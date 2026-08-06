package com.talkingvillagers.social;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;

/**
 * How one villager feels about one other entity — another villager or a player.
 *
 * <p>The meter runs 0-100 and starts at {@link #NEUTRAL} on first encounter. Zero is a
 * permanent lock: a villager who has been driven all the way down to nothing never recovers,
 * and {@link #adjust} will refuse to raise them. That is deliberate, and it is the reason
 * killing someone's family in front of them is unforgivable rather than merely expensive.
 *
 * @param target          the villager or player this is about
 * @param meter           0-100 regard; 0 is a permanent lock
 * @param bond            explicit family or romantic tie, if any
 * @param interactions    how many times they have interacted, used to gate "we barely know
 *                        each other" versus a considered opinion
 * @param lastInteraction game time of the last interaction, for decay and forgetting
 * @param memorialName    the target's name, snapshotted at the moment they became unreachable
 *                        (currently only set on {@link Bond#WIDOWED}); empty otherwise. A dead
 *                        entity cannot be looked up by UUID any more, so this is the only place
 *                        left holding their name.
 */
public record Relationship(
	UUID target,
	int meter,
	Bond bond,
	int interactions,
	long lastInteraction,
	String memorialName
) {
	public static final int MIN = 0;
	public static final int MAX = 100;
	public static final int NEUTRAL = 50;

	/** Meter at or below this reads as hostility. */
	public static final int ENEMY_THRESHOLD = 20;
	public static final int FRIEND_THRESHOLD = 70;
	public static final int BEST_FRIEND_THRESHOLD = 90;

	public static final Codec<Relationship> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("target").forGetter(Relationship::target),
				ExtraCodecs.NON_NEGATIVE_INT.fieldOf("meter").forGetter(Relationship::meter),
				Bond.CODEC.optionalFieldOf("bond", Bond.NONE).forGetter(Relationship::bond),
				ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("interactions", 0).forGetter(Relationship::interactions),
				Codec.LONG.optionalFieldOf("last_interaction", 0L).forGetter(Relationship::lastInteraction),
				Codec.STRING.optionalFieldOf("memorial_name", "").forGetter(Relationship::memorialName)
			)
			.apply(instance, Relationship::new)
	);

	public static Relationship fresh(UUID target, long gameTime) {
		return new Relationship(target, NEUTRAL, Bond.NONE, 0, gameTime, "");
	}

	/** True once the meter has hit zero, after which no event can raise it again. */
	public boolean lockedInHatred() {
		return this.meter <= MIN;
	}

	/**
	 * Moves the meter by {@code delta}, clamped to 0-100, and counts an interaction.
	 *
	 * <p>A locked relationship absorbs the change and stays at zero. Note that reaching zero
	 * is one-way, so callers applying a large negative delta are making a permanent decision.
	 */
	public Relationship adjust(int delta, long gameTime) {
		if (lockedInHatred()) {
			return new Relationship(this.target, MIN, this.bond, this.interactions + 1, gameTime, this.memorialName);
		}
		int updated = Math.max(MIN, Math.min(MAX, this.meter + delta));
		return new Relationship(this.target, updated, this.bond, this.interactions + 1, gameTime, this.memorialName);
	}

	public Relationship withBond(Bond newBond) {
		return new Relationship(this.target, this.meter, newBond, this.interactions, this.lastInteraction, this.memorialName);
	}

	public Relationship withMeter(int newMeter) {
		int clamped = Math.max(MIN, Math.min(MAX, newMeter));
		return new Relationship(this.target, clamped, this.bond, this.interactions, this.lastInteraction, this.memorialName);
	}

	/** Downgrades a spouse bond to a memorial once they are gone, snapshotting their name. */
	public Relationship widowed(String deceasedName) {
		return new Relationship(
			this.target, this.meter, Bond.WIDOWED, this.interactions, this.lastInteraction, deceasedName);
	}

	/**
	 * The social label, derived rather than stored. An explicit bond always wins: a villager
	 * who is furious with their brother is still their brother.
	 */
	public Label label() {
		if (this.bond != Bond.NONE) {
			return switch (this.bond) {
				case SPOUSE -> Label.SPOUSE;
				case ENGAGED -> Label.ENGAGED;
				case LOVER -> Label.LOVER;
				case PARENT, CHILD, SIBLING -> Label.FAMILY;
				case WIDOWED -> Label.EX_SPOUSE;
				case NONE -> Label.NEUTRAL;
			};
		}
		if (this.meter <= MIN) {
			return Label.SWORN_ENEMY;
		}
		if (this.meter <= ENEMY_THRESHOLD) {
			return Label.ENEMY;
		}
		if (this.meter >= BEST_FRIEND_THRESHOLD) {
			return Label.BEST_FRIEND;
		}
		if (this.meter >= FRIEND_THRESHOLD) {
			return Label.FRIEND;
		}
		return Label.NEUTRAL;
	}

	/** Derived social standing, used for prompts, gossip topics and reports. */
	public enum Label {
		SWORN_ENEMY("sworn enemy"),
		ENEMY("enemy"),
		NEUTRAL("acquaintance"),
		FRIEND("friend"),
		BEST_FRIEND("best friend"),
		FAMILY("family"),
		LOVER("sweetheart"),
		ENGAGED("betrothed"),
		SPOUSE("spouse"),
		EX_SPOUSE("ex-spouse");

		private final String description;

		Label(String description) {
			this.description = description;
		}

		public String description() {
			return this.description;
		}
	}
}
