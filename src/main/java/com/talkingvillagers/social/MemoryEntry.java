package com.talkingvillagers.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One thing a villager remembers, in the third person and short enough to sit in a prompt
 * ("Mira gave me bread", "the mayor let Tobias starve").
 *
 * @param gameTime when it happened, used for ordering and for phrasing recency
 * @param text     one clause, no trailing punctuation
 */
public record MemoryEntry(long gameTime, String text) {
	/** Memories longer than this are truncated, since prompt budget is the binding constraint. */
	public static final int MAX_LENGTH = 120;

	public static final Codec<MemoryEntry> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				Codec.LONG.fieldOf("time").forGetter(MemoryEntry::gameTime),
				Codec.STRING.fieldOf("text").forGetter(MemoryEntry::text)
			)
			.apply(instance, MemoryEntry::new)
	);

	public static MemoryEntry of(long gameTime, String text) {
		String trimmed = text.strip();
		if (trimmed.length() > MAX_LENGTH) {
			trimmed = trimmed.substring(0, MAX_LENGTH - 1).strip() + "…";
		}
		return new MemoryEntry(gameTime, trimmed);
	}
}
