package com.talkingvillagers.llm;

/**
 * Why a request is being made, which decides what gets dropped when the model cannot keep up.
 *
 * <p>Declaration order is priority order: a player waiting for an answer always beats
 * background flavour text. A busy village generates far more candidate requests than a small
 * local model can serve, so this is the main thing standing between "villagers feel alive"
 * and "the queue is permanently backed up".
 */
public enum LlmPriority {
	/** A player is standing there waiting for a reply. Never dropped while healthy. */
	PLAYER_CONVERSATION,
	/** A significant villager event: a proposal, a feud, a death, an election. */
	VILLAGER_EVENT,
	/**
	 * Ambient colour nobody is waiting on — inventing the day's gossip topics, or upgrading a
	 * conversation memory that already has a templated fallback.
	 */
	BACKGROUND
}
