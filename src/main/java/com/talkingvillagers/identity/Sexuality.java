package com.talkingvillagers.identity;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * Who a villager can fall in love with. Rolled when a villager reaches adulthood and hidden
 * on children.
 *
 * <p>{@link #ASEXUAL} villagers do not pursue romance at all and never marry; every other
 * orientation can. Whether a couple can have children is a separate question decided by
 * birth sex, not by this.
 */
public enum Sexuality implements StringRepresentable {
	HETEROSEXUAL("heterosexual"),
	BISEXUAL("bisexual"),
	HOMOSEXUAL("homosexual"),
	PANSEXUAL("pansexual"),
	ASEXUAL("asexual");

	public static final Codec<Sexuality> CODEC = StringRepresentable.fromEnum(Sexuality::values);

	private final String id;

	Sexuality(String id) {
		this.id = id;
	}

	/** Whether this villager pursues romance at all. */
	public boolean romantic() {
		return this != ASEXUAL;
	}

	/**
	 * Whether a villager of this orientation could be attracted to {@code target}, judged
	 * against the gender each villager presents as rather than their birth sex.
	 */
	public boolean attractedTo(Gender own, Gender target) {
		return switch (this) {
			case HETEROSEXUAL -> own != target;
			case HOMOSEXUAL -> own == target;
			case BISEXUAL, PANSEXUAL -> true;
			case ASEXUAL -> false;
		};
	}

	@Override
	public String getSerializedName() {
		return this.id;
	}
}
