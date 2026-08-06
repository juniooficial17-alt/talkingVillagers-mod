package com.talkingvillagers.family;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.Gender;
import com.talkingvillagers.identity.IdentityGenerator;
import com.talkingvillagers.identity.VillagerIdentity;
import com.talkingvillagers.identity.VillagerSouls;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Children, born only to married couples.
 *
 * <p>Vanilla breeding is disabled entirely (see {@code VillagerMakeLoveMixin}), so this is the
 * only route to a new villager. A couple rolls its family size once when it marries, then
 * holds off until the village has a free bed for every child they are planning — and once it
 * does, the whole brood arrives at the same time. Housing is the gate on growth, and the
 * player's reason to build more of it.
 */
public final class Births {
	/** How far to look for a free bed, matching vanilla's POI search range. */
	private static final int BED_SEARCH_RADIUS = 48;
	/** How close the parents must be to each other for a child to arrive. */
	private static final double PARENT_RADIUS = 8.0;

	private Births() {
	}

	/**
	 * Considers whether this villager's marriage produces a child right now.
	 *
	 * <p>Only ever evaluated from the mother's side, so a couple cannot be processed twice in
	 * one pass and end up with two children at once.
	 */
	public static void evaluate(Villager mother, ServerLevel level) {
		VillagerSoul motherSoul = mother.getAttached(Attachments.SOUL);
		if (motherSoul == null || mother.isBaby()) {
			return;
		}

		VillagerIdentity motherIdentity = motherSoul.identity();
		// Childbearing follows birth sex, which is what makes the transgender rules in the
		// identity system coherent: who a villager is treated as socially and who can bear a
		// child are separate questions.
		if (motherIdentity.birthSex() != Gender.FEMALE) {
			return;
		}
		int remaining = motherSoul.plannedChildren() - motherSoul.childrenBorn();
		if (remaining <= 0) {
			return;
		}

		long gameTime = level.getGameTime();
		if (gameTime - motherSoul.lastBirthGameTime() < Tuning.Family.BIRTH_INTERVAL_TICKS) {
			return;
		}

		Optional<UUID> spouseId = motherSoul.spouse();
		if (spouseId.isEmpty()) {
			return;
		}
		if (!(level.getEntityInAnyDimension(spouseId.get()) instanceof Villager father)) {
			return;
		}
		VillagerSoul fatherSoul = father.getAttached(Attachments.SOUL);
		if (fatherSoul == null || father.isBaby()) {
			return;
		}
		// Children only from a male-female married couple.
		if (fatherSoul.identity().birthSex() != Gender.MALE) {
			return;
		}
		if (mother.distanceToSqr(father) > PARENT_RADIUS * PARENT_RADIUS) {
			return;
		}

		// The whole brood arrives together: the couple holds off until there is a free bed for
		// every child they are still planning, and then has them all at once. Deferring counts
		// as a failed attempt so they wait out the interval before checking again, rather than
		// retrying every sweep. Not announced directly — the village's housing shortage is a
		// standing complaint on the village's own record, not a one-off event.
		if (freeBeds(level, mother) < remaining) {
			motherSoul.deferBirth(gameTime);
			fatherSoul.deferBirth(gameTime);
			return;
		}

		for (int child = 0; child < remaining; child++) {
			bear(level, mother, motherSoul, father, fatherSoul, gameTime);
		}
	}

	/** How many beds the village has going spare for new arrivals. */
	private static long freeBeds(ServerLevel level, Villager near) {
		PoiManager poi = level.getPoiManager();
		return poi.getCountInRange(
			holder -> holder.is(PoiTypes.HOME),
			near.blockPosition(),
			BED_SEARCH_RADIUS,
			PoiManager.Occupancy.HAS_SPACE);
	}

