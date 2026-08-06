package com.talkingvillagers.data;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Keeps track of which villagers are currently loaded, per level.
 *
 * <p>The mod's periodic work — aging, courtship, gossip scheduling, needs — only concerns
 * villagers, and there is no vanilla index of them. Sweeping {@code getAllEntities()} every
 * tick would mean walking every mob, item and projectile in the world to find a handful of
 * villagers, so instead this is maintained incrementally from the entity load and unload
 * events.
 *
 * <p>Entries are removed on unload and on death, so a villager that is gone cannot be kept
 * alive by this map.
 */
public final class VillagerTracker {
	private final Map<ServerLevel, Set<Villager>> byLevel = new ConcurrentHashMap<>();

	public void onLoad(Villager villager, ServerLevel level) {
		this.byLevel.computeIfAbsent(level, key -> new LinkedHashSet<>()).add(villager);
	}

	public void onUnload(Villager villager, ServerLevel level) {
		Set<Villager> villagers = this.byLevel.get(level);
		if (villagers != null) {
			villagers.remove(villager);
			if (villagers.isEmpty()) {
				this.byLevel.remove(level, villagers);
			}
		}
	}

	public void onLevelUnload(ServerLevel level) {
		this.byLevel.remove(level);
	}

	public void clear() {
		this.byLevel.clear();
	}

	/**
	 * The villagers loaded in {@code level}. Returns a snapshot, because callers routinely
	 * cause loads and unloads (spawning children, banishing residents) while iterating.
	 */
	public List<Villager> loaded(ServerLevel level) {
		Set<Villager> villagers = this.byLevel.get(level);
		if (villagers == null || villagers.isEmpty()) {
			return List.of();
		}
		return List.copyOf(villagers);
	}

	/** Every tracked villager across all levels. */
	public List<Villager> all() {
		List<Villager> out = new java.util.ArrayList<>();
		for (Set<Villager> villagers : this.byLevel.values()) {
			out.addAll(villagers);
		}
		return out;
	}

	/** Finds a loaded villager by UUID, searching every level. */
	public Optional<Villager> find(UUID id) {
		for (Set<Villager> villagers : this.byLevel.values()) {
			for (Villager villager : villagers) {
				if (villager.getUUID().equals(id)) {
					return Optional.of(villager);
				}
			}
		}
		return Optional.empty();
	}

	public int count() {
		int total = 0;
		for (Collection<Villager> villagers : this.byLevel.values()) {
			total += villagers.size();
		}
		return total;
	}
}
