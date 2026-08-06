package com.talkingvillagers.family;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Gives a freshly-discovered settlement a lived-in history: some residents are already couples,
 * some couples already have grown children living in the same village, instead of every
 * villager starting out with no family beyond whatever is born in-game from here on.
 *
 * <p>Runs exactly once per settlement (see {@code Settlement.familySeeded()}), the moment it is
 * first seen with more than one resident. Whatever it does not manage to pair off — an odd one
 * out, an orientation with nobody nearby to match it — is simply left for ordinary courtship to
 * find over time, the same as any village the mod did not seed at all.
 */
public final class FamilySeeding {
	/** Of the couples formed, roughly this share are already married outright. */
	private static final double SPOUSE_CHANCE = 0.6;
	/** Of the rest, roughly this share are engaged; whatever remains are just dating. */
	private static final double ENGAGED_CHANCE = 0.25;

	/** Chance a formed couple already has at least one grown child living in the village. */
	private static final double CHILD_CHANCE_PER_COUPLE = 0.5;
	/** Most children a single seeded couple is given. */
	private static final int MAX_CHILDREN_PER_COUPLE = 2;

	/** Regard between a seeded couple — established, not just freshly infatuated. */
	private static final int COUPLE_METER_MIN = 80;
	private static final int COUPLE_METER_SPREAD = 21;
	/** Regard between a seeded parent and child. */
	private static final int FAMILY_METER = 85;

	private FamilySeeding() {
	}

	/** Seeds couples, and children for some of them, among this settlement's current residents. */
	public static void seed(List<Villager> residents, ServerLevel level) {
		long gameTime = level.getGameTime();
		RandomSource random = level.getRandom();

		List<Villager> singles = new ArrayList<>();
		for (Villager villager : residents) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null && soul.identity().romantic()) {
				singles.add(villager);
			}
		}
		shuffle(singles, random);

		List<Villager> paired = new ArrayList<>();
		List<Villager[]> couples = new ArrayList<>();
		for (int i = 0; i < singles.size(); i++) {
			Villager one = singles.get(i);
			if (paired.contains(one)) {
				continue;
			}
			VillagerSoul oneSoul = one.getAttached(Attachments.SOUL);

			for (int j = i + 1; j < singles.size(); j++) {
				Villager two = singles.get(j);
				if (paired.contains(two)) {
					continue;
				}
				VillagerSoul twoSoul = two.getAttached(Attachments.SOUL);
				if (!mutuallyAttracted(oneSoul, twoSoul)) {
					continue;
				}

				Bond bond = rollCoupleBond(random);
				int meter = COUPLE_METER_MIN + random.nextInt(COUPLE_METER_SPREAD);
				pairBond(oneSoul, two.getUUID(), twoSoul, one.getUUID(), bond, meter, gameTime);
				paired.add(one);
				paired.add(two);
				couples.add(new Villager[] {one, two});
				break;
			}
		}

		giveChildren(residents, couples, paired, random, gameTime);
	}

	/** Hands some of the newly-formed couples one or two children, drawn from whoever is left. */
	private static void giveChildren(
		List<Villager> residents, List<Villager[]> couples, List<Villager> paired,
		RandomSource random, long gameTime
	) {
		List<Villager> availableForChildren = new ArrayList<>(residents);
		availableForChildren.removeAll(paired);
		shuffle(availableForChildren, random);

		int nextChild = 0;
		for (Villager[] couple : couples) {
			if (random.nextDouble() >= CHILD_CHANCE_PER_COUPLE) {
				continue;
			}
			int childCount = 1 + random.nextInt(MAX_CHILDREN_PER_COUPLE);
			for (int c = 0; c < childCount && nextChild < availableForChildren.size(); c++) {
				giveChild(couple[0], couple[1], availableForChildren.get(nextChild++), gameTime);
			}
		}
	}

	private static boolean mutuallyAttracted(VillagerSoul one, VillagerSoul two) {
		return one.identity().attractedTo(two.identity().genderIdentity())
			&& two.identity().attractedTo(one.identity().genderIdentity());
	}

	private static Bond rollCoupleBond(RandomSource random) {
		double roll = random.nextDouble();
		if (roll < SPOUSE_CHANCE) {
			return Bond.SPOUSE;
		}
		if (roll < SPOUSE_CHANCE + ENGAGED_CHANCE) {
			return Bond.ENGAGED;
		}
		return Bond.LOVER;
	}

	private static void pairBond(
		VillagerSoul oneSoul, UUID twoId, VillagerSoul twoSoul, UUID oneId,
		Bond bond, int meter, long gameTime
	) {
		oneSoul.setRelationship(Relationship.fresh(twoId, gameTime).withMeter(meter).withBond(bond));
		twoSoul.setRelationship(Relationship.fresh(oneId, gameTime).withMeter(meter).withBond(bond));
	}

	private static void giveChild(Villager parentA, Villager parentB, Villager child, long gameTime) {
		VillagerSoul parentASoul = parentA.getAttached(Attachments.SOUL);
		VillagerSoul parentBSoul = parentB.getAttached(Attachments.SOUL);
		VillagerSoul childSoul = child.getAttached(Attachments.SOUL);
		if (parentASoul == null || parentBSoul == null || childSoul == null) {
			return;
		}

		parentASoul.setRelationship(
			Relationship.fresh(child.getUUID(), gameTime).withMeter(FAMILY_METER).withBond(Bond.CHILD));
		childSoul.setRelationship(
			Relationship.fresh(parentA.getUUID(), gameTime).withMeter(FAMILY_METER).withBond(Bond.PARENT));
		parentBSoul.setRelationship(
			Relationship.fresh(child.getUUID(), gameTime).withMeter(FAMILY_METER).withBond(Bond.CHILD));
		childSoul.setRelationship(
			Relationship.fresh(parentB.getUUID(), gameTime).withMeter(FAMILY_METER).withBond(Bond.PARENT));
	}

	/** Fisher-Yates, since {@code RandomSource} is not a {@code java.util.Random}. */
	private static void shuffle(List<Villager> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			Villager swap = list.get(i);
			list.set(i, list.get(j));
			list.set(j, swap);
		}
	}
}
