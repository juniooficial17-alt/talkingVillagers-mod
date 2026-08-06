package com.talkingvillagers.ui;

import com.talkingvillagers.Tuning;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * The mod's whole vocabulary for talking to a player.
 *
 * <p>Three registers, kept apart on purpose. The action bar is for anything transient — what you
 * are doing right now, and why something did not happen; it costs no chat history and vanishes
 * on its own. A title is for arriving somewhere. Chat is reserved for a record worth scrolling
 * back to: dialogue, and the things that happen to a village.
 *
 * <p>All three are vanilla packets a normal client already understands, which is what keeps the
 * mod server-side only.
 */
public final class Notify {
	private Notify() {
	}

	/**
	 * Shows a line above the hotbar.
	 *
	 * <p>Only one can be visible at a time and a second replaces the first instantly, so a caller
	 * with two things to say has to say them in one line rather than in two calls.
	 */
	public static void actionBar(ServerPlayer player, Component message) {
		// The boolean is the "overlay" flag on the system-chat packet: true routes it to the
		// action bar instead of the chat box.
		player.sendSystemMessage(message, true);
	}

	/** Shows a title with a subtitle under it, using the mod's standard timing. */
	public static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(
			Tuning.Ui.TITLE_FADE_IN_TICKS,
			Tuning.Ui.TITLE_STAY_TICKS,
			Tuning.Ui.TITLE_FADE_OUT_TICKS));
		// Subtitle first: the client shows both together, and sending the title last means a
		// player who arrives mid-packet never sees a subtitle hanging on its own.
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
