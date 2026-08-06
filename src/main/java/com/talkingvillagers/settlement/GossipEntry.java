package com.talkingvillagers.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One conversation the village's own record took note of: who the two participants were, and a
 * short line about what it was about. Villager-to-villager small talk and player-to-villager
 * conversations both feed this the same way, so the village's gossip page reads as one log
 * rather than two.
 *
 * @param gameTime      when the conversation happened
 * @param key           canonical identity of the underlying fact — two entries with the same key
 *                      are the same piece of news, even when the wording differs (an engagement
 *                      told from either side, the same pair's small talk)
 * @param participantOne one side of the conversation
 * @param participantTwo the other side
 * @param summary       one short sentence describing it
 */
public record GossipEntry(
	long gameTime, String key, String participantOne, String participantTwo, String summary
) {
	public static final Codec<GossipEntry> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				Codec.LONG.fieldOf("time").forGetter(GossipEntry::gameTime),
				Codec.STRING.optionalFieldOf("key", "").forGetter(GossipEntry::key),
				Codec.STRING.fieldOf("one").forGetter(GossipEntry::participantOne),
				Codec.STRING.fieldOf("two").forGetter(GossipEntry::participantTwo),
				Codec.STRING.fieldOf("summary").forGetter(GossipEntry::summary)
			)
			.apply(instance, GossipEntry::new)
	);

	/** Entries saved before keys existed fall back to their summary as their identity. */
	public String dedupKey() {
		return this.key.isEmpty() ? this.summary : this.key;
	}
}
