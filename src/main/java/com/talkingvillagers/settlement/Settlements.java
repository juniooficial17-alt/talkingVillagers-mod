package com.talkingvillagers.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Finds settlements, works out who belongs to them, and handles succession.
 *
 * <p>Settlements are discovered rather than declared: any bell with villagers around it is a
 * settlement, found through vanilla's {@code MEETING} point-of-interest index. That means the
 * mod picks up naturally generated villages, villages the player builds, and a bell dropped in
 * the wilderness, all through one mechanism — and "place a bell to found a settlement" falls
 * out for free.
 */
public final class Settlements {
	/** How far from a villager to look for a bell when assigning them a home settlement. */
	private static final int BELL_SEARCH_RADIUS = 48;

	private Settlements() {
	}

	/**
	 * Ensures the settlement around this villager exists and that the villager is a member.
	 *
	 * @return the villager's settlement, or empty if there is no bell anywhere near them
	 */
	public static Optional<Settlement> assign(Villager villager, ServerLevel level) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null) {
			return Optional.empty();
		}

		SettlementData data = SettlementData.get(level.getServer());
		TalkingVillagersConfig config = TalkingVillagers.config();

		// Still a member of the settlement they already belong to?
		Optional<Settlement> current = soul.settlement().flatMap(data::byId);
		if (current.isPresent() && withinRadius(villager, current.get(), level, config)) {
			return current;
		}

		Optional<BlockPos> bellPos = findBell(villager, level);
		if (bellPos.isEmpty()) {
			soul.setSettlement(Optional.empty());
			return Optional.empty();
		}

		GlobalPos bell = GlobalPos.of(level.dimension(), bellPos.get());
		Settlement settlement = data.byBell(bell)
			// A second bell close to one that is already known belongs to the same village rather
			// than founding a rival one next door. Villages really do generate with two bells a
			// few blocks apart, and without this the place ends up with two names, two mayors and
			// two of every announcement, all drawn from the same residents.
			.or(() -> nearbyBell(bell, data))
			.orElseGet(() -> found(bell, level, data));
		soul.setSettlement(Optional.of(settlement.id()));
		// Freshly joining a village that already has a mayor: they already know who is in charge,
		// the same as anyone who was elected or claimed the place while this villager was around.
		long gameTime = level.getGameTime();
		greetMayor(settlement, villager, level, gameTime);
		seedAmbientRelationships(villager, soul, settlement, level, gameTime);
		return Optional.of(settlement);
	}

	/**
	 * Gives a villager a plausible starting opinion of everyone else already living in the
	 * settlement, instead of the blank neutral slate a brand new relationship starts at — a
	 * village is a place people already know each other, not a room full of strangers who
	 * happen to share a bell.
	 *
	 * <p>Only ever fills in a pair that has never met: an existing relationship, however it got
	 * there — gameplay, or {@link com.talkingvillagers.family.FamilySeeding} — is left untouched.
	 */
	private static void seedAmbientRelationships(
		Villager villager, VillagerSoul soul, Settlement settlement, ServerLevel level, long gameTime
	) {
		RandomSource random = level.getRandom();
		for (Villager other : residents(settlement, level)) {
			if (other == villager || soul.relationship(other.getUUID()).isPresent()) {
				continue;
			}
			VillagerSoul otherSoul = other.getAttached(Attachments.SOUL);
			if (otherSoul == null || otherSoul.relationship(villager.getUUID()).isPresent()) {
				continue;
			}

			int meter = rollAmbientMeter(random);
			soul.setRelationship(Relationship.fresh(other.getUUID(), gameTime).withMeter(meter));
			otherSoul.setRelationship(Relationship.fresh(villager.getUUID(), gameTime).withMeter(meter));
		}
	}

	/**
	 * A starting opinion with realistic variety: mostly acquaintances, plenty of friends, a rare
	 * best friend, and occasionally someone who simply rubs them the wrong way. Never all the
	 * way down to a sworn enemy — that lock is earned through actual events, not backstory.
	 */
	private static int rollAmbientMeter(RandomSource random) {
		double roll = random.nextDouble();
		if (roll < 0.07) {
			return 10 + random.nextInt(16);
		}
		if (roll < 0.67) {
			return 35 + random.nextInt(31);
		}
		if (roll < 0.92) {
			return 66 + random.nextInt(24);
		}
		return 90 + random.nextInt(11);
	}

	/** An existing settlement close enough to this bell that they must be the same village. */
	private static Optional<Settlement> nearbyBell(GlobalPos bell, SettlementData data) {
		double radius = Tuning.Settlement.BELL_MERGE_RADIUS;
		return data.all().stream()
			.filter(settlement -> settlement.bell().dimension().equals(bell.dimension()))
			.filter(settlement -> bell.pos().distSqr(settlement.bell().pos()) <= radius * radius)
			.min(Comparator.comparingDouble(settlement -> bell.pos().distSqr(settlement.bell().pos())));
	}

	/**
	 * Folds settlements that turned out to be the same village into one.
	 *
	 * <p>{@link #assign} stops new ones being created, but a world that has already been played
	 * carries the duplicates it made earlier, and they do not go away on their own. This repairs
	 * them in place, once, as each village comes into range.
	 *
	 * <p>The survivor is whichever a player has claimed, or else the older of the two — never the
	 * newcomer, so a village does not lose its name or its history to a bell placed yesterday.
	 */
	public static void mergeOverlapping(ServerLevel level) {
		SettlementData data = SettlementData.get(level.getServer());
		List<Settlement> here = data.all().stream()
			.filter(settlement -> settlement.bell().dimension().equals(level.dimension()))
			.toList();
		if (here.size() < 2) {
			return;
		}

		double radius = Tuning.Settlement.BELL_MERGE_RADIUS;
		for (int i = 0; i < here.size(); i++) {
			for (int j = i + 1; j < here.size(); j++) {
				Settlement one = here.get(i);
				Settlement two = here.get(j);
				if (one.bell().pos().distSqr(two.bell().pos()) > radius * radius) {
					continue;
				}

				Settlement survivor = preferred(one, two);
				Settlement absorbed = survivor == one ? two : one;
				// Residents point at a settlement id, so anyone still pointing at the absorbed one
				// is repointed rather than left orphaned and re-founding it on the next sweep.
				repoint(absorbed.id(), survivor.id(), level);
				data.remove(absorbed.id());
				TalkingVillagers.LOGGER.info(
					"{} and {} share one village; keeping {}", one.name(), two.name(), survivor.name());
				// The list is now stale, so stop and let the next pass find any further overlap.
				return;
			}
		}
	}

	/** Which of two duplicates should survive a merge. */
	private static Settlement preferred(Settlement one, Settlement two) {
		if (one.mayorPlayer().isPresent() != two.mayorPlayer().isPresent()) {
			return one.mayorPlayer().isPresent() ? one : two;
		}
		return one.foundedGameTime() <= two.foundedGameTime() ? one : two;
	}

	/** Moves every loaded villager from one settlement id to another. */
	private static void repoint(UUID from, UUID to, ServerLevel level) {
		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null && soul.settlement().filter(from::equals).isPresent()) {
				soul.setSettlement(Optional.of(to));
			}
		}
	}

	/**
	 * Keeps a settlement anchored to a bell that still stands, and disbands it when its last
	 * bell is gone.
	 *
	 * <p>Only checked while the anchor's chunk is loaded — an unloaded bell cannot have been
	 * broken by anyone. If the anchor block is no longer a bell, the nearest other bell inside
	 * the village keeps it alive as the new anchor; with no bell left the village is disbanded
	 * outright: residents are released and the settlement is deleted, so whichever bell is
	 * placed there next founds a fresh village with a new random name and a clean record.
	 *
	 * @return whether the settlement still exists afterwards
	 */
	public static boolean verifyBell(Settlement settlement, ServerLevel level, SettlementData data) {
		BlockPos pos = settlement.bell().pos();
		if (!level.isLoaded(pos) || level.getBlockState(pos).is(Blocks.BELL)) {
			return true;
		}

		Optional<BlockPos> replacement = level.getPoiManager().findClosest(
			holder -> holder.is(PoiTypes.MEETING),
			pos,
			(int) Tuning.Settlement.BELL_RADIUS,
			PoiManager.Occupancy.ANY);
		if (replacement.isPresent()) {
			settlement.setBell(GlobalPos.of(level.dimension(), replacement.get()));
			data.markChanged();
			TalkingVillagers.LOGGER.info(
				"{} lost its bell; re-anchored to the one at {}", settlement.name(), replacement.get());
			return true;
		}

		// Loaded villagers are released now; anyone unloaded still points at the dead id, which
		// resolves to nothing and is replaced the next time assignment finds them near a bell.
		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null && soul.settlement().filter(settlement.id()::equals).isPresent()) {
				soul.setSettlement(Optional.empty());
			}
		}
		data.remove(settlement.id());
		TalkingVillagers.LOGGER.info("{} lost its last bell and is disbanded", settlement.name());
		return false;
	}

	/** Creates a settlement around a newly discovered bell. */
	private static Settlement found(GlobalPos bell, ServerLevel level, SettlementData data) {
		RandomSource random = level.getRandom();
		TalkingVillagersConfig config = TalkingVillagers.config();
		String name = config.names.settlementPrefixes.get(
			random.nextInt(config.names.settlementPrefixes.size()))
			+ config.names.settlementSuffixes.get(
				random.nextInt(config.names.settlementSuffixes.size()));

		Settlement settlement = new Settlement(
			UUID.randomUUID(), name, bell, level.getGameTime());
		data.add(settlement);
		TalkingVillagers.LOGGER.info("Discovered settlement {} at {}", name, bell.pos());
		return settlement;
	}

	/** The nearest bell, via vanilla's meeting-point index. */
	private static Optional<BlockPos> findBell(Villager villager, ServerLevel level) {
		PoiManager poi = level.getPoiManager();
		return poi.findClosest(
			holder -> holder.is(PoiTypes.MEETING),
			villager.blockPosition(),
			BELL_SEARCH_RADIUS,
			PoiManager.Occupancy.ANY);
	}

	private static boolean withinRadius(
		Villager villager, Settlement settlement, ServerLevel level, TalkingVillagersConfig config
	) {
		if (!settlement.bell().dimension().equals(level.dimension())) {
			return false;
		}
		double radius = Tuning.Settlement.BELL_RADIUS;
		return villager.blockPosition().distSqr(settlement.bell().pos()) <= radius * radius;
	}

	/** Every loaded villager belonging to this settlement. */
	public static List<Villager> residents(Settlement settlement, ServerLevel level) {
		if (!settlement.bell().dimension().equals(level.dimension())) {
			return List.of();
		}

		double radius = Tuning.Settlement.BELL_RADIUS;
		AABB box = new AABB(settlement.bell().pos()).inflate(radius);
		return level.getEntitiesOfClass(Villager.class, box,
			villager -> villager.isAlive() && villager.getAttached(Attachments.SOUL) != null);
	}

	/**
	 * The settlement a position belongs to.
	 *
	 * <p>An exact bell match wins, then the nearest settlement whose radius covers the position.
	 * The fallback matters for the bell itself: a village can gain a second bell, and a player
	 * clicking that one plainly means the village they are standing in rather than nothing at all.
	 */
	public static Optional<Settlement> at(BlockPos pos, ServerLevel level) {
		SettlementData data = SettlementData.get(level.getServer());
		Optional<Settlement> exact = data.byBell(GlobalPos.of(level.dimension(), pos));
		if (exact.isPresent()) {
			return exact;
		}

		double radius = Tuning.Settlement.BELL_RADIUS;
		return data.all().stream()
			.filter(settlement -> settlement.bell().dimension().equals(level.dimension()))
			.filter(settlement -> pos.distSqr(settlement.bell().pos()) <= radius * radius)
			.min(Comparator.comparingDouble(settlement -> pos.distSqr(settlement.bell().pos())));
	}

	/** A settlement by id, for callers that hold one across ticks. */
	public static Optional<Settlement> byId(UUID id, ServerLevel level) {
		return SettlementData.get(level.getServer()).byId(id);
	}

	/**
	 * Notes something worth the village's own record against whichever settlement {@code soul}
	 * belongs to. Does nothing if they belong to none — a villager with no bell nearby has no
	 * record to write into.
	 */
	public static void recordVillageEvent(VillagerSoul soul, ServerLevel level, long gameTime, String text) {
		soul.settlement().flatMap(id -> byId(id, level)).ifPresent(settlement -> {
			settlement.recordEvent(gameTime, text);
			SettlementData.get(level.getServer()).markChanged();
		});
	}

	/** As {@link #recordVillageEvent}, for callers that already hold the settlement itself. */
	public static void recordEvent(Settlement settlement, ServerLevel level, long gameTime, String text) {
		settlement.recordEvent(gameTime, text);
		SettlementData.get(level.getServer()).markChanged();
	}

	/**
	 * Notes a conversation against whichever settlement {@code soul} belongs to, the same way
	 * {@link #recordVillageEvent} does for events.
	 */
	public static void recordVillageGossip(
		VillagerSoul soul, ServerLevel level, long gameTime,
		String key, String participantOne, String participantTwo, String summary
	) {
		soul.settlement().flatMap(id -> byId(id, level)).ifPresent(settlement -> {
			settlement.recordGossip(gameTime, key, participantOne, participantTwo, summary);
			SettlementData.get(level.getServer()).markChanged();
		});
	}

	/**
	 * Adds a model-invented topic to a settlement's daily pool.
	 *
	 * @return whether it was added — false once the pool is full or if the topic is a repeat
	 */
	public static boolean recordGossipTopic(Settlement settlement, ServerLevel level, String topic) {
		if (!settlement.addGossipTopic(topic)) {
			return false;
		}
		SettlementData.get(level.getServer()).markChanged();
		return true;
	}

	/**
	 * Records a raid's outcome against whichever settlement it happened at, if any.
	 *
	 * <p>Called from {@code RaidMixin} the instant a {@code Raid} reports itself over — there is
	 * no vanilla event for this, so the mixin is the only source. A raid with no settlement
	 * nearby (the centre falls outside every known bell's radius) is simply not recorded.
	 */
	public static void onRaidConcluded(ServerLevel level, BlockPos center, boolean victory) {
		Optional<Settlement> settlement = at(center, level);
		if (settlement.isEmpty()) {
			return;
		}
		settlement.get().recordRaid(level.getGameTime(), victory);
		SettlementData.get(level.getServer()).markChanged();
	}

	/**
	 * Keeps a settlement's leadership valid: elects one of its own when nobody leads it, and
	 * replaces a villager mayor who has died.
	 *
	 * <p>Every village governs itself. It elects a mayor the moment it is noticed and never asks
	 * a player to take the job — a player who wants it claims the village outright, at the bell.
	 */
	public static void updateLeadership(Settlement settlement, ServerLevel level) {
		// A player in charge stays in charge, whether or not they are anywhere near the place.
		if (settlement.mayorPlayer().isPresent()) {
			return;
		}

		SettlementData data = SettlementData.get(level.getServer());

		Optional<UUID> villagerMayor = settlement.mayorVillager();
		if (villagerMayor.isPresent()) {
			Entity found = level.getEntityInAnyDimension(villagerMayor.get());

			// A mayor nobody can currently see keeps the post. This distinction matters more than
			// it looks: an entity lookup cannot tell "dead" from "in an unloaded chunk", and
			// treating the second as the first deposed the mayor every time the village partly
			// unloaded, so a village quietly held elections all day. Losing the office happens
			// through death, which is observed as it happens — see {@link #onResidentDied}.
			if (found == null) {
				return;
			}
			// Visible and still a living villager: nothing to do. Anything else — a corpse mid
			// removal, or a mayor who has been turned into a zombie villager — vacates the office.
			if (found instanceof Villager mayor && mayor.isAlive()) {
				return;
			}
			settlement.clearMayor();
			data.markChanged();
		}

		electVillagerMayor(settlement, level, data);
	}

	/**
	 * Vacates the office if this villager held it, so a successor is elected on the next pass.
	 *
	 * <p>Driven from the death event rather than from a periodic liveness check, because that is
	 * the only moment the mod can be certain: an entity that has died is removed immediately, and
	 * a moment later is indistinguishable from one standing in an unloaded chunk.
	 */
	public static void onResidentDied(Villager villager, ServerLevel level) {
		SettlementData data = SettlementData.get(level.getServer());
		UUID id = villager.getUUID();

		for (Settlement settlement : data.all()) {
			if (settlement.mayorVillager().filter(id::equals).isEmpty()) {
				continue;
			}
			settlement.clearMayor();
			data.markChanged();

			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			String name = soul == null ? "Their mayor" : soul.identity().fullName();
			recordEvent(settlement, level, level.getGameTime(),
				name + " is dead; " + settlement.name() + " must choose again");
			TalkingVillagers.LOGGER.info("{} died; {} needs a new mayor", name, settlement.name());
		}
	}

	/**
	 * Elects the resident with the highest summed regard from everyone else — the villager the
	 * settlement collectively thinks most of.
	 */
	public static Optional<Villager> electVillagerMayor(
		Settlement settlement, ServerLevel level, SettlementData data
	) {
		List<Villager> residents = residents(settlement, level).stream()
			.filter(villager -> !villager.isBaby())
			.toList();
		if (residents.isEmpty()) {
			return Optional.empty();
		}

		Optional<Villager> winner = residents.stream()
			.max(Comparator.comparingInt(candidate -> standing(candidate, residents)));
		if (winner.isEmpty()) {
			return Optional.empty();
		}

		Villager mayor = winner.get();
		settlement.setVillagerMayor(mayor.getUUID());
		data.markChanged();

		VillagerSoul soul = mayor.getAttached(Attachments.SOUL);
		long gameTime = level.getGameTime();
		if (soul != null) {
			soul.remember(gameTime, "was chosen to lead " + settlement.name());
		}

		// The village already knows its new mayor: every resident starts out at least on good
		// terms with them, and the mayor with every resident in turn.
		for (Villager resident : residents) {
			greetMayor(settlement, resident, level, gameTime);
		}

		String name = soul == null ? "A villager" : soul.identity().fullName();
		recordEvent(settlement, level, gameTime,
			name + " was chosen to lead " + settlement.name());
		TalkingVillagers.LOGGER.info("{} elected mayor of {}", name, settlement.name());
		return Optional.of(mayor);
	}

	/** How well regarded a candidate is by everyone else in the settlement. */
	private static int standing(Villager candidate, List<Villager> residents) {
		int total = 0;
		for (Villager resident : residents) {
			if (resident == candidate) {
				continue;
			}
			VillagerSoul soul = resident.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}
			total += soul.relationship(candidate.getUUID())
				.map(Relationship::meter)
				.orElse(Relationship.NEUTRAL);
		}
		return total;
	}

	/**
	 * Has {@code resident} start out already knowing the settlement's mayor — floored at
	 * {@link Relationship#FRIEND_THRESHOLD} rather than a stranger's neutral — and, when the
	 * mayor is a fellow villager rather than a player, has the mayor regard this resident just
	 * as warmly in return. Does nothing if the settlement currently has no mayor at all.
	 *
	 * <p>Only ever raises a relationship that has no considered opinion behind it yet (see
	 * {@link Relationship#interactions()}), so a villager who already loves or, through actual
	 * events, has come to hate their mayor keeps that: this fills in a starting value for
	 * strangers, it does not overwrite a story that has already happened.
	 */
	public static void greetMayor(
		Settlement settlement, Villager resident, ServerLevel level, long gameTime
	) {
		if (!settlement.hasMayor()) {
			return;
		}
		// The mayor doesn't need a relationship with themselves.
		if (settlement.mayorVillager().filter(resident.getUUID()::equals).isPresent()) {
			return;
		}

		UUID mayorId = settlement.mayorVillager().or(settlement::mayorPlayer).orElse(null);
		if (mayorId == null) {
			return;
		}

		VillagerSoul residentSoul = resident.getAttached(Attachments.SOUL);
		if (residentSoul != null) {
			raiseFloor(residentSoul, mayorId, gameTime);
		}

		// Symmetric only when the mayor is a villager: a player mayor has no VillagerSoul to
		// hold the reverse entry.
		settlement.mayorVillager().ifPresent(villagerMayorId -> {
			if (level.getEntityInAnyDimension(villagerMayorId) instanceof Villager mayor) {
				VillagerSoul mayorSoul = mayor.getAttached(Attachments.SOUL);
				if (mayorSoul != null) {
					raiseFloor(mayorSoul, resident.getUUID(), gameTime);
				}
			}
		});
	}

	/**
	 * Raises {@code soul}'s regard for {@code target} to {@link Relationship#FRIEND_THRESHOLD}
	 * if it is currently lower, but only while there is no considered opinion behind the current
	 * value — leaves alone anyone they have actually interacted with, whatever the outcome.
	 */
	private static void raiseFloor(VillagerSoul soul, UUID target, long gameTime) {
		Optional<Relationship> current = soul.relationship(target);
		if (current.isPresent() && current.get().interactions() > 0) {
			return;
		}
		Relationship base = current.orElseGet(() -> Relationship.fresh(target, gameTime));
		if (base.meter() < Relationship.FRIEND_THRESHOLD) {
			soul.setRelationship(base.withMeter(Relationship.FRIEND_THRESHOLD));
		}
	}

	/** Sends a message to every player currently inside a settlement. */
	public static void announce(Settlement settlement, ServerLevel level, Component message) {
		double radius = Tuning.Settlement.BELL_RADIUS;
		for (var player : level.players()) {
			if (player.blockPosition().distSqr(settlement.bell().pos()) <= radius * radius) {
				player.sendSystemMessage(message);
			}
		}
	}

	/** The mayor's name for display, whoever or whatever they are. */
	public static String describeMayor(Settlement settlement, ServerLevel level) {
		if (settlement.mayorPlayer().isPresent()) {
			var player = level.getServer().getPlayerList().getPlayer(settlement.mayorPlayer().get());
			String name = player == null ? "an absent player" : player.getName().getString();
			return settlement.mayorTitle() + " " + name;
		}
		return settlement.mayorVillager()
			.map(id -> {
				if (level.getEntityInAnyDimension(id) instanceof Villager mayor) {
					VillagerSoul soul = mayor.getAttached(Attachments.SOUL);
					if (soul != null) {
						return "Mayor " + soul.identity().fullName();
					}
				}
				return "an unknown mayor";
			})
			.orElse("nobody");
	}

	/** Counts residents by profession, for reports and the needs system. */
	public static Map<String, Integer> professionCounts(List<Villager> residents) {
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (Villager villager : residents) {
			String profession = villager.getVillagerData().profession()
				.unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("unknown");
			counts.merge(profession, 1, Integer::sum);
		}
		return counts;
	}

	/** Residents with no trade who are old enough to want one. */
	public static List<Villager> joblessAdults(List<Villager> residents) {
		List<Villager> jobless = new ArrayList<>();
		for (Villager villager : residents) {
			if (villager.isBaby()) {
				continue;
			}
			var profession = villager.getVillagerData().profession();
			// Nitwits are content to have no trade; the mod leaves them alone.
			if (profession.is(net.minecraft.world.entity.npc.villager.VillagerProfession.NONE)) {
				jobless.add(villager);
			}
		}
		return jobless;
	}
}
