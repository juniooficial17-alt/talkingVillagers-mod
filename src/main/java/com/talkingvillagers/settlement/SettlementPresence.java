package com.talkingvillagers.settlement;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.ui.Notify;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Announces a village to a player who walks into it.
 *
 * <p>Tracks where each player currently is so the banner fires on arrival rather than every pass.
 * Leaving takes a wider radius than arriving, because a player wandering along the edge of a
 * village would otherwise be told they have arrived several times a minute.
 */
public final class SettlementPresence {
	/** The settlement each player is currently considered to be inside. */
	private final Map<UUID, UUID> inside = new HashMap<>();

	/**
	 * Checks every player in this level against every settlement in it, announcing arrivals.
	 *
	 * <p>Driven from the settlement upkeep pass rather than per tick: a village that takes half a
	 * second to announce itself is indistinguishable from one that announces instantly, and this
	 * way the cost is paid once per sweep regardless of how many settlements exist.
	 */
	public void tick(ServerLevel level) {
		SettlementData data = SettlementData.get(level.getServer());
		double radius = Tuning.Settlement.BELL_RADIUS;
		double leaveRadius = radius + Tuning.Ui.VILLAGE_TITLE_HYSTERESIS;

		for (ServerPlayer player : level.players()) {
			UUID was = this.inside.get(player.getUUID());

			// Still in the one they were in? Nothing to say, and no need to look at the others.
			if (was != null) {
				Optional<Settlement> previous = data.byId(was);
				if (previous.isPresent() && within(player, previous.get(), level, leaveRadius)) {
					continue;
				}
				this.inside.remove(player.getUUID());
			}

			Optional<Settlement> arrived = data.all().stream()
				.filter(settlement -> within(player, settlement, level, radius))
				.findFirst();
			if (arrived.isEmpty()) {
				continue;
			}

			this.inside.put(player.getUUID(), arrived.get().id());
			announce(player, arrived.get(), level);
		}
	}

	private static boolean within(
		ServerPlayer player, Settlement settlement, ServerLevel level, double radius
	) {
		if (!settlement.bell().dimension().equals(level.dimension())) {
			return false;
		}
		return player.blockPosition().distSqr(settlement.bell().pos()) <= radius * radius;
	}

	/** The village's name, with how many live there underneath. */
	private static void announce(ServerPlayer player, Settlement settlement, ServerLevel level) {
		int residents = Settlements.residents(settlement, level).size();

		Notify.title(player,
			Component.literal(settlement.name()).withStyle(ChatFormatting.GOLD),
			Component.literal(residents + (residents == 1 ? " resident" : " residents"))
				.withStyle(ChatFormatting.GRAY));
	}

	/** Forgets a player, so rejoining announces the village around them again. */
	public void forget(UUID playerId) {
		this.inside.remove(playerId);
	}

	public void clear() {
		this.inside.clear();
	}
}
