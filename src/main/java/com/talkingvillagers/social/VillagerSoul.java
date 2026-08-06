package com.talkingvillagers.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.talkingvillagers.identity.VillagerIdentity;

import net.minecraft.core.UUIDUtil;

/**
 * Everything the mod knows about one villager, stored as a Fabric data attachment so it
 * rides along in the entity's own save data.
 *
 * <p>This is deliberately mutable: it is read and written many times per tick by the
 * conversation and relationship systems, and the attachment holds a live reference that is
 * serialised on world save. Callers mutate it in place rather than reassigning the
 * attachment.
 *
 * <p>The relationship map is bounded — see {@link #trimRelationships} — because a villager in
 * a busy world would otherwise accumulate an entry for every entity it ever met and carry
 * that forever in the save file.
 */
public final class VillagerSoul {
	/** Rolling memories kept per villager. Older ones are folded into {@link #summary}. */
	public static final int MEMORY_LIMIT = 12;
	/**
	 * Rolling gossip kept per villager, separate from {@link #memories}. Gossip is hearsay
	 * about other people rather than something that happened to this villager, so it is not
	 * worth folding into the rolling summary the way a life event is — the oldest is simply
	 * dropped.
	 */
	public static final int GOSSIP_LIMIT = 8;

	public static final Codec<VillagerSoul> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				VillagerIdentity.CODEC.fieldOf("identity").forGetter(soul -> soul.identity),
				// Map<UUID, Relationship> is stored as a list of entries: UUIDUtil.CODEC
				// encodes to an int array, which cannot be an NBT map key. This is the same
				// shape vanilla's GossipContainer uses for exactly this reason.
				Relationship.CODEC.listOf()
					.optionalFieldOf("relationships", List.of())
					.forGetter(soul -> List.copyOf(soul.relationships.values())),
				MemoryEntry.CODEC.listOf()
					.optionalFieldOf("memories", List.of())
					.forGetter(soul -> List.copyOf(soul.memories)),
				Codec.STRING.optionalFieldOf("summary", "").forGetter(soul -> soul.summary),
				MemoryEntry.CODEC.listOf()
					.optionalFieldOf("gossip", List.of())
					.forGetter(soul -> List.copyOf(soul.gossip)),
				UUIDUtil.CODEC.optionalFieldOf("settlement").forGetter(soul -> soul.settlement),
				Codec.INT.optionalFieldOf("planned_children", -1).forGetter(soul -> soul.plannedChildren),
				Codec.INT.optionalFieldOf("children_born", 0).forGetter(soul -> soul.childrenBorn),
				Codec.LONG.optionalFieldOf("grieving_until", 0L).forGetter(soul -> soul.grievingUntil),
				Codec.LONG.optionalFieldOf("last_birth", 0L).forGetter(soul -> soul.lastBirthGameTime),
				Codec.INT.optionalFieldOf("nights_without_bed", 0)
					.forGetter(soul -> soul.nightsWithoutBed)
			)
			.apply(instance, VillagerSoul::new)
	);

	private VillagerIdentity identity;
	private final Map<UUID, Relationship> relationships = new HashMap<>();
	private final List<MemoryEntry> memories = new ArrayList<>();
	private String summary;
	private final List<MemoryEntry> gossip = new ArrayList<>();
	private Optional<UUID> settlement;
	private int plannedChildren;
	private int childrenBorn;
	private long grievingUntil;
	private long lastBirthGameTime;
	private int nightsWithoutBed;

	private VillagerSoul(
		VillagerIdentity identity,
		List<Relationship> relationships,
		List<MemoryEntry> memories,
		String summary,
		List<MemoryEntry> gossip,
		Optional<UUID> settlement,
		int plannedChildren,
		int childrenBorn,
		long grievingUntil,
		long lastBirthGameTime,
		int nightsWithoutBed
	) {
		this.identity = identity;
		relationships.forEach(entry -> this.relationships.put(entry.target(), entry));
		this.memories.addAll(memories);
		this.summary = summary;
		this.gossip.addAll(gossip);
		this.settlement = settlement;
		this.plannedChildren = plannedChildren;
		this.childrenBorn = childrenBorn;
		this.grievingUntil = grievingUntil;
		this.lastBirthGameTime = lastBirthGameTime;
		this.nightsWithoutBed = nightsWithoutBed;
	}

	public VillagerSoul(VillagerIdentity identity) {
		this.identity = identity;
		this.summary = "";
		this.settlement = Optional.empty();
		this.plannedChildren = -1;
		this.childrenBorn = 0;
		this.grievingUntil = 0L;
		this.lastBirthGameTime = 0L;
	}

	public VillagerIdentity identity() {
		return this.identity;
	}

	public void setIdentity(VillagerIdentity newIdentity) {
		this.identity = newIdentity;
	}

	public String name() {
		return this.identity.fullName();
	}

	// ---- relationships ----

	/** The relationship with {@code target}, or empty if they have never interacted. */
	public Optional<Relationship> relationship(UUID target) {
		return Optional.ofNullable(this.relationships.get(target));
	}

	/**
	 * The relationship with {@code target}, created at neutral if this is a first encounter.
	 */
	public Relationship relationshipOrFresh(UUID target, long gameTime) {
		return this.relationships.computeIfAbsent(target, id -> Relationship.fresh(id, gameTime));
	}

	/**
	 * Applies a relationship change, scaled by how heavily this villager's personality takes
	 * things. Returns the updated relationship.
	 */
	public Relationship adjustRelationship(UUID target, int delta, long gameTime) {
		int weighted = Math.round(delta * this.identity.mbti().sentimentWeight());
		// Keep a requested change from rounding away to nothing, or an event would silently
		// have no effect on stoical villagers.
		if (weighted == 0 && delta != 0) {
			weighted = delta > 0 ? 1 : -1;
		}
		Relationship updated = relationshipOrFresh(target, gameTime).adjust(weighted, gameTime);
		this.relationships.put(target, updated);
		return updated;
	}

	public void setRelationship(Relationship relationship) {
		this.relationships.put(relationship.target(), relationship);
	}

	public void setBond(UUID target, Bond bond, long gameTime) {
		Relationship current = relationshipOrFresh(target, gameTime);
		this.relationships.put(target, current.withBond(bond));
	}

	public Map<UUID, Relationship> relationships() {
		return Map.copyOf(this.relationships);
	}

	/** Everyone this villager holds the given bond with, e.g. every child. */
	public List<UUID> withBond(Bond bond) {
		List<UUID> found = new ArrayList<>();
		this.relationships.forEach((id, relationship) -> {
			if (relationship.bond() == bond) {
				found.add(id);
			}
		});
		return found;
	}

	public Optional<UUID> spouse() {
		return withBond(Bond.SPOUSE).stream().findFirst();
	}

	/**
	 * Forgets the least significant relationships once the cap is exceeded.
	 *
	 * <p>Family, romance and anyone strongly loved or hated is never forgotten; among the
	 * rest, the ones nearest neutral and least recently interacted with go first, so trimming
	 * loses the acquaintances a villager would plausibly stop thinking about.
	 */
	public void trimRelationships(int cap) {
		if (this.relationships.size() <= cap) {
			return;
		}

		List<Relationship> forgettable = new ArrayList<>();
		for (Relationship relationship : this.relationships.values()) {
			boolean memorable = relationship.bond() != Bond.NONE
				|| relationship.meter() <= Relationship.ENEMY_THRESHOLD
				|| relationship.meter() >= Relationship.FRIEND_THRESHOLD;
			if (!memorable) {
				forgettable.add(relationship);
			}
		}

		forgettable.sort(Comparator
			.comparingInt((Relationship r) -> Math.abs(r.meter() - Relationship.NEUTRAL))
			.thenComparingLong(Relationship::lastInteraction));

		int toRemove = this.relationships.size() - cap;
		for (Relationship relationship : forgettable) {
			if (toRemove-- <= 0) {
				break;
			}
			this.relationships.remove(relationship.target());
		}
	}

	// ---- memories ----

	/**
	 * Records something that happened. Once the buffer is full the oldest memory is folded
	 * into the rolling summary, which is what keeps prompt size bounded no matter how long a
	 * villager lives.
	 *
	 * @return the entry as stored (it may have been truncated), or null if there was nothing
	 *         to store — callers keep it if they intend to {@link #replaceMemory} later
	 */
	public MemoryEntry remember(long gameTime, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		MemoryEntry entry = MemoryEntry.of(gameTime, text);
		this.memories.add(entry);
		while (this.memories.size() > MEMORY_LIMIT) {
			MemoryEntry oldest = this.memories.remove(0);
			foldIntoSummary(oldest.text());
		}
		return entry;
	}

	/**
	 * Swaps an earlier memory's text in place, keeping its timestamp — for when a better
	 * wording of the same event arrives later, such as a model-written summary replacing the
	 * templated line that was recorded while the model was still thinking. Does nothing if
	 * the memory has since been folded away.
	 */
	public void replaceMemory(MemoryEntry previous, String newText) {
		if (previous == null || newText == null || newText.isBlank()) {
			return;
		}
		int index = this.memories.indexOf(previous);
		if (index >= 0) {
			this.memories.set(index, MemoryEntry.of(previous.gameTime(), newText));
		}
	}

	/**
	 * Appends to the rolling summary, keeping only the most recent clauses so the summary
	 * itself cannot grow without bound.
	 */
	private void foldIntoSummary(String text) {
		String combined = this.summary.isEmpty() ? text : this.summary + "; " + text;
		int limit = 400;
		if (combined.length() > limit) {
			// Drop from the front at a clause boundary so the summary stays readable.
			int cut = combined.indexOf("; ", combined.length() - limit);
			combined = cut >= 0 ? combined.substring(cut + 2) : combined.substring(combined.length() - limit);
		}
		this.summary = combined;
	}

	public List<MemoryEntry> memories() {
		return List.copyOf(this.memories);
	}

	public String summary() {
		return this.summary;
	}

	/**
	 * Records a piece of gossip heard or told, kept separate from {@link #memories} — this is
	 * hearsay about other people, not a life event of this villager's own, so it has no place
	 * in the rolling summary and is simply dropped once the buffer is full.
	 */
	public void rememberGossip(long gameTime, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		this.gossip.add(MemoryEntry.of(gameTime, text));
		while (this.gossip.size() > GOSSIP_LIMIT) {
			this.gossip.remove(0);
		}
	}

	public List<MemoryEntry> gossipHeard() {
		return List.copyOf(this.gossip);
	}

	/** Wipes the gossip log clean. Called once a day, not when gossip is simply heard. */
	public void clearGossip() {
		this.gossip.clear();
	}

	// ---- settlement, family, grief ----

	public Optional<UUID> settlement() {
		return this.settlement;
	}

	public void setSettlement(Optional<UUID> settlementId) {
		this.settlement = settlementId;
	}

	/** How many children this marriage will produce; -1 until it has been rolled. */
	public int plannedChildren() {
		return this.plannedChildren;
	}

	public void setPlannedChildren(int planned) {
		this.plannedChildren = planned;
	}

	public int childrenBorn() {
		return this.childrenBorn;
	}

	public void recordChildBorn(long gameTime) {
		this.childrenBorn++;
		this.lastBirthGameTime = gameTime;
	}

	/** Game time of this villager's last child, or 0 if they have never had one. */
	public long lastBirthGameTime() {
		return this.lastBirthGameTime;
	}

	/**
	 * Records a failed attempt at a child — no spare bed, say — so the couple waits out the
	 * usual interval before trying again instead of retrying every tick.
	 */
	public void deferBirth(long gameTime) {
		this.lastBirthGameTime = gameTime;
	}

	/** Consecutive nights this villager has had nowhere to sleep. */
	public int nightsWithoutBed() {
		return this.nightsWithoutBed;
	}

	public void recordNightWithoutBed() {
		this.nightsWithoutBed++;
	}

	public void resetNightsWithoutBed() {
		this.nightsWithoutBed = 0;
	}

	public boolean grieving(long gameTime) {
		return gameTime < this.grievingUntil;
	}

	public void grieveUntil(long gameTime) {
		this.grievingUntil = Math.max(this.grievingUntil, gameTime);
	}

	/**
	 * Ends grief outright, regardless of how much longer {@link #grieveUntil} would otherwise
	 * have held it — the daily ceremony's doing, not a timer running out.
	 */
	public void endGrieving() {
		this.grievingUntil = 0L;
	}
}
