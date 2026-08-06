package com.talkingvillagers.family;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.IdentityGenerator;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.Bond;
import com.talkingvillagers.social.VillagerSoul;

import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

/**
 * The wedding itself.
 *
 * <p>Marriage happens on its own: an engaged couple is married automatically at the next daily
 * ceremony (see {@code Tuning.Family#DAILY_CEREMONY_TICK}), the same moment mourning ends for
 * anyone grieving. Nobody has to go and do anything for either — a village runs its own life on
 * a schedule rather than waiting on a player to notice and act.
 */
public final class Weddings {
	private Weddings() {
	}

	/**
	 * Marries every mutually-engaged couple currently loaded in this level. Called once a day,
	 * from the daily ceremony sweep.
	 */
	public static void marryDueCouples(ServerLevel level) {
		long gameTime = level.getGameTime();
		Set<UUID> married = new HashSet<>();

		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			if (married.contains(villager.getUUID())) {
				continue;
			}
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul == null) {
				continue;
			}

			for (UUID fianceId : soul.withBond(Bond.ENGAGED)) {
				if (married.contains(fianceId)) {
					continue;
				}
				Optional<Villager> fiance = TalkingVillagers.tracker().find(fianceId);
				if (fiance.isEmpty()) {
					continue;
				}
				VillagerSoul fianceSoul = fiance.get().getAttached(Attachments.SOUL);
				// Require the engagement to be mutual before marrying anyone.
				if (fianceSoul == null || !fianceSoul.withBond(Bond.ENGAGED).contains(villager.getUUID())) {
					continue;
				}

				marry(level, villager, soul, fiance.get(), fianceSoul, gameTime);
				married.add(villager.getUUID());
				married.add(fianceId);
				break;
			}
		}
	}

	/** Performs the marriage: the bond, the roll for children, and the memory of it. */
	private static void marry(
		ServerLevel level, Villager one, VillagerSoul oneSoul, Villager two, VillagerSoul twoSoul, long gameTime
	) {
		oneSoul.setBond(two.getUUID(), Bond.SPOUSE, gameTime);
		twoSoul.setBond(one.getUUID(), Bond.SPOUSE, gameTime);

		// How many children this marriage will have is decided once, now, rather than rolled
		// repeatedly — otherwise a long-lived couple would drift towards the maximum.
		int children = IdentityGenerator.rollChildCount(level.getRandom());
		oneSoul.setPlannedChildren(children);
		twoSoul.setPlannedChildren(children);

		String oneName = oneSoul.identity().firstName();
		String twoName = twoSoul.identity().firstName();
		oneSoul.remember(gameTime, "married " + twoName);
		twoSoul.remember(gameTime, "married " + oneName);
		Settlements.recordVillageEvent(oneSoul, level, gameTime, oneName + " married " + twoName);

		BehaviorUtils.lockGazeAndWalkToEachOther(one, two, 0.5F, 2);
		celebrateWithFireworks(level, one, two);

		TalkingVillagers.bubbles().showNarration(one,
			oneName + " and " + twoName + " are wed",
			TalkingVillagers.config().conversation.overheadDurationTicks);
	}

	/**
	 * Launches a firework from each newlywed — the same rocket vanilla villagers fire when a
	 * raid is won ({@code CelebrateVillagersSurvivedRaid}): one burst in a random colour, with
	 * a random flight height.
	 */
	private static void celebrateWithFireworks(ServerLevel level, Villager one, Villager two) {
		RandomSource random = level.getRandom();
		for (Villager partner : List.of(one, two)) {
			DyeColor colour = Util.getRandom(DyeColor.values(), random);
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			rocket.set(DataComponents.FIREWORKS, new Fireworks(random.nextInt(3), List.of(
				new FireworkExplosion(FireworkExplosion.Shape.BURST,
					IntList.of(colour.getFireworkColor()), IntList.of(), false, false))));
			Projectile.spawnProjectile(
				new FireworkRocketEntity(level, partner,
					partner.getX(), partner.getEyeY(), partner.getZ(), rocket),
				level, rocket);
		}
	}
}
