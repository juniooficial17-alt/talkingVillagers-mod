package com.talkingvillagers.settlement;

import java.util.List;
import java.util.Set;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/**
 * Settlement-level needs. Version one covers food only.
 *
 * <p>Food is a stock in points. Each food-producing villager brings in
 * {@link Tuning.Needs#FOOD_PER_FARMER_PER_DAY} points with the morning meal — and the
 * stockpile is capped at one day of their combined output, so a village can never hoard past
 * what its farmers sustain. Everyone eats twice a day, morning and night. A settlement that
 * grows without gaining farmers slides into shortage: complaints, then villagers actually
 * starving. The resentment aimed at whoever is in charge is not applied here — that is
 * {@link Complaints}' daily reckoning, which covers every kind of standing complaint at once.
 */
public final class Needs {
	/** Trades that feed a village. */
	private static final Set<String> FOOD_PROFESSIONS = Set.of(
		"farmer", "fisherman", "butcher", "shepherd");

	/**
	 * Time of day the morning meal is eaten, shortly after villagers rise. Also when the day's
	 * harvest lands, which is why {@link Complaints} anchors its reckoning to it.
	 */
	static final long MORNING_MEAL_TICK = 1000L;

	/** Time of day the night meal is eaten, as villagers head home at dusk. */
	private static final long NIGHT_MEAL_TICK = 13000L;

	/** Chance per starving villager, per missed meal, of actually dying of it. */
	private static final double STARVATION_CHANCE = 0.15;

	private Needs() {
	}

	/**
	 * Serves a meal if it is mealtime. The whole day's harvest lands with the morning meal;
	 * the night meal only eats from the stock. Either way everyone present eats one point, and
	 * the stock is capped at {@link #maxFood}.
	 *
	 * <p>Driven off the time of day so meals happen at dawn and dusk, and only while residents
	 * are loaded — a settlement in an unloaded chunk neither farms nor starves while nobody is
	 * looking.
	 */
	public static void tick(Settlement settlement, ServerLevel level, List<Villager> residents) {
		TalkingVillagersConfig config = TalkingVillagers.config();
		if (!config.needs.foodEnabled || residents.isEmpty()) {
			return;
		}

		long timeOfDay = level.getOverworldClockTime() % 24000L;
		boolean morning = isMealWindow(timeOfDay, MORNING_MEAL_TICK);
		if (!morning && !isMealWindow(timeOfDay, NIGHT_MEAL_TICK)) {
			return;
		}

		long gameTime = level.getGameTime();
		SettlementData data = SettlementData.get(level.getServer());

		double max = maxFood(residents);
		double harvest = morning ? max : 0.0;
		double demand = residents.size() * Tuning.Needs.FOOD_PER_VILLAGER_PER_MEAL;

		// The harvest is stocked BEFORE anyone eats. Order matters: a village at zero food
		// with working farmers must be fed by this same tick's harvest, not starved on a
		// technicality because consumption was settled first.
		double stocked = Math.min(max, settlement.food() + harvest);
		boolean ranOut = stocked < demand;
		settlement.setFood(Math.max(0.0, stocked - demand));
		data.markChanged();

		if (ranOut) {
			starve(settlement, level, residents, gameTime);
		} else if (isShort(settlement, residents)) {
			complain(settlement, level, residents, gameTime);
		}
	}

	/**
	 * Whether this sweep lands in a meal's once-a-day window. Sized to the sweep interval the
	 * same way the daily ceremony is, so each meal is served exactly once.
	 */
	static boolean isMealWindow(long timeOfDay, long mealTick) {
		return timeOfDay >= mealTick && timeOfDay < mealTick + TalkingVillagers.SWEEP_INTERVAL_TICKS;
	}

	/** The most food this village can keep stocked: a day's output from its current farmers. */
	public static int maxFood(List<Villager> residents) {
		int farmers = 0;
		for (Villager villager : residents) {
			if (feedsTheVillage(villager)) {
				farmers++;
			}
		}
		return farmers * Tuning.Needs.FOOD_PER_FARMER_PER_DAY;
	}

	/** Whether the village has actually run dry — the state that kills. */
	public static boolean isStarving(Settlement settlement) {
		return TalkingVillagers.config().needs.foodEnabled && settlement.food() <= 0.0;
	}

	/** Whether the stock has fallen below one full day of meals for everyone. */
	public static boolean isShort(Settlement settlement, List<Villager> residents) {
		double dayOfMeals = residents.size()
			* Tuning.Needs.FOOD_PER_VILLAGER_PER_MEAL * Tuning.Needs.MEALS_PER_DAY;
		return TalkingVillagers.config().needs.foodEnabled && settlement.food() < dayOfMeals;
	}

	private static boolean feedsTheVillage(Villager villager) {
		if (villager.isBaby()) {
			return false;
		}
		Holder<VillagerProfession> profession = villager.getVillagerData().profession();
		return profession.unwrapKey()
			.map(key -> FOOD_PROFESSIONS.contains(key.identifier().getPath()))
			.orElse(false);
	}

	/** Hungry but not yet dying: grumbling. The mayor answers for it at the daily reckoning. */
	private static void complain(
		Settlement settlement, ServerLevel level, List<Villager> residents, long gameTime
	) {
		for (Villager villager : residents) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null || villager.isBaby()) {
				continue;
			}
			soul.remember(gameTime, "is going hungry in " + settlement.name());
		}
		// Deliberately not written to the event log: a shortage is a standing condition, and
		// the complaints page already carries it for as long as it lasts.
	}

	/** Out of food: villagers begin to die. */
	private static void starve(
		Settlement settlement, ServerLevel level, List<Villager> residents, long gameTime
	) {
		for (Villager villager : residents) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null) {
				soul.remember(gameTime, "is starving in " + settlement.name());
			}

			if (level.getRandom().nextDouble() < STARVATION_CHANCE) {
				// Vanilla starvation damage, so death handling, drops and the grief system all
				// behave exactly as they would for any other death.
				villager.hurtServer(level, level.damageSources().starve(), Float.MAX_VALUE);
			}
		}
		// Deliberately not written to the event log: starvation is a standing condition, and
		// the complaints page already carries it for as long as it lasts.
	}

	/**
	 * The stock as "current/max". The stored value is clamped to the ceiling for display, so a
	 * village that just lost a farmer never shows more food than it can now hold.
	 */
	public static String describeFood(Settlement settlement, List<Villager> residents) {
		if (!TalkingVillagers.config().needs.foodEnabled) {
			return "not tracked";
		}
		int max = maxFood(residents);
		int food = (int) Math.min(settlement.food(), max);
		return food + "/" + max;
	}
}
