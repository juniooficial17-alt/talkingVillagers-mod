package com.talkingvillagers.identity;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;

import net.minecraft.util.RandomSource;

/**
 * Rolls new villager identities from the configured name pools and weights.
 *
 * <p>Children get a name, a birth sex and a personality. Orientation and the transgender
 * roll are deferred to {@link #applyAdulthood}, so nothing about a child's future is decided
 * — or visible in prompts — before they grow up.
 */
public final class IdentityGenerator {
	/** Attempts at a first name unused in the settlement before accepting a duplicate. */
	private static final int UNIQUENESS_ATTEMPTS = 12;

	private IdentityGenerator() {
	}

	/**
	 * Creates an identity for a villager the mod has just taken over — a natural spawn, a
	 * spawn egg, a cured zombie villager, or an existing villager in a world that predates
	 * the mod.
	 *
	 * @param takenFirstNames first names already in use nearby, avoided where possible
	 * @param adult           whether to roll orientation immediately
	 */
	public static VillagerIdentity generate(
		RandomSource random, TalkingVillagersConfig config, Set<String> takenFirstNames, boolean adult
	) {
		Gender birthSex = random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
		String firstName = pickFirstName(random, config, birthSex, takenFirstNames);
		String surname = pick(random, config.names.surnames);
		Mbti mbti = Mbti.values()[random.nextInt(Mbti.values().length)];

		VillagerIdentity child = new VillagerIdentity(
			firstName, surname, birthSex, birthSex, Optional.empty(), mbti, false);
		return adult ? applyAdulthood(child, random, config, takenFirstNames) : child;
	}

	/**
	 * Creates an identity for a newborn, inheriting the family surname.
	 */
	public static VillagerIdentity generateChild(
		RandomSource random, TalkingVillagersConfig config, String familySurname, Set<String> takenFirstNames
	) {
		Gender birthSex = random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
		String firstName = pickFirstName(random, config, birthSex, takenFirstNames);
		Mbti mbti = Mbti.values()[random.nextInt(Mbti.values().length)];
		return new VillagerIdentity(
			firstName, familySurname, birthSex, birthSex, Optional.empty(), mbti, false);
	}

	/**
	 * Performs the rolls that happen when a villager grows up: orientation, and a small
	 * chance of being transgender, in which case they take a new first name from the other
	 * pool and are referred to by their gender identity from then on.
	 */
	public static VillagerIdentity applyAdulthood(
		VillagerIdentity identity, RandomSource random, TalkingVillagersConfig config, Set<String> takenFirstNames
	) {
		if (identity.adult()) {
			return identity;
		}

		Sexuality sexuality = rollSexuality(random, config);
		Gender genderIdentity = identity.birthSex();
		String firstName = identity.firstName();

		if (random.nextDouble() < Tuning.Identity.TRANSGENDER_CHANCE) {
			genderIdentity = identity.birthSex().opposite();
			firstName = pickFirstName(random, config, genderIdentity, takenFirstNames);
		}

		return identity.asAdult(firstName, genderIdentity, sexuality);
	}

	/**
	 * Rolls an orientation.
	 *
	 * <p>With {@code sexuality.enable_sexuality} switched off every villager comes out
	 * heterosexual, which keeps courtship working — it simply only ever pairs men with women.
	 */
	public static Sexuality rollSexuality(RandomSource random, TalkingVillagersConfig config) {
		if (!config.sexuality.enabled) {
			return Sexuality.HETEROSEXUAL;
		}

		double heterosexual = Tuning.Identity.HETEROSEXUAL_WEIGHT;
		double bisexual = Tuning.Identity.BISEXUAL_WEIGHT;
		double homosexual = Tuning.Identity.HOMOSEXUAL_WEIGHT;
		double pansexual = Tuning.Identity.PANSEXUAL_WEIGHT;
		double asexual = Tuning.Identity.ASEXUAL_WEIGHT;
		double total = heterosexual + bisexual + homosexual + pansexual + asexual;

		if (total <= 0.0) {
			return Sexuality.HETEROSEXUAL;
		}

		double roll = random.nextDouble() * total;
		if ((roll -= heterosexual) < 0.0) {
			return Sexuality.HETEROSEXUAL;
		}
		if ((roll -= bisexual) < 0.0) {
			return Sexuality.BISEXUAL;
		}
		if ((roll -= homosexual) < 0.0) {
			return Sexuality.HOMOSEXUAL;
		}
		if ((roll -= pansexual) < 0.0) {
			return Sexuality.PANSEXUAL;
		}
		return Sexuality.ASEXUAL;
	}

	/** Weighted pick over the children-per-marriage weights. */
	public static int rollChildCount(RandomSource random) {
		List<Integer> weights = Tuning.Family.CHILDREN_WEIGHTS;
		int total = 0;
		for (int weight : weights) {
			total += Math.max(0, weight);
		}
		if (total <= 0) {
			return 0;
		}

		int roll = random.nextInt(total);
		for (int count = 0; count < weights.size(); count++) {
			roll -= Math.max(0, weights.get(count));
			if (roll < 0) {
				return count;
			}
		}
		return 0;
	}

	private static String pickFirstName(
		RandomSource random, TalkingVillagersConfig config, Gender gender, Set<String> taken
	) {
		List<String> pool = gender == Gender.MALE
			? config.names.firstNamesMale
			: config.names.firstNamesFemale;

		for (int attempt = 0; attempt < UNIQUENESS_ATTEMPTS; attempt++) {
			String candidate = pick(random, pool);
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
		// The pool is smaller than the settlement, or unlucky. A duplicate first name is
		// harmless — villagers are identified by surname and UUID, not by first name.
		return pick(random, pool);
	}

	private static String pick(RandomSource random, List<String> pool) {
		return pool.get(random.nextInt(pool.size()));
	}
}
