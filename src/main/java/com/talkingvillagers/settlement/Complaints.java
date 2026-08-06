package com.talkingvillagers.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.Relationships;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * The village's standing problems, and the daily reckoning over them.
 *
 * <p>A complaint is a condition, not an event: not enough homes, not enough food, no free
 * work, no protection. {@link #current} computes whatever holds right now, which is what the
 * book's complaints page shows.
 *
 * <p>Accountability runs on a day's grace. Each morning, just after the harvest has landed —
 * so a food problem the farmers have just solved does not count — the standing complaints are
 * compared with yesterday morning's. Any complaint that stood the whole day turns the village
 * against its mayor: every adult resident loses the same rolled amount of regard for them.
 * One reckoning a day, whatever the number of complaints — the village is angry about being
 * neglected, not angry once per line item.
 */
public final class Complaints {
	/** Adults a settlement needs before having no golems at all is worth complaining about. */
	private static final int MIN_ADULTS_FOR_PROTECTION_CONCERN = 5;

	/** The least and most regard the mayor loses per resident at a morning reckoning. */
	private static final int NEGLECT_DROP_MIN = 4;
	private static final int NEGLECT_DROP_MAX = 12;

	private Complaints() {
	}

	/**
	 * A standing problem.
	 *
	 * @param key  what kind of problem it is, stable from day to day so mornings can be
	 *             compared — the wording of a complaint may change while the problem does not
	 * @param line the sentence the village book shows for it
	 */
	public record Complaint(String key, String line) {
	}

	/** Every complaint the village has right now. */
	public static List<Complaint> current(
		Settlement settlement, ServerLevel level, List<Villager> residents
	) {
		List<Complaint> complaints = new ArrayList<>();
		long adults = residents.stream().filter(villager -> !villager.isBaby()).count();

		// A complaint only when beds genuinely fall short of heads: a village of exactly as
		// many beds as villagers is fully housed and has nothing to complain about.
		long beds = level.getPoiManager().getCountInRange(
			holder -> holder.is(PoiTypes.HOME),
			settlement.bell().pos(),
			(int) Tuning.Settlement.BELL_RADIUS,
			PoiManager.Occupancy.ANY);
		if (beds < residents.size()) {
			complaints.add(new Complaint("homes", "There are not enough homes for the villagers"));
		}

		// One key for both stages: food that was short yesterday and is starving today is the
		// same failure a day older, not a fresh complaint with a fresh day of grace.
		if (Needs.isStarving(settlement)) {
			complaints.add(new Complaint("food", "The village is starving"));
		} else if (Needs.isShort(settlement, residents)) {
			complaints.add(new Complaint("food", "Food is running short"));
		}

		Jobs.describeComplaint(settlement, level, residents)
			.ifPresent(line -> complaints.add(new Complaint("work", line)));

		long golems = level.getEntitiesOfClass(IronGolem.class,
			new AABB(settlement.bell().pos()).inflate(Tuning.Settlement.BELL_RADIUS)).size();
		if (golems == 0 && adults >= MIN_ADULTS_FOR_PROTECTION_CONCERN) {
			complaints.add(new Complaint("protection", "The village has no golems to protect it"));
		}

		return complaints;
	}

	/**
	 * Runs the morning reckoning, if it is that time of day.
	 *
	 * <p>Called from the settlement sweep after {@link Needs#tick}, so it lands in the same
	 * window as the morning meal and reads the food state with the day's harvest already in.
	 */
	public static void tick(Settlement settlement, ServerLevel level, List<Villager> residents) {
		long timeOfDay = level.getOverworldClockTime() % 24000L;
		if (!Needs.isMealWindow(timeOfDay, Needs.MORNING_MEAL_TICK)) {
			return;
		}

		List<String> yesterday = settlement.standingComplaints();
		List<String> today = current(settlement, level, residents).stream()
			.map(Complaint::key)
			.toList();
		settlement.setStandingComplaints(today);
		SettlementData.get(level.getServer()).markChanged();

		// New complaints get their day of grace; only ones that stood since yesterday bite.
		if (today.stream().noneMatch(yesterday::contains)) {
			return;
		}
		UUID mayor = settlement.mayorPlayer().or(settlement::mayorVillager).orElse(null);
		if (mayor == null) {
			return;
		}

		long gameTime = level.getGameTime();
		int drop = -(NEGLECT_DROP_MIN
			+ level.getRandom().nextInt(NEGLECT_DROP_MAX - NEGLECT_DROP_MIN + 1));
		for (Villager villager : residents) {
			// The mayor does not resent themselves, and children do not keep grievances.
			if (villager.isBaby() || villager.getUUID().equals(mayor)) {
				continue;
			}
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}
			Relationships.apply(soul, mayor, drop, gameTime, level,
				"blames the " + settlement.mayorTitle() + " for the village's unmet needs");
		}
	}
}
