package com.talkingvillagers.identity;

import java.util.HashSet;
import java.util.Set;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Attaches and maintains the mod's identity data on villagers.
 *
 * <p>Every villager the server loads passes through {@link #ensureSoul}, which is what makes
 * the mod work on existing worlds: a villager that predates the mod, was spawned from an egg,
 * or was cured from a zombie villager has no soul, so one is generated the first time it
 * loads. Nothing else in the mod has to care whether a villager is new or retrofitted.
 */
public final class VillagerSouls {
	/** How far to look for names already in use when picking a new one. */
	private static final double NAME_UNIQUENESS_RADIUS = 96.0;

	private VillagerSouls() {
	}

	/**
	 * Returns this villager's soul, generating one if it does not have it yet.
	 *
	 * <p>A villager that is already an adult when first seen has its orientation rolled
	 * immediately; a baby has that deferred until it grows up.
	 */
	public static VillagerSoul ensureSoul(Villager villager, ServerLevel level) {
		VillagerSoul existing = villager.getAttached(Attachments.SOUL);
		if (existing != null) {
			return existing;
		}

		TalkingVillagersConfig config = TalkingVillagers.config();
		RandomSource random = villager.getRandom();
		VillagerIdentity identity = IdentityGenerator.generate(
			random, config, nearbyFirstNames(villager, level), !villager.isBaby());

		VillagerSoul soul = new VillagerSoul(identity);
		villager.setAttached(Attachments.SOUL, soul);
		applyNameTag(villager, soul);
		TalkingVillagers.LOGGER.debug("Generated identity {} for villager {}", identity.fullName(), villager.getUUID());
		return soul;
	}

	/**
	 * Rolls the adulthood identity changes if this villager has just grown up.
	 *
	 * <p>Vanilla ages villagers on its own schedule and there is no growing-up event, so this
	 * is driven from the mod's periodic sweep: a villager whose soul still says child but
	 * which vanilla no longer considers a baby has crossed the boundary.
	 */
	public static VillagerSoul updateForAge(Villager villager, ServerLevel level) {
		VillagerSoul soul = ensureSoul(villager, level);
		if (soul.identity().adult() || villager.isBaby()) {
			return soul;
		}

		TalkingVillagersConfig config = TalkingVillagers.config();
		VillagerIdentity grown = IdentityGenerator.applyAdulthood(
			soul.identity(), villager.getRandom(), config, nearbyFirstNames(villager, level));
		soul.setIdentity(grown);
		applyNameTag(villager, soul);

		if (grown.transgender()) {
			soul.remember(level.getGameTime(), "grew up and took the name " + grown.firstName());
		}
		return soul;
	}

	/**
	 * Puts the villager's name on the entity, which is how a vanilla client sees it — no
	 * custom packets involved.
	 *
	 * <p>The name is deliberately not marked always-visible; see
	 * {@link Tuning.Identity#NAME_TAGS_ALWAYS_VISIBLE} for why.
	 */
	public static void applyNameTag(Villager villager, VillagerSoul soul) {
		villager.setCustomName(Component.literal(soul.identity().fullName()));
		villager.setCustomNameVisible(Tuning.Identity.NAME_TAGS_ALWAYS_VISIBLE);
	}

	/**
	 * First names in use by villagers nearby, so a new arrival is unlikely to duplicate one.
	 *
	 * <p>This is a proximity check rather than a true per-settlement one because a villager
	 * being named has not necessarily been assigned to a settlement yet.
	 */
	private static Set<String> nearbyFirstNames(Villager villager, ServerLevel level) {
		Set<String> taken = new HashSet<>();
		AABB box = villager.getBoundingBox().inflate(NAME_UNIQUENESS_RADIUS);
		for (Villager other : level.getEntitiesOfClass(Villager.class, box, candidate -> candidate != villager)) {
			VillagerSoul soul = other.getAttached(Attachments.SOUL);
			if (soul != null) {
				taken.add(soul.identity().firstName());
			}
		}
		return taken;
	}
}
