package com.talkingvillagers.identity;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The immutable core of who a villager is.
 *
 * <p>Children carry a name, a birth sex and an MBTI type; {@link #sexuality()} and the
 * transgender roll only happen at adulthood, so both are optional here rather than being
 * given placeholder values that could leak into prompts.
 *
 * @param firstName        current first name; a transgender villager's changes at adulthood
 * @param surname          inherited from the father, or rolled for a founder
 * @param birthSex         fixed at creation, drives marriage and childbearing mechanics
 * @param genderIdentity   how others refer to them; differs from birth sex if transgender
 * @param sexuality        empty until adulthood
 * @param mbti             fixed at creation
 * @param adult            whether the adulthood rolls have happened
 */
public record VillagerIdentity(
	String firstName,
	String surname,
	Gender birthSex,
	Gender genderIdentity,
	Optional<Sexuality> sexuality,
	Mbti mbti,
	boolean adult
) {
	public static final Codec<VillagerIdentity> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				Codec.STRING.fieldOf("first_name").forGetter(VillagerIdentity::firstName),
				Codec.STRING.fieldOf("surname").forGetter(VillagerIdentity::surname),
				Gender.CODEC.fieldOf("birth_sex").forGetter(VillagerIdentity::birthSex),
				Gender.CODEC.fieldOf("gender_identity").forGetter(VillagerIdentity::genderIdentity),
				Sexuality.CODEC.optionalFieldOf("sexuality").forGetter(VillagerIdentity::sexuality),
				Mbti.CODEC.fieldOf("mbti").forGetter(VillagerIdentity::mbti),
				Codec.BOOL.fieldOf("adult").forGetter(VillagerIdentity::adult)
			)
			.apply(instance, VillagerIdentity::new)
	);

	public String fullName() {
		return this.firstName + " " + this.surname;
	}

	public boolean transgender() {
		return this.birthSex != this.genderIdentity;
	}

	/** Whether this villager pursues romance. Children never do. */
	public boolean romantic() {
		return this.adult && this.sexuality.map(Sexuality::romantic).orElse(false);
	}

	/**
	 * Whether this villager could be attracted to a villager presenting as {@code target}.
	 * Judged on gender identity, so a transgender villager is courted as the gender they
	 * present as even though childbearing follows birth sex.
	 */
	public boolean attractedTo(Gender target) {
		return this.sexuality.map(s -> s.attractedTo(this.genderIdentity, target)).orElse(false);
	}

	/** Applies the adulthood rolls: an orientation, and whichever name/identity they settled on. */
	public VillagerIdentity asAdult(String newFirstName, Gender newIdentity, Sexuality rolledSexuality) {
		return new VillagerIdentity(
			newFirstName, this.surname, this.birthSex, newIdentity, Optional.of(rolledSexuality), this.mbti, true);
	}
}
