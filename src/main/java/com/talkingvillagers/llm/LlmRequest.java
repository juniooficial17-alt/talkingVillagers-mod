package com.talkingvillagers.llm;

import java.util.function.Consumer;

/**
 * One queued generation request.
 *
 * <p>{@code onReply} and {@code onFailure} are always invoked on the server thread, never on
 * an HTTP thread. Because an unbounded amount of game time can pass between submitting and
 * being answered, callbacks must re-check that whatever they were about to act on — the
 * villager, the player, the conversation — still exists and is still relevant.
 *
 * @param priority  decides ordering and what gets dropped under load
 * @param system    persona and rules; the stable part of the prompt
 * @param user      the situation being reacted to
 * @param onReply   handed the model's cleaned-up reply
 * @param onFailure run instead if the request was dropped, timed out or errored; villagers
 *                  fall silent rather than saying something canned
 */
public record LlmRequest(
	LlmPriority priority,
	String system,
	String user,
	Consumer<String> onReply,
	Runnable onFailure
) {
	public static LlmRequest of(LlmPriority priority, String system, String user, Consumer<String> onReply) {
		return new LlmRequest(priority, system, user, onReply, () -> {
		});
	}
}
