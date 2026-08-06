package com.talkingvillagers.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.entity.npc.villager.Villager;

/**
 * One player talking to one villager.
 *
 * <p>Sessions are deliberately not persisted. A conversation is a moment, not a state of the
 * world, and a player who logs out mid-sentence should come back to a villager going about
 * their day rather than to a half-finished exchange. What does outlive the session is what the
 * villager took away from it: {@link #playerLines()} is folded into their memory when the
 * conversation ends, so the next one starts from somewhere.
 */
public final class ConversationSession {
	/** Turns of dialogue kept as context. Enough to hold a thread, small enough for a 3B model. */
	private static final int TRANSCRIPT_LIMIT = 6;

	/**
	 * Player lines kept for the villager's long-term memory. Two, because a memory has to fit
	 * alongside eleven others in a prompt — and they are the <em>first</em> two, since that is
	 * where introductions and the reason for the conversation land.
	 */
	private static final int REMEMBERED_PLAYER_LINES = 2;

	private final UUID playerId;
	private final String playerName;
	private final Villager villager;
	private final List<String> transcript = new ArrayList<>();
	private final List<String> playerLines = new ArrayList<>();

	private long lastActivityGameTime;
	private boolean awaitingReply;
	/** How many times the villager has replied, for the length backstop. */
	private int replyCount;

	ConversationSession(UUID playerId, String playerName, Villager villager, long gameTime) {
		this.playerId = playerId;
		this.playerName = playerName;
		this.villager = villager;
		this.lastActivityGameTime = gameTime;
	}

	public UUID playerId() {
		return this.playerId;
	}

	/**
	 * The player's name, captured at the start so the villager can still remember who they spoke
	 * to after that player has logged out.
	 */
	public String playerName() {
		return this.playerName;
	}

	public Villager villager() {
		return this.villager;
	}

	/** Whether the villager this session belongs to is still around to talk. */
	public boolean villagerAvailable() {
		return this.villager.isAlive() && !this.villager.isRemoved();
	}

	public List<String> transcript() {
		return List.copyOf(this.transcript);
	}

	void recordLine(String speaker, String text) {
		this.transcript.add(speaker + ": " + text);
		while (this.transcript.size() > TRANSCRIPT_LIMIT) {
			this.transcript.remove(0);
		}
	}

	/**
	 * What the player actually said, in order, for the villager to remember afterwards. Empty
	 * when the player never replied — a greeting somebody walked away from is not a memory.
	 */
	public List<String> playerLines() {
		return List.copyOf(this.playerLines);
	}

	void recordPlayerLine(String text) {
		if (this.playerLines.size() < REMEMBERED_PLAYER_LINES) {
			this.playerLines.add(text);
		}
	}

	long lastActivityGameTime() {
		return this.lastActivityGameTime;
	}

	void touch(long gameTime) {
		this.lastActivityGameTime = gameTime;
	}

	boolean awaitingReply() {
		return this.awaitingReply;
	}

	void setAwaitingReply(boolean awaiting) {
		this.awaitingReply = awaiting;
	}

	int replyCount() {
		return this.replyCount;
	}

	void incrementReplyCount() {
		this.replyCount++;
	}
}
