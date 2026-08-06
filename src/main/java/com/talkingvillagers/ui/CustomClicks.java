package com.talkingvillagers.ui;

import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.book.Claims;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The controls in the mod's books, and what happens when one is tapped.
 *
 * <p>A book page is an ordinary chat component, so its clickable lines carry a click event — and
 * {@code custom} is the one that talks to the server without going through a command. The client
 * answers a tap with a {@code ServerboundCustomClickActionPacket} carrying only the action id and
 * payload the server itself put on the page, which is why the mod needs no {@code /talkingvillagers}
 * command tree to make its books work.
 *
 * <p>Two consequences worth keeping in mind. A player cannot type these: there is no command to
 * discover in tab-completion and nothing to forge by hand. But the payload still arrives from a
 * client and is still only a claim about what was clicked, so every action re-checks the player's
 * standing from scratch rather than trusting that the book was the one that sent it.
 *
 * @see com.talkingvillagers.mixin.CustomClickActionMixin the hook that delivers these
 */
public final class CustomClicks {
	/** Take charge of a village, from the villager's book. */
	public static final Identifier VILLAGE_CLAIM = TalkingVillagers.id("village_claim");

	/** Rename a village you already lead, from the village book. */
	public static final Identifier VILLAGE_RENAME = TalkingVillagers.id("village_rename");

	private CustomClicks() {
	}

	/**
	 * The payload for an action about one settlement.
	 *
	 * <p>A string rather than the settlement's raw UUID: {@code UUIDUtil} encodes a UUID as an int
	 * array, which is a needless shape to put through an untrusted payload when the value is only
	 * ever parsed straight back into a UUID.
	 */
	public static Optional<Tag> settlement(UUID settlementId) {
		return Optional.of(StringTag.valueOf(settlementId.toString()));
	}

	/**
	 * Runs the action a player tapped in a book.
	 *
	 * @return true when the action belonged to this mod, whether or not it could be carried out —
	 *         the caller uses this only to decide whether anyone else should see the packet
	 */
	public static boolean dispatch(ServerPlayer player, Identifier action, Optional<Tag> payload) {
		if (!action.getNamespace().equals(TalkingVillagers.MOD_ID)) {
			return false;
		}

		// Both of the mod's actions are about one settlement, so an unreadable payload is simply a
		// tap that does nothing. Silent on purpose: the only way to get here with a bad payload is
		// a hand-built packet, and there is no player mistake worth explaining.
		Optional<UUID> settlementId = settlementFrom(payload);
		if (settlementId.isEmpty()) {
			return true;
		}

		if (action.equals(VILLAGE_CLAIM)) {
			Claims.take(player, settlementId.get());
			return true;
		}
		if (action.equals(VILLAGE_RENAME)) {
			TalkingVillagers.renamePrompts().open(player, settlementId.get());
			return true;
		}
		return false;
	}

	private static Optional<UUID> settlementFrom(Optional<Tag> payload) {
		if (!(payload.orElse(null) instanceof StringTag text)) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(text.value()));
		} catch (IllegalArgumentException notAUuid) {
			return Optional.empty();
		}
	}
}
