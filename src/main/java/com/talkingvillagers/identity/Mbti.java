package com.talkingvillagers.identity;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * One of the sixteen MBTI types, rolled at creation and immutable thereafter.
 *
 * <p>This does two jobs. It goes into the prompt as a compact description of how the
 * villager talks, and its four axes bias behaviour directly: extraverts start conversations
 * more often, and thinkers weigh relationship changes less heavily than feelers do.
 */
public enum Mbti implements StringRepresentable {
	INTJ("intj", "private, strategic, says little but means it"),
	INTP("intp", "curious, abstract, thinks out loud in tangents"),
	ENTJ("entj", "blunt, commanding, impatient with dithering"),
	ENTP("entp", "argumentative for fun, delights in a contrary angle"),
	INFJ("infj", "gentle, watchful, speaks in quiet convictions"),
	INFP("infp", "dreamy, earnest, takes things to heart"),
	ENFJ("enfj", "warm, encouraging, steers people kindly"),
	ENFP("enfp", "enthusiastic, scattered, leaps between topics"),
	ISTJ("istj", "dutiful, literal, cites how things are properly done"),
	ISFJ("isfj", "quietly caring, remembers small kindnesses"),
	ESTJ("estj", "organising, opinionated about order and rules"),
	ESFJ("esfj", "sociable, fussing over everyone's business"),
	ISTP("istp", "terse, practical, would rather show than explain"),
	ISFP("isfp", "soft-spoken, observant, easily embarrassed"),
	ESTP("estp", "brash, jokey, always mid-scheme"),
	ESFP("esfp", "loud, cheerful, tells you far too much");

	public static final Codec<Mbti> CODEC = StringRepresentable.fromEnum(Mbti::values);

	private final String id;
	private final String flavour;

	Mbti(String id, String flavour) {
		this.id = id;
		this.flavour = flavour;
	}

	/** A short description of speaking style, written to be dropped into a prompt. */
	public String flavour() {
		return this.flavour;
	}

	public boolean extraverted() {
		return name().charAt(0) == 'E';
	}

	/** Thinkers let relationship changes land more softly than feelers do. */
	public boolean thinking() {
		return name().charAt(2) == 'T';
	}

	/**
	 * Multiplier applied to relationship deltas. Feelers react about half again as strongly
	 * as thinkers to the same event.
	 */
	public float sentimentWeight() {
		return thinking() ? 0.8f : 1.2f;
	}

	/** Relative likelihood of starting a conversation rather than waiting to be spoken to. */
	public float chattiness() {
		return extraverted() ? 1.5f : 0.6f;
	}

	@Override
	public String getSerializedName() {
		return this.id;
	}
}
