package com.talkingvillagers.book;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.Gender;
import com.talkingvillagers.settlement.Complaints;
import com.talkingvillagers.settlement.GossipEntry;
import com.talkingvillagers.settlement.Needs;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.MemoryEntry;
import com.talkingvillagers.social.VillagerSoul;
import com.talkingvillagers.ui.CustomClicks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * The village's own record, written by sneak-clicking its bell with a book.
 *
 * <p>The village's full report — population, employment, food and mood — as an item a player can
 * carry, hand to someone else, or leave in a chest, and where the village's mayor finds the
 * control for renaming it. The mod has no commands, so this book is the only place it appears.
 */
public final class VillageBook {
	/** Lines listed per page. Newest first, across every resident. */
	private static final int LIST_LIMIT = 10;
	/** How long a concluded raid stays worth mentioning as village news. */
	private static final long RAID_NEWS_TICKS = 3L * 24000L;

	private VillageBook() {
	}

	/** Writes the record of this village into the book the player is holding. */
	public static void give(
		ServerPlayer player, Settlement settlement, ServerLevel level, InteractionHand hand
	) {
		List<Villager> residents = Settlements.residents(settlement, level);

		List<Component> pages = new ArrayList<>();
		pages.add(summaryPage(player, settlement, level, residents));
		pages.add(statsPage(settlement, level, residents));
		pages.addAll(Books.section("Gossip", describeGossip(settlement)));
		pages.addAll(Books.section("Complaints", describeComplaints(settlement, level, residents)));
		pages.addAll(Books.section("Events", describeEvents(settlement, level)));

		Books.exchange(player, hand, Books.write("Village Directory", settlement.name(), pages));
	}

	/** Village name, mayor, and the population breakdown. */
	private static Component summaryPage(
		ServerPlayer player, Settlement settlement, ServerLevel level, List<Villager> residents
	) {
		long adults = residents.stream().filter(villager -> !villager.isBaby()).count();
		long babies = residents.size() - adults;

		long male = 0;
		long female = 0;
		for (Villager villager : residents) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}
			if (soul.identity().genderIdentity() == Gender.MALE) {
				male++;
			} else {
				female++;
			}
		}

		MutableComponent page = Component.empty()
			.append(bold(settlement.name())).append("\n")
			.append(Settlements.describeMayor(settlement, level)).append("\n\n")
			.append(bold("Population")).append("\n")
			.append("Total: " + residents.size() + "\n")
			.append("Adult: " + adults + "\n")
			.append("Baby: " + babies + "\n")
			.append("Male: " + male + "\n")
			.append("Female: " + female + "\n");

		// The rename control belongs to whoever actually holds the office. Anyone else reading the
		// same village's record simply does not see it.
		if (settlement.mayorPlayer().filter(player.getUUID()::equals).isPresent()) {
			page.append("\n").append(Books.action("[Rename village]",
				CustomClicks.VILLAGE_RENAME, CustomClicks.settlement(settlement.id()),
				"Type a new name in chat"));
		}
		return page;
	}

	private static Component statsPage(Settlement settlement, ServerLevel level, List<Villager> residents) {
		return Component.empty()
			.append(Books.heading("Stats"))
			.append("Food: " + Needs.describeFood(settlement, residents) + "\n")
			.append("Last Raid: " + describeLastRaid(settlement, level) + "\n");
	}

	private static String describeLastRaid(Settlement settlement, ServerLevel level) {
		long lastRaid = settlement.lastRaidGameTime();
		if (lastRaid == 0L) {
			return "None";
		}

		long daysAgo = (level.getGameTime() - lastRaid) / 24000L;
		String when = daysAgo <= 0 ? "Today" : daysAgo == 1 ? "1 day ago" : daysAgo + " days ago";
		return when + " (" + (settlement.lastRaidVictory() ? "Victory" : "Defeat") + ")";
	}

	/**
	 * What the village is talking about lately — just the topics, newest first. Who exactly
	 * was overheard saying it is deliberately not shown; gossip belongs to the village, not to
	 * the pair a passing player happened to catch discussing it.
	 */
	private static List<String> describeGossip(Settlement settlement) {
		List<GossipEntry> gossip = new ArrayList<>(settlement.gossipLog());
		gossip.sort(Comparator.comparingLong(GossipEntry::gameTime).reversed());

		List<String> lines = new ArrayList<>();
		for (GossipEntry entry : gossip.subList(0, Math.min(LIST_LIMIT, gossip.size()))) {
			String summary = entry.summary();
			if (!summary.isEmpty()) {
				lines.add(Character.toUpperCase(summary.charAt(0)) + summary.substring(1));
			}
		}
		return spaced(lines);
	}

	/** Genuine, standing problems: not enough beds, not enough food, not enough protection. */
	private static List<String> describeComplaints(
		Settlement settlement, ServerLevel level, List<Villager> residents
	) {
		return spaced(Complaints.current(settlement, level, residents).stream()
			.map(Complaints.Complaint::line)
			.toList());
	}

	/**
	 * What has actually happened lately: deaths, raids, weddings, engagements, and sworn
	 * enmities declared — the village's own history rather than its rumour mill. Drawn from the
	 * settlement's event log, which is wiped clean once a day, so this is only ever what
	 * happened since the last reset.
	 */
	private static List<String> describeEvents(Settlement settlement, ServerLevel level) {
		List<String> lines = new ArrayList<>();
		if (settlement.lastRaidGameTime() > 0
			&& level.getGameTime() - settlement.lastRaidGameTime() <= RAID_NEWS_TICKS) {
			lines.add(settlement.lastRaidVictory()
				? "The village just fought off a raid."
				: "The village was just raided.");
		}

		List<MemoryEntry> events = new ArrayList<>(settlement.eventLog());
		events.sort(Comparator.comparingLong(MemoryEntry::gameTime).reversed());
		for (MemoryEntry event : events.subList(0, Math.min(LIST_LIMIT, events.size()))) {
			String text = event.text();
			if (!text.isEmpty()) {
				lines.add(Character.toUpperCase(text.charAt(0)) + text.substring(1));
			}
		}
		return spaced(lines);
	}

	/**
	 * Interleaves a blank line between entries. Each blank is its own list entry, so
	 * pagination charges it a display line and pages never overflow.
	 */
	private static List<String> spaced(List<String> lines) {
		List<String> out = new ArrayList<>();
		for (String line : lines) {
			if (!out.isEmpty()) {
				out.add("");
			}
			out.add(line);
		}
		return out;
	}

	private static MutableComponent bold(String text) {
		return Component.literal(text).withStyle(ChatFormatting.BOLD);
	}
}
