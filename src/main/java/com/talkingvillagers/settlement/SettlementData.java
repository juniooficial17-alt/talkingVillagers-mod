package com.talkingvillagers.settlement;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;

import com.talkingvillagers.TalkingVillagers;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World storage for every known settlement.
 *
 * <p>Stored at whole-save scope rather than per-dimension, because a player can be mayor of
 * settlements in different dimensions and the mod wants one consistent list of them.
 *
 * <p>Every mutating method calls {@link #setDirty()}; without it Minecraft never writes the
 * file, and the loss would only show up after a restart.
 */
public final class SettlementData extends SavedData {
	private static final Codec<SettlementData> CODEC = Settlement.CODEC
		.listOf()
		.fieldOf("settlements")
		.codec()
		.xmap(SettlementData::unpack, SettlementData::pack);

	public static final SavedDataType<SettlementData> TYPE = new SavedDataType<>(
		TalkingVillagers.id("settlements"),
		SettlementData::new,
		CODEC,
		// A mod cannot add a DataFixTypes constant, and this one is only consulted to run
		// vanilla's fixers over the tag on load — a no-op for keys vanilla does not know.
		DataFixTypes.LEVEL
	);

	private final Map<UUID, Settlement> settlements = new HashMap<>();

	public SettlementData() {
	}

	private static SettlementData unpack(List<Settlement> list) {
		SettlementData data = new SettlementData();
		list.forEach(settlement -> data.settlements.put(settlement.id(), settlement));
		return data;
	}

	private List<Settlement> pack() {
		return List.copyOf(this.settlements.values());
	}

	/** The store for this server, creating it on first use. */
	public static SettlementData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<Settlement> byId(UUID id) {
		return Optional.ofNullable(this.settlements.get(id));
	}

	/** The settlement anchored on exactly this bell, if one is known. */
	public Optional<Settlement> byBell(GlobalPos bell) {
		for (Settlement settlement : this.settlements.values()) {
			if (settlement.bell().equals(bell)) {
				return Optional.of(settlement);
			}
		}
		return Optional.empty();
	}

	public Collection<Settlement> all() {
		return List.copyOf(this.settlements.values());
	}

	public void add(Settlement settlement) {
		this.settlements.put(settlement.id(), settlement);
		setDirty();
	}

	public void remove(UUID id) {
		if (this.settlements.remove(id) != null) {
			setDirty();
		}
	}

	/**
	 * Marks the store dirty after a caller has mutated a settlement in place.
	 *
	 * <p>Settlements are handed out as live objects, so this has to be called explicitly by
	 * whoever changed one — there is no way to notice from here.
	 */
	public void markChanged() {
		setDirty();
	}

}