	/** Spawns the child and wires up the family bonds. */
	private static void bear(
		ServerLevel level, Villager mother, VillagerSoul motherSoul,
		Villager father, VillagerSoul fatherSoul, long gameTime
	) {
		Villager baby = EntityTypes.VILLAGER.create(level, EntitySpawnReason.BREEDING);
		if (baby == null) {
			return;
		}

		baby.setAge(-24000);
		baby.snapTo(mother.getX(), mother.getY(), mother.getZ(), 0.0F, 0.0F);
		baby.finalizeVillagerType(level, baby.blockPosition());

		// Children take the father's surname, per the family naming rule.
		VillagerIdentity identity = IdentityGenerator.generateChild(
			level.getRandom(),
			TalkingVillagers.config(),
			fatherSoul.identity().surname(),
			siblingFirstNames(motherSoul, level));
		VillagerSoul babySoul = new VillagerSoul(identity);

		// Attached before the entity joins the world, so the retrofit hook that fires on spawn
		// finds an identity already there instead of generating a stranger.
		baby.setAttached(Attachments.SOUL, babySoul);
		if (!level.addFreshEntity(baby)) {
			return;
		}
		VillagerSouls.applyNameTag(baby, babySoul);

		linkFamily(baby, babySoul, mother, motherSoul, father, fatherSoul, level, gameTime);

		motherSoul.recordChildBorn(gameTime);
		fatherSoul.recordChildBorn(gameTime);
		motherSoul.remember(gameTime, "gave birth to " + identity.firstName());
		fatherSoul.remember(gameTime, identity.firstName() + " was born");
		Settlements.recordVillageEvent(motherSoul, level, gameTime,
			identity.firstName() + " was born to " + motherSoul.identity().firstName()
				+ " and " + fatherSoul.identity().firstName());

		level.sendParticles(ParticleTypes.HEART,
			mother.getX(), mother.getEyeY() + 0.5, mother.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
	}

	/** Records parent, child and sibling bonds in every direction. */
	private static void linkFamily(
		Villager baby, VillagerSoul babySoul,
		Villager mother, VillagerSoul motherSoul,
		Villager father, VillagerSoul fatherSoul,
		ServerLevel level, long gameTime
	) {
		babySoul.setBond(mother.getUUID(), Bond.PARENT, gameTime);
		babySoul.setBond(father.getUUID(), Bond.PARENT, gameTime);
		motherSoul.setBond(baby.getUUID(), Bond.CHILD, gameTime);
		fatherSoul.setBond(baby.getUUID(), Bond.CHILD, gameTime);

		// Family starts out fond of each other rather than at the neutral default.
		babySoul.setRelationship(babySoul.relationshipOrFresh(mother.getUUID(), gameTime).withMeter(85));
		babySoul.setRelationship(babySoul.relationshipOrFresh(father.getUUID(), gameTime).withMeter(85));
		motherSoul.setRelationship(motherSoul.relationshipOrFresh(baby.getUUID(), gameTime).withMeter(90));
		fatherSoul.setRelationship(fatherSoul.relationshipOrFresh(baby.getUUID(), gameTime).withMeter(90));

		for (UUID siblingId : motherSoul.withBond(Bond.CHILD)) {
			if (siblingId.equals(baby.getUUID())) {
				continue;
			}
			if (!(level.getEntityInAnyDimension(siblingId) instanceof Villager sibling)) {
				continue;
			}
			VillagerSoul siblingSoul = sibling.getAttached(Attachments.SOUL);
			if (siblingSoul == null) {
				continue;
			}
			babySoul.setBond(siblingId, Bond.SIBLING, gameTime);
			siblingSoul.setBond(baby.getUUID(), Bond.SIBLING, gameTime);
		}
	}

	/** Names already used by this child's siblings, so a family avoids duplicates. */
	private static Set<String> siblingFirstNames(VillagerSoul motherSoul, ServerLevel level) {
		Set<String> names = new HashSet<>();
		for (UUID childId : motherSoul.withBond(Bond.CHILD)) {
			if (level.getEntityInAnyDimension(childId) instanceof Villager sibling) {
				VillagerSoul soul = sibling.getAttached(Attachments.SOUL);
				if (soul != null) {
					names.add(soul.identity().firstName());
				}
			}
		}
		return names;
	}

}
