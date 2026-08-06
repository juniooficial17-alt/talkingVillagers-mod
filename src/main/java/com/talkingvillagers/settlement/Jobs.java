package com.talkingvillagers.settlement;

import java.util.List;
import java.util.Optional;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Jobless adults wanting work.
 *
 * <p>Vanilla villagers will take a job site if they happen upon one; here they actively want
 * one, and if the settlement has no free workstation they say so. That turns "my village is
 * full of unemployed villagers" from something the player has to notice into something the
 * village tells them about.
 *
 * <p>The actual claiming is left to vanilla's brain, which already handles pathing, ticketing
 * and profession assignment properly. This only points villagers at work and complains when
 * there is none.
 */
public final class Jobs {
	/** How far a villager will look for a workstation. Matches vanilla's POI scan range. */
	private static final int WORKSTATION_SEARCH_RADIUS = 48;

	/** Minimum ticks between one settlement's unemployment complaints. */
	private static final long COMPLAINT_INTERVAL_TICKS = 12000L;

	private Jobs() {
	}

	/**
	 * Nudges a settlement's jobless adults towards work, or reports that there is none.
	 *
	 * <p>Called on the same slow cadence as the rest of the settlement upkeep.
	 */
	public static void tick(Settlement settlement, ServerLevel level, List<Villager> residents) {
		List<Villager> jobless = Settlements.joblessAdults(residents);
		if (jobless.isEmpty()) {
			return;
		}

		long gameTime = level.getGameTime();
		int freeStations = countFreeWorkstations(level, settlement.bell().pos());

		if (freeStations > 0) {
			for (Villager villager : jobless) {
				seekWork(villager, level);
			}
			return;
		}

		// Rate-limited off its own clock. This used to piggyback on the needs clock, which only
		// advances at mealtimes — so once enough time had passed, the complaint re-fired every
		// sweep and buried the village's event page under copies of itself.
		if (gameTime - settlement.lastJobComplaint() < COMPLAINT_INTERVAL_TICKS) {
			return;
		}
		settlement.setLastJobComplaint(gameTime);

		for (Villager villager : jobless) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}
			// Only a memory: the mayor answers for standing complaints at Complaints' daily
			// reckoning, not per grumble.
			soul.remember(gameTime, "wants work but there is no workstation free");
		}
		// Deliberately not written to the event log: unemployment is a standing condition, and
		// the complaints page carries it live for as long as it lasts.
	}

	/**
	 * The unemployment complaint for the village book, or empty while everyone who wants work
	 * either has it or has a free workstation to claim.
	 */
	public static Optional<String> describeComplaint(
		Settlement settlement, ServerLevel level, List<Villager> residents
	) {
		int jobless = Settlements.joblessAdults(residents).size();
		if (jobless == 0 || countFreeWorkstations(level, settlement.bell().pos()) > 0) {
			return Optional.empty();
		}
		return Optional.of(jobless + (jobless == 1 ? " villager wants" : " villagers want")
			+ " work, but there are no free workstations");
	}

	/** Points a villager at the nearest unclaimed workstation so vanilla can take it from there. */
	private static void seekWork(Villager villager, ServerLevel level) {
		Optional<BlockPos> station = level.getPoiManager().findClosest(
			holder -> holder.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
			villager.blockPosition(),
			WORKSTATION_SEARCH_RADIUS,
			PoiManager.Occupancy.HAS_SPACE);

		station.ifPresent(pos -> BehaviorUtils.setWalkAndLookTargetMemories(villager, pos, 0.5F, 1));
	}

	private static int countFreeWorkstations(ServerLevel level, BlockPos centre) {
		return (int) level.getPoiManager().getCountInRange(
			holder -> holder.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
			centre,
			WORKSTATION_SEARCH_RADIUS,
			PoiManager.Occupancy.HAS_SPACE);
	}

	/**
	 * Directs a villager to a specific workstation on the mayor's instruction.
	 *
	 * <p>A villager who thinks poorly of the mayor refuses, which is the point of the mayor
	 * having a relationship with their residents at all.
	 *
	 * @return true if the villager accepted the instruction
	 */
	public static boolean assign(Villager villager, BlockPos station, ServerLevel level, int mayorRegard) {
		if (mayorRegard < 30) {
			return false;
		}
		BehaviorUtils.setWalkAndLookTargetMemories(villager, station, 0.5F, 1);

		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul != null) {
			soul.remember(level.getGameTime(), "was told to take up work");
		}
		return true;
	}
}
