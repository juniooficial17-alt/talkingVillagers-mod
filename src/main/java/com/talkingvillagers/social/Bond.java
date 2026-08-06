package com.talkingvillagers.social;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * An explicit tie between two villagers that cannot be inferred from the relationship meter.
 *
 * <p>Friendship and enmity are derived from the meter instead (see
 * {@link Relationship#label()}), because those move continuously with events. Family and
 * romantic standing are discrete states the mod sets deliberately, so they live here.
 */
public enum Bond implements StringRepresentable {
	NONE("none"),
	/** The target is this villager's parent. */
	PARENT("parent"),
	/** The target is this villager's child. */
	CHILD("child"),
	SIBLING("sibling"),
	LOVER("lover"),
	ENGAGED("engaged"),
	SPOUSE("spouse"),
	/** A spouse who has died. Not romantic any more — it is a memorial, not an active tie. */
	WIDOWED("widowed");

	public static final Codec<Bond> CODEC = StringRepresentable.fromEnum(Bond::values);

	private final String id;

	Bond(String id) {
		this.id = id;
	}

	/** Blood family, which no amount of falling out can undo. */
	public boolean family() {
		return this == PARENT || this == CHILD || this == SIBLING;
	}

	public boolean romantic() {
		return this == LOVER || this == ENGAGED || this == SPOUSE;
	}

	@Override
	public String getSerializedName() {
		return this.id;
	}
}
