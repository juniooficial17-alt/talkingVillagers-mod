package com.talkingvillagers.family;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.VillagerIdentity;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Moves villagers along the courtship pipeline: friends become lovers, lovers become engaged,
 * and engaged couples wait for a wedding.
 *
 * <p>Progress needs mutual regard, not one-sided infatuation — both villagers must clear the
 * threshold — and it respects orientation, so a pairing that one of them would never want
 * cannot form. Asexual villagers are skipped entirely.
 *
 * <p>The final step to married is deliberately not here: it requires a ceremony, which is
 * {@link Weddings}.
 */
public final class Courtship {
	/** How close two villagers must be for their relationship to advance. */
	private static final double COURTING_RADIUS = 8.0;

	private Courtship() {
	}

	/**
	 * Evaluates one villager's romantic prospects. Cheap enough to call from the periodic
	 * sweep, since it only looks at villagers already nearby.
	 */
	public static void evaluate(Villager villager, ServerLevel level) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null || !soul.identity().romantic() || villager.isBaby()) {
			return;
		}
		// Monogamy: anyone already spoken for is not looking. A widowed villager never looks
		// again either — Ex-Spouse is a permanent status, not a cooldown.
		if (!soul.withBond(Bond.SPOUSE).isEmpty() || !soul.withBond(Bond.ENGAGED).isEmpty()
			|| !soul.withBond(Bond.WIDOWED).isEmpty()) {
			return;
		}

		long gameTime = level.getGameTime();
		Optional<UUID> lover = soul.withBond(Bond.LOVER).stream().findFirst();
		if (lover.isPresent()) {
			if (maybeBreakUp(villager, soul, lover.get(), level, gameTime)) {
				return;
			}
			maybeEngage(villager, soul, lover.get(), level, gameTime);
			return;
		}

		maybeFallInLove(villager, soul, level, gameTime);
	}

	/** Looks for a nearby villager they both fancy enough to become a couple. */
	private static void maybeFallInLove(
		Villager villager, VillagerSoul soul, ServerLevel level, long gameTime
	) {
		VillagerIdentity identity = soul.identity();

		List<Villager> nearby = level.getEntitiesOfClass(
			Villager.class,
			villager.getBoundingBox().inflate(COURTING_RADIUS),
			candidate -> candidate != villager && candidate.isAlive() && !candidate.isBaby());

		for (Villager other : nearby) {
			VillagerSoul otherSoul = other.getAttached(Attachments.SOUL);
			if (otherSoul == null || !otherSoul.identity().romantic()) {
				continue;
			}
			if (!otherSoul.withBond(Bond.SPOUSE).isEmpty()
				|| !otherSoul.withBond(Bond.ENGAGED).isEmpty()
				|| !otherSoul.withBond(Bond.LOVER).isEmpty()
				|| !otherSoul.withBond(Bond.WIDOWED).isEmpty()) {
				continue;
			}

			VillagerIdentity otherIdentity = otherSoul.identity();
			// Family never becomes romance, however fond they are of each other.
			Bond existing = soul.relationship(other.getUUID()).map(Relationship::bond).orElse(Bond.NONE);
			if (existing.family()) {
				continue;
			}
			if (!identity.attractedTo(otherIdentity.genderIdentity())
				|| !otherIdentity.attractedTo(identity.genderIdentity())) {
				continue;
			}

			int mine = soul.relationship(other.getUUID()).map(Relationship::meter).orElse(0);
			int theirs = otherSoul.relationship(villager.getUUID()).map(Relationship::meter).orElse(0);
			if (mine < Tuning.Family.LOVERS_THRESHOLD || theirs < Tuning.Family.LOVERS_THRESHOLD) {
				continue;
			}

			soul.setBond(other.getUUID(), Bond.LOVER, gameTime);
			otherSoul.setBond(villager.getUUID(), Bond.LOVER, gameTime);
			soul.remember(gameTime, "fell in love with " + otherIdentity.firstName());
			otherSoul.remember(gameTime, "fell in love with " + identity.firstName());
			celebrate(level, villager, other, ParticleTypes.HEART);
			return;
		}
	}

	/**
	 * Ends a dating couple once either side's regard has cooled below the same threshold that
	 * brought them together. Deliberately silent — no memory, no announcement — since this is
	 * simply what the meter already says about them, not an event that happened to them.
	 *
	 * @return true if the couple broke up, so the caller does not also evaluate engagement for a
	 *         pairing that no longer exists
	 */
	private static boolean maybeBreakUp(
		Villager villager, VillagerSoul soul, UUID loverId, ServerLevel level, long gameTime
	) {
		if (!(level.getEntityInAnyDimension(loverId) instanceof Villager lover)) {
			return false;
		}
		VillagerSoul loverSoul = lover.getAttached(Attachments.SOUL);
		if (loverSoul == null) {
			return false;
		}

		int mine = soul.relationship(loverId).map(Relationship::meter).orElse(Relationship.NEUTRAL);
		int theirs = loverSoul.relationship(villager.getUUID()).map(Relationship::meter).orElse(Relationship.NEUTRAL);
		if (mine >= Tuning.Family.LOVERS_THRESHOLD && theirs >= Tuning.Family.LOVERS_THRESHOLD) {
			return false;
		}

		soul.setBond(loverId, Bond.NONE, gameTime);
		loverSoul.setBond(villager.getUUID(), Bond.NONE, gameTime);
		return true;
	}

	/** Promotes an established couple to engaged once they are devoted enough. */
	private static void maybeEngage(
		Villager villager, VillagerSoul soul, UUID loverId, ServerLevel level, long gameTime
	) {
		if (!(level.getEntityInAnyDimension(loverId) instanceof Villager lover)) {
			return;
		}
		VillagerSoul loverSoul = lover.getAttached(Attachments.SOUL);
		if (loverSoul == null) {
			return;
		}

		int mine = soul.relationship(loverId).map(Relationship::meter).orElse(0);
		int theirs = loverSoul.relationship(villager.getUUID()).map(Relationship::meter).orElse(0);
		if (mine < Tuning.Family.ENGAGEMENT_THRESHOLD || theirs < Tuning.Family.ENGAGEMENT_THRESHOLD) {
			return;
		}

		soul.setBond(loverId, Bond.ENGAGED, gameTime);
		loverSoul.setBond(villager.getUUID(), Bond.ENGAGED, gameTime);
		soul.remember(gameTime, "agreed to marry " + loverSoul.identity().firstName());
		loverSoul.remember(gameTime, "agreed to marry " + soul.identity().firstName());
		Settlements.recordVillageEvent(soul, level, gameTime,
			soul.identity().firstName() + " got engaged to " + loverSoul.identity().firstName());
		celebrate(level, villager, lover, ParticleTypes.HEART);
	}

	private static void celebrate(
		ServerLevel level, Villager one, Villager two,
		net.minecraft.core.particles.SimpleParticleType particle
	) {
		for (Villager villager : List.of(one, two)) {
			level.sendParticles(particle,
				villager.getX(), villager.getEyeY() + 0.5, villager.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
		}
	}
}
