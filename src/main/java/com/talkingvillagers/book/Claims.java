package com.talkingvillagers.book;

import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.SettlementData;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.VillagerSoul;
import com.talkingvillagers.ui.CustomClicks;
import com.talkingvillagers.ui.Notify;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * A player taking over a village from its elected mayor.
 *
 * <p>Reached only from the sitting mayor's own book, but validated here from scratch: the book
 * decides what to draw, and a page written when the offer was true can be tapped long after it
 * stopped being. Every one of those checks refuses silently, and a claim that succeeds takes the
 * offer off the book so it cannot be tapped twice.
 */
public final class Claims {
	private Claims() {
	}

	/** Installs this player as mayor, if the village is currently led by one of its own. */
	public static void take(ServerPlayer player, UUID settlementId) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		// Every refusal below is silent. The offer is only ever drawn on a book page that was
		// true when it was written, so reaching one of these means the page has gone stale or the
		// player is not where they need to be — and the button coming off the book on success is
		// the feedback that matters, rather than a line explaining a tap that did nothing.
		Optional<Settlement> found = Settlements.byId(settlementId, level);
		if (found.isEmpty()) {
			return;
		}
		Settlement settlement = found.get();

		if (settlement.mayorPlayer().isPresent()) {
			return;
		}

		// Has to be standing there. A village is claimed in person, not from a book read at home.
		if (Settlements.at(player.blockPosition(), level)
			.filter(here -> here.id().equals(settlement.id()))
			.isEmpty()) {
			return;
		}

		String deposed = Settlements.describeMayor(settlement, level);
		settlement.setPlayerMayor(player.getUUID(), "Mayor");
		SettlementData.get(level.getServer()).markChanged();

		noticeNewMayor(settlement, level, player);

		// The office is taken, so the offer to take it is no longer true.
		Books.removeControl(player, new ClickEvent.Custom(
			CustomClicks.VILLAGE_CLAIM, CustomClicks.settlement(settlement.id())));

		Notify.title(player,
			Component.literal("Congratulations").withStyle(ChatFormatting.GOLD),
			Component.literal("You are now the mayor of " + settlement.name())
				.withStyle(ChatFormatting.GRAY));
		TalkingVillagers.LOGGER.info("{} took {} from {}",
			player.getName().getString(), settlement.name(), deposed);
	}

	/** Has the residents register who is in charge now. */
	private static void noticeNewMayor(Settlement settlement, ServerLevel level, ServerPlayer player) {
		long gameTime = level.getGameTime();
		for (Villager resident : Settlements.residents(settlement, level)) {
			VillagerSoul soul = resident.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}
			soul.adjustRelationship(player.getUUID(), 10, gameTime);
			soul.remember(gameTime,
				player.getName().getString() + " took charge of " + settlement.name());
			Settlements.greetMayor(settlement, resident, level, gameTime);
		}
	}
}
