package com.talkingvillagers.social;

import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.settlement.Settlements;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Applies relationship changes to villagers, and translates the mod's meter into something
 * vanilla's trading code understands.
 *
 * <p>Most of the interesting events are taken from vanilla rather than detected separately.
 * Minecraft already tells villagers "this entity traded with you", "this entity hurt me" and
 * "this entity murdered someone in front of you" through its reputation system, including the
 * witness logic — so {@link #onVanillaReputationEvent} is the single hook that covers being
 * hit, being traded with, seeing a neighbour killed, and being cured of zombification.
 */
public final class Relationships {
	/** A fair trade nudges regard up. Small, because trading happens constantly. */
	public static final int TRADE_DELTA = 2;
	/** Being handed something the villager wanted. */
	public static final int GIFT_DELTA = 8;
	/**
	 * Being struck. Large enough that sustained violence walks a villager down to the
	 * permanent zero lock in a handful of blows, small enough that one stray swing is
	 * forgivable.
	 */
	public static final int HURT_DELTA = -15;
	/** Watching this entity kill one of your neighbours. */
	public static final int WITNESS_MURDER_DELTA = -40;
	/** Being cured of zombification — the strongest positive in the game. */
	public static final int CURED_DELTA = 30;
	/** Destroying the village's iron golem. */
	public static final int GOLEM_KILLED_DELTA = -12;

	/** Below this magnitude an event moves the meter but is not worth a memory. */
	private static final int MEMORABLE_DELTA = 10;

	private Relationships() {
	}

	/**
	 * Records a vanilla reputation event against the mod's own relationship meter.
	 *
	 * <p>Called for every villager vanilla notifies, so this runs for witnesses as well as
	 * victims, and for villager-to-villager events as well as player ones.
	 */
	public static void onVanillaReputationEvent(Villager villager, ReputationEventType type, Entity source) {
		if (!(villager.level() instanceof ServerLevel level)) {
			return;
		}
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null || source.getUUID().equals(villager.getUUID())) {
			return;
		}

		long gameTime = level.getGameTime();
		String actor = displayName(source, level);

		if (type == ReputationEventType.TRADE) {
			apply(soul, source.getUUID(), TRADE_DELTA, gameTime, level, null);
		} else if (type == ReputationEventType.VILLAGER_HURT) {
			apply(soul, source.getUUID(), HURT_DELTA, gameTime, level, "was struck by " + actor);
		} else if (type == ReputationEventType.VILLAGER_KILLED) {
			apply(soul, source.getUUID(), WITNESS_MURDER_DELTA, gameTime, level,
				"watched " + actor + " kill a neighbour");
		} else if (type == ReputationEventType.ZOMBIE_VILLAGER_CURED) {
			apply(soul, source.getUUID(), CURED_DELTA, gameTime, level,
				actor + " cured a villager of the plague");
		} else if (type == ReputationEventType.GOLEM_KILLED) {
			apply(soul, source.getUUID(), GOLEM_KILLED_DELTA, gameTime, level,
				actor + " destroyed the village golem");
		}
	}

	/** Records a player handing a villager something it wanted. */
	public static void onGiftReceived(Villager villager, Player giver, ItemStack stack) {
		if (!(villager.level() instanceof ServerLevel level)) {
			return;
		}
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null) {
			return;
		}

		String item = stack.getHoverName().getString();
		apply(soul, giver.getUUID(), GIFT_DELTA, level.getGameTime(), level,
			giver.getName().getString() + " gave me " + item);
	}

	/**
	 * Applies a change and, for anything significant, records a memory the villager can talk
	 * about later.
	 */
	public static Relationship apply(
		VillagerSoul soul, UUID target, int delta, long gameTime, ServerLevel level, String memory
	) {
		Relationship before = soul.relationship(target).orElse(null);
		Relationship after = soul.adjustRelationship(target, delta, gameTime);

		if (memory != null && Math.abs(delta) >= MEMORABLE_DELTA) {
			soul.remember(gameTime, memory);
		}
		// Crossing into permanent hatred is a one-way door, so it is always worth remembering
		// even when the blow that caused it was individually minor.
		if (after.lockedInHatred() && (before == null || !before.lockedInHatred())) {
			soul.remember(gameTime, "will never forgive them");
			Settlements.recordVillageEvent(soul, level, gameTime,
				soul.identity().firstName() + " now considers "
					+ targetName(target, after.memorialName(), level) + " a sworn enemy");
		}
		return after;
	}

	/** The best name available for a relationship target: a live player, a live villager, or a
	 * memorial name snapshotted for someone no longer reachable. */
	private static String targetName(UUID target, String memorialName, ServerLevel level) {
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(target);
		if (player != null) {
			return player.getName().getString();
		}
		if (level.getEntityInAnyDimension(target) instanceof Villager villager) {
			VillagerSoul targetSoul = villager.getAttached(Attachments.SOUL);
			if (targetSoul != null) {
				return targetSoul.identity().firstName();
			}
		}
		return memorialName.isEmpty() ? "someone" : memorialName;
	}

	/**
	 * Extra vanilla reputation contributed by the mod's meter, blended into trade prices.
	 *
	 * <p>Vanilla multiplies a villager's reputation by each offer's price multiplier to
	 * discount or mark up trades. Mapping the meter onto the same scale means a well-liked
	 * player gets cheaper trades through the ordinary vanilla path, stacking with
	 * hero-of-the-village rather than fighting it.
	 */
	public static int priceReputationOffset(Villager villager, Player player) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null) {
			return 0;
		}
		return soul.relationship(player.getUUID())
			.map(relationship -> (relationship.meter() - Relationship.NEUTRAL) / 2)
			.orElse(0);
	}

	/**
	 * Whether this villager has been driven to permanent hatred of the player and will not
	 * trade with them at all.
	 */
	public static boolean refusesToTrade(Villager villager, Player player) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null) {
			return false;
		}
		return soul.relationship(player.getUUID())
			.map(Relationship::lockedInHatred)
			.orElse(false);
	}

	/** How an entity should be referred to in another villager's memory. */
	public static String displayName(Entity entity, ServerLevel level) {
		if (entity instanceof Player player) {
			return player.getName().getString();
		}
		if (entity instanceof Villager villager) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null) {
				return soul.identity().firstName();
			}
		}
		return entity.getName().getString();
	}

	/** The relationship a villager holds with an entity, if any. */
	public static Optional<Relationship> between(Villager villager, UUID target) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		return soul == null ? Optional.empty() : soul.relationship(target);
	}
}
