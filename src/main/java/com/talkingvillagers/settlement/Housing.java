package com.talkingvillagers.settlement;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Whether a village's residents actually have somewhere to sleep.
 *
 * <p>A villager puts up with sleeping rough only so long: after
 * {@link Tuning.Settlement#NIGHTS_WITHOUT_BED_BEFORE_LEAVING} consecutive nights without a
 * bed they give up on the village and leave for good. That turns a housing shortage from a
 * line on the complaints page into something that visibly costs the village people.
 */
public final class Housing {
	private Housing() {
	}

	/**
	 * The nightly headcount. Runs once per night, deep enough into it that everyone who has a
	 * bed is asleep in it — a claimed bed shows up as the brain's {@code HOME} memory, so
	 * whoever lacks one has genuinely nowhere to sleep tonight.
	 *
	 * <p>Only adults with a settlement are counted: a baby has no say in moving out, and a
	 * wanderer with no village cannot leave one.
	 */
	public static void nightlyBedCheck(ServerLevel level) {
		long gameTime = level.getGameTime();

		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null || villager.isBaby() || soul.settlement().isEmpty()) {
				continue;
			}

			if (villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent()) {
				soul.resetNightsWithoutBed();
				continue;
			}

			soul.recordNightWithoutBed();
			if (soul.nightsWithoutBed() >= Tuning.Settlement.NIGHTS_WITHOUT_BED_BEFORE_LEAVING) {
				leave(villager, soul, level, gameTime);
			}
		}
	}

	/** The villager departs: noted on the village record, then gone from the world. */
	private static void leave(Villager villager, VillagerSoul soul, ServerLevel level, long gameTime) {
		Settlements.recordVillageEvent(soul, level, gameTime,
			soul.identity().firstName() + " has left the village");

		// A departed mayor must vacate the office explicitly: the leadership check deliberately
		// treats a vanished entity as possibly-just-unloaded and would keep them in post forever.
		SettlementData data = SettlementData.get(level.getServer());
		soul.settlement().flatMap(data::byId).ifPresent(settlement -> {
			if (settlement.mayorVillager().filter(villager.getUUID()::equals).isPresent()) {
				settlement.clearMayor();
				data.markChanged();
			}
		});

		TalkingVillagers.LOGGER.info("{} left their village after {} nights without a bed",
			soul.identity().fullName(), soul.nightsWithoutBed());
		villager.discard();
	}
}
