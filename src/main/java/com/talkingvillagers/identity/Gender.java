package com.talkingvillagers.identity;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * A villager's gender. Birth sex is fixed at creation and drives the marriage and
 * childbearing mechanics; {@link VillagerIdentity#genderIdentity()} is what other villagers
 * and the model refer to them by, and the two differ for transgender villagers.
 */
public enum Gender implements StringRepresentable {
	MALE("male"),
	FEMALE("female");

	public static final Codec<Gender> CODEC = StringRepresentable.fromEnum(Gender::values);

	private final String id;

	Gender(String id) {
		this.id = id;
	}

	public Gender opposite() {
		return this == MALE ? FEMALE : MALE;
	}

	@Override
	public String getSerializedName() {
		return this.id;
	}
}
