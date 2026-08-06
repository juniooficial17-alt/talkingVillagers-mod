package com.talkingvillagers.book;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.SettlementData;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.ui.Notify;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Renaming a village by typing the new name into chat.
 *
 * <p>The book cannot take text input — a written book is read-only, and the writable one cannot be
 * opened from the server. So the book's rename control arms a prompt here, and the player's next
 * line of chat is taken as the answer instead of being broadcast.
 *
 * <p>Swallowing someone's chat is a thing to do carefully, so a prompt is narrow on purpose: it
 * belongs to one player, it expires, it can be abandoned by typing cancel, and it is announced
 * clearly when it opens so nobody is left wondering why their messages have stopped appearing.
 */
public final class RenamePrompts {
	/** What a player types to abandon a prompt rather than answer it. */
	private static final String CANCEL = "cancel";

	private final Map<UUID, Pending> pending = new HashMap<>();

	/**
	 * Opens the prompt, if this player is really the mayor of that village.
	 *
	 * <p>Re-checked here rather than trusted from the book: the book only decides what control to
	 * draw, and its command could be run by anyone who read the text off the page.
	 *
	 * @return true if the player was asked for a name
	 */
	public boolean open(ServerPlayer player, UUID settlementId) {
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}

		Optional<Settlement> found = Settlements.byId(settlementId, level);
		if (found.isEmpty()) {
			Notify.actionBar(player, Component.literal("That village is no longer on the map.")
				.withStyle(ChatFormatting.RED));
			return false;
		}
		Settlement settlement = found.get();
		if (settlement.mayorPlayer().filter(player.getUUID()::equals).isEmpty()) {
			Notify.actionBar(player, Component.literal("Only " + settlement.name()
				+ "'s mayor can rename it.").withStyle(ChatFormatting.RED));
			return false;
		}

		this.pending.put(player.getUUID(), new Pending(settlementId, level.getGameTime()));

		// In chat rather than the action bar, unlike the mod's other status lines: this one asks a
		// question and then eats what the player types, so it needs to stay on screen while they
		// think about it.
		player.sendSystemMessage(Component.literal("Type the new name for " + settlement.name()
				+ " in chat, or ").withStyle(ChatFormatting.GOLD)
			.append(Component.literal(CANCEL).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" to leave it as it is.").withStyle(ChatFormatting.GOLD)));
		return true;
	}

	/**
	 * Takes a player's chat line as the new name, if they were asked for one.
	 *
	 * @return true if the message was consumed and must not reach global chat
	 */
	public boolean handleChat(ServerPlayer player, String message) {
		Pending prompt = this.pending.get(player.getUUID());
		if (prompt == null) {
			return false;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}

		this.pending.remove(player.getUUID());

		String answer = message.strip();
		if (answer.isEmpty() || answer.toLowerCase(Locale.ROOT).equals(CANCEL)) {
			Notify.actionBar(player, Component.literal("Left the name alone")
				.withStyle(ChatFormatting.GRAY));
			return true;
		}

		Optional<Settlement> found = Settlements.byId(prompt.settlementId(), level);
		if (found.isEmpty()) {
			Notify.actionBar(player, Component.literal("That village is no longer on the map.")
				.withStyle(ChatFormatting.RED));
			return true;
		}

		Settlement settlement = found.get();
		// Checked again on the way out as well as on the way in: a prompt can outlive the claim
		// that justified it, if another player took the village over meanwhile.
		if (settlement.mayorPlayer().filter(player.getUUID()::equals).isEmpty()) {
			Notify.actionBar(player, Component.literal("You no longer lead " + settlement.name() + ".")
				.withStyle(ChatFormatting.RED));
			return true;
		}

		String cleaned = Names.sanitise(answer);
		if (cleaned.isEmpty()) {
			Notify.actionBar(player, Component.literal("That name has nothing usable in it")
				.withStyle(ChatFormatting.RED));
			return true;
		}

		String previous = settlement.name();
		settlement.setName(cleaned);
		SettlementData.get(level.getServer()).markChanged();

		Settlements.announce(settlement, level, Component.literal(
			previous + " is now called " + cleaned + ".").withStyle(ChatFormatting.GOLD));
		TalkingVillagers.LOGGER.info("{} renamed {} to {}", player.getName().getString(), previous, cleaned);
		return true;
	}

	/**
	 * Drops prompts nobody answered.
	 *
	 * <p>This is not housekeeping — it is the reason a player's chat cannot stay captured forever
	 * because they clicked something and wandered off.
	 */
	public void expire(long gameTime) {
		this.pending.entrySet().removeIf(entry ->
			gameTime - entry.getValue().askedAtGameTime() > Tuning.Book.RENAME_PROMPT_TIMEOUT_TICKS);
	}

	public void forget(UUID playerId) {
		this.pending.remove(playerId);
	}

	public void clear() {
		this.pending.clear();
	}

	private record Pending(UUID settlementId, long askedAtGameTime) {
	}
}
