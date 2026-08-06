package com.talkingvillagers.family;

import java.util.List;
import java.util.Optional;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.Relationships;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;

/**
 * What a death does to the villagers left behind.
 *
 * <p>Two things happen. Anyone who cared about the deceased grieves, which shows in their
 * particles, their prompts and the village's gossip. And if a player was responsible, the
 * blame is severe and falls hardest on family — this is the main route by which a relationship
 * crashes all the way to the permanent zero lock, and it is meant to be.
 */
public final class Grief {
	/** How long grief lasts: one in-game day. */
	public static final long GRIEF_DURATION_TICKS = 24000L;

	/** How far the news of a death spreads to villagers who did not witness it. */
	private static final double MOURNING_RADIUS = 48.0;

	/** Relationship damage to the deceased's family when a player is responsible. */
	private static final int FAMILY_BLAME = -60;
	/** Relationship damage to friends of the deceased. */
	private static final int FRIEND_BLAME = -30;
	/** Relationship damage to everyone else in earshot. */
	private static final int BYSTANDER_BLAME = -10;

	private Grief() {
	}

	/**
	 * Handles a villager's death.
	 *
	 * <p>Note this only reaches villagers currently loaded. A relative in an unloaded chunk
	 * will not learn of the death; making that work would mean tracking every villager's
	 * family in world-level storage, which is not worth the cost for a mourning animation.
	 */
	public static void onVillagerDied(Villager deceased, DamageSource source) {
		if (!(deceased.level() instanceof ServerLevel level)) {
			return;
		}
		VillagerSoul deceasedSoul = deceased.getAttached(Attachments.SOUL);
		if (deceasedSoul == null) {
			return;
		}

		long gameTime = level.getGameTime();
		String deceasedName = deceasedSoul.identity().firstName();
		Optional<Player> culprit = playerResponsible(source);
		Settlements.recordVillageEvent(deceasedSoul, level, gameTime, deceasedName + " died");

		List<Villager> mourners = level.getEntitiesOfClass(
			Villager.class,
			deceased.getBoundingBox().inflate(MOURNING_RADIUS),
			villager -> villager != deceased && villager.isAlive());

		for (Villager mourner : mourners) {
			VillagerSoul soul = mourner.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}

			Optional<Relationship> tie = soul.relationship(deceased.getUUID());
			Bond bond = tie.map(Relationship::bond).orElse(Bond.NONE);
			int regard = tie.map(Relationship::meter).orElse(Relationship.NEUTRAL);
			boolean close = bond.family() || bond.romantic() || regard >= Relationship.FRIEND_THRESHOLD;

			// A spouse becomes a memorial rather than simply vanishing from the relationship
			// map: their name would otherwise be unrecoverable the moment the entity is gone,
			// since every other lookup resolves names by looking the live entity back up.
			if (bond == Bond.SPOUSE) {
				soul.setRelationship(tie.get().widowed(deceasedName));
			}

			if (close) {
				soul.grieveUntil(gameTime + GRIEF_DURATION_TICKS);
				soul.remember(gameTime, "lost " + deceasedName + " and still mourns them");
				sadParticles(level, mourner);
			} else {
				soul.remember(gameTime, deceasedName + " died");
			}

			culprit.ifPresent(player -> {
				int blame = bond.family() || bond.romantic()
					? FAMILY_BLAME
					: close ? FRIEND_BLAME : BYSTANDER_BLAME;
				Relationships.apply(soul, player.getUUID(), blame, gameTime, level,
					player.getName().getString() + " is to blame for " + deceasedName + "'s death");
			});
		}

		if (culprit.isPresent()) {
			TalkingVillagers.LOGGER.debug("{} died; {} blamed by {} nearby villagers",
				deceasedName, culprit.get().getName().getString(), mourners.size());
		}
	}

	/**
	 * The player at fault, if any.
	 *
	 * <p>Covers indirect kills — an arrow, a tamed wolf, a summoned mob — because a villager
	 * has no way to tell the difference and neither should the blame.
	 */
	private static Optional<Player> playerResponsible(DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker instanceof Player player) {
			return Optional.of(player);
		}
		Entity direct = source.getDirectEntity();
		if (direct instanceof Player player) {
			return Optional.of(player);
		}
		return Optional.empty();
	}

	/**
	 * Per-sweep upkeep for a grieving villager: sad particles, and a nudge away from work.
	 *
	 * <p>Setting the activity to idle is advisory — the villager's schedule will reassert
	 * itself — which is deliberate. It produces visible loitering without corrupting the brain
	 * state the way clearing their job site would.
	 */
	public static void tickGrief(Villager villager, ServerLevel level) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null || !soul.grieving(level.getGameTime())) {
			return;
		}

		if (level.getRandom().nextInt(3) == 0) {
			sadParticles(level, villager);
		}
		if (!villager.isSleeping()) {
			villager.getBrain().setActiveActivityIfPossible(Activity.IDLE);
		}
	}

	/**
	 * Ends mourning for everyone currently grieving in this level. Called once a day, from the
	 * same daily ceremony sweep that marries engaged couples — a day's-end rhythm rather than a
	 * fixed duration counted from the moment of loss.
	 */
	public static void endDailyMourning(ServerLevel level) {
		long gameTime = level.getGameTime();
		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null && soul.grieving(gameTime)) {
				soul.endGrieving();
			}
		}
	}

	private static void sadParticles(ServerLevel level, Villager villager) {
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
			villager.getX(), villager.getEyeY() + 0.4, villager.getZ(), 2, 0.25, 0.1, 0.25, 0.0);
		// Falling water at eye height reads as tears.
		level.sendParticles(ParticleTypes.FALLING_WATER,
			villager.getX(), villager.getEyeY(), villager.getZ(), 3, 0.15, 0.05, 0.15, 0.0);
	}
}
