package com.talkingvillagers.settlement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.social.MemoryEntry;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;

/**
 * A village: a bell, a name, whoever leads it, and how well fed it is.
 *
 * <p>Membership is not stored. A settlement is anchored on its bell and its residents are
 * whichever villagers are within {@link com.talkingvillagers.Tuning.Settlement#BELL_RADIUS} of it,
 * which matches how
 * vanilla already scopes bells and raids and means the mod never has to reconcile a stored
 * roster against villagers that wandered off or died.
 *
 * <p>Every village is the same kind of thing — there is no size class. It elects one of its own
 * to lead it as soon as it is noticed, and a player takes over only by deliberately claiming it
 * with a charter at the bell.
 *
 * <p>Mutable because the food meter and leadership change constantly; persisted through
 * {@link SettlementData}.
 */
public final class Settlement {
	// Saves written by earlier versions carry "hamlet" and "offer_declined" fields. They are
	// simply not read: a record codec ignores keys it has no field for, so an old save loads
	// cleanly and the dead fields disappear the next time it is written.
	public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("id").forGetter(settlement -> settlement.id),
				Codec.STRING.fieldOf("name").forGetter(settlement -> settlement.name),
				GlobalPos.CODEC.fieldOf("bell").forGetter(settlement -> settlement.bell),
				UUIDUtil.CODEC.optionalFieldOf("mayor_player")
					.forGetter(settlement -> settlement.mayorPlayer),
				Codec.STRING.optionalFieldOf("mayor_title", "Mayor")
					.forGetter(settlement -> settlement.mayorTitle),
				UUIDUtil.CODEC.optionalFieldOf("mayor_villager")
					.forGetter(settlement -> settlement.mayorVillager),
				Codec.DOUBLE.optionalFieldOf("food", 100.0).forGetter(settlement -> settlement.food),
				Codec.LONG.optionalFieldOf("founded", 0L).forGetter(settlement -> settlement.foundedGameTime),
				Codec.LONG.optionalFieldOf("last_raid_game_time", 0L)
					.forGetter(settlement -> settlement.lastRaidGameTime),
				Codec.BOOL.optionalFieldOf("last_raid_victory", false)
					.forGetter(settlement -> settlement.lastRaidVictory),
				Codec.BOOL.optionalFieldOf("family_seeded", false)
					.forGetter(settlement -> settlement.familySeeded),
				GossipEntry.CODEC.listOf().optionalFieldOf("gossip_log", List.of())
					.forGetter(settlement -> List.copyOf(settlement.gossipLog)),
				MemoryEntry.CODEC.listOf().optionalFieldOf("event_log", List.of())
					.forGetter(settlement -> List.copyOf(settlement.eventLog)),
				Codec.STRING.listOf().optionalFieldOf("gossip_topics", List.of())
					.forGetter(settlement -> List.copyOf(settlement.gossipTopics)),
				Codec.STRING.listOf().optionalFieldOf("standing_complaints", List.of())
					.forGetter(settlement -> List.copyOf(settlement.standingComplaints))
			)
			.apply(instance, Settlement::new)
	);

	private final UUID id;
	private String name;
	private GlobalPos bell;
	private Optional<UUID> mayorPlayer;
	private String mayorTitle;
	private Optional<UUID> mayorVillager;
	/** Food stock, 0-100. Starts full so a newly discovered village is not instantly starving. */
	private double food;
	private final long foundedGameTime;
	/** Game time of the last raid concluded near this settlement's bell; 0 if never. */
	private long lastRaidGameTime;
	/** Whether that raid was beaten back. Meaningless while {@link #lastRaidGameTime} is 0. */
	private boolean lastRaidVictory;
	/**
	 * Whether this settlement's residents have already been given a pre-existing social web —
	 * random standing with each other, plus some couples and family ties. Runs once, the first
	 * time the settlement is seen with more than one resident; never again after, so a village
	 * that grows later gains new relationships only through ordinary gameplay.
	 */
	private boolean familySeeded;
	/** The village's own record of who talked to whom. Wiped clean every night. */
	private final List<GossipEntry> gossipLog = new ArrayList<>();
	/** The village's own record of what happened. Wiped clean every night. */
	private final List<MemoryEntry> eventLog = new ArrayList<>();
	/** The day's model-invented conversation topics. Wiped clean every night. */
	private final List<String> gossipTopics = new ArrayList<>();
	/**
	 * The complaint keys standing at the last morning reckoning, for {@link Complaints} to
	 * compare against a day later. Persisted so a save reload does not hand the mayor a fresh
	 * day of grace.
	 */
	private final List<String> standingComplaints = new ArrayList<>();

	private Settlement(
		UUID id, String name, GlobalPos bell, Optional<UUID> mayorPlayer,
		String mayorTitle, Optional<UUID> mayorVillager, double food,
		long foundedGameTime, long lastRaidGameTime, boolean lastRaidVictory,
		boolean familySeeded, List<GossipEntry> gossipLog, List<MemoryEntry> eventLog,
		List<String> gossipTopics, List<String> standingComplaints
	) {
		this.id = id;
		this.name = name;
		this.bell = bell;
		this.mayorPlayer = mayorPlayer;
		this.mayorTitle = mayorTitle;
		this.mayorVillager = mayorVillager;
		this.food = food;
		this.foundedGameTime = foundedGameTime;
		this.lastRaidGameTime = lastRaidGameTime;
		this.lastRaidVictory = lastRaidVictory;
		this.familySeeded = familySeeded;
		this.gossipLog.addAll(gossipLog);
		this.eventLog.addAll(eventLog);
		this.gossipTopics.addAll(gossipTopics);
		this.standingComplaints.addAll(standingComplaints);
	}

	public Settlement(UUID id, String name, GlobalPos bell, long gameTime) {
		this(id, name, bell, Optional.empty(), "Mayor", Optional.empty(),
			100.0, gameTime, 0L, false, false, List.of(), List.of(), List.of(), List.of());
	}

	public UUID id() {
		return this.id;
	}

	public String name() {
		return this.name;
	}

	public void setName(String newName) {
		this.name = newName;
	}

	public GlobalPos bell() {
		return this.bell;
	}

	public void setBell(GlobalPos newBell) {
		this.bell = newBell;
	}

	public Optional<UUID> mayorPlayer() {
		return this.mayorPlayer;
	}

	public Optional<UUID> mayorVillager() {
		return this.mayorVillager;
	}

	/** Whether anyone at all currently leads this settlement. */
	public boolean hasMayor() {
		return this.mayorPlayer.isPresent() || this.mayorVillager.isPresent();
	}

	/** What the player mayor is addressed as. */
	public String mayorTitle() {
		return this.mayorTitle;
	}

	/**
	 * Installs a player as mayor. There is at most one mayor per settlement, so this also
	 * clears any villager mayor — a player claiming a village turns its elected leader back into
	 * an ordinary resident.
	 */
	public void setPlayerMayor(UUID playerId, String title) {
		this.mayorPlayer = Optional.of(playerId);
		this.mayorTitle = title;
		this.mayorVillager = Optional.empty();
	}

	public void setVillagerMayor(UUID villagerId) {
		this.mayorVillager = Optional.of(villagerId);
		this.mayorPlayer = Optional.empty();
	}

	public void clearMayor() {
		this.mayorPlayer = Optional.empty();
		this.mayorVillager = Optional.empty();
	}

	/**
	 * Food stock, in points. The ceiling is not fixed: {@link Needs} caps it at what the
	 * village's current farmers can keep stocked.
	 */
	public double food() {
		return this.food;
	}

	public void setFood(double newFood) {
		this.food = Math.max(0.0, newFood);
	}

	public long foundedGameTime() {
		return this.foundedGameTime;
	}

	/**
	 * Game time of the last unemployment complaint, for rate-limiting it. Deliberately not
	 * persisted — at worst a reload lets the village complain once early.
	 */
	private long lastJobComplaint;

	public long lastJobComplaint() {
		return this.lastJobComplaint;
	}

	public void setLastJobComplaint(long gameTime) {
		this.lastJobComplaint = gameTime;
	}

	/** Game time of the last concluded raid, or 0 if this settlement has never been raided. */
	public long lastRaidGameTime() {
		return this.lastRaidGameTime;
	}

	/** Whether the last raid was won. Meaningless while {@link #lastRaidGameTime()} is 0. */
	public boolean lastRaidVictory() {
		return this.lastRaidVictory;
	}

	public void recordRaid(long gameTime, boolean victory) {
		this.lastRaidGameTime = gameTime;
		this.lastRaidVictory = victory;
	}

	public boolean familySeeded() {
		return this.familySeeded;
	}

	public void setFamilySeeded(boolean seeded) {
		this.familySeeded = seeded;
	}

	/**
	 * Notes a conversation on the village's own record. The same piece of news retold — by the
	 * other half of a couple, or the same pair chatting again — replaces its earlier entry
	 * rather than piling up, so the log holds each fact once at its freshest telling. Oldest
	 * entries drop once the cap is hit.
	 */
	public void recordGossip(
		long gameTime, String key, String participantOne, String participantTwo, String summary
	) {
		GossipEntry entry = new GossipEntry(gameTime, key, participantOne, participantTwo, summary);
		this.gossipLog.removeIf(existing -> existing.dedupKey().equals(entry.dedupKey()));
		this.gossipLog.add(entry);
		while (this.gossipLog.size() > Tuning.Settlement.GOSSIP_LOG_LIMIT) {
			this.gossipLog.remove(0);
		}
	}

	public List<GossipEntry> gossipLog() {
		return List.copyOf(this.gossipLog);
	}

	/** Wipes the village's gossip log clean. Called once a day. */
	public void clearGossipLog() {
		this.gossipLog.clear();
	}

	/** The day's conversation topics so far, oldest first. */
	public List<String> gossipTopics() {
		return List.copyOf(this.gossipTopics);
	}

	/**
	 * Adds a model-invented topic to the day's pool. Refused once the pool holds its five, or
	 * if the model produced a topic the village already has — including rewordings: the model
	 * likes to hand back an existing topic with a word changed ("Hedda's plans for the upcoming
	 * hay harvest festival" beside "Hedda's plan for the hay harvest festival"), and an exact
	 * match would let every variant through.
	 */
	public boolean addGossipTopic(String topic) {
		if (this.gossipTopics.size() >= Tuning.Settlement.DAILY_GOSSIP_TOPICS) {
			return false;
		}
		for (String existing : this.gossipTopics) {
			if (similarTopics(existing, topic)) {
				return false;
			}
		}
		this.gossipTopics.add(topic);
		return true;
	}

	/**
	 * Whether two topics are about the same thing: they share at least half of their meaningful
	 * words. Judged on words rather than characters so reordering and filler make no difference.
	 */
	private static boolean similarTopics(String one, String two) {
		Set<String> wordsOne = topicWords(one);
		Set<String> wordsTwo = topicWords(two);
		if (wordsOne.isEmpty() || wordsTwo.isEmpty()) {
			return one.equalsIgnoreCase(two);
		}
		Set<String> shared = new HashSet<>(wordsOne);
		shared.retainAll(wordsTwo);
		int union = wordsOne.size() + wordsTwo.size() - shared.size();
		return (double) shared.size() / union >= 0.5;
	}

	/** Words too common to say what a topic is about. */
	private static final Set<String> TOPIC_STOPWORDS = Set.of(
		"the", "and", "for", "with", "about", "over", "under", "after", "before", "into",
		"from", "his", "her", "their", "this", "that", "these", "those", "new", "old",
		"big", "little", "last", "next", "recent", "upcoming", "village");

	/** The meaningful words of a topic, folding plurals and possessives into their stem. */
	private static Set<String> topicWords(String topic) {
		Set<String> words = new HashSet<>();
		for (String raw : topic.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
			String word = raw.length() > 3 && raw.endsWith("s")
				? raw.substring(0, raw.length() - 1)
				: raw;
			if (word.length() >= 3 && !TOPIC_STOPWORDS.contains(word)) {
				words.add(word);
			}
		}
		return words;
	}

	/** Wipes the day's topics, alongside the gossip log. Called once a day. */
	public void clearGossipTopics() {
		this.gossipTopics.clear();
	}

	/** The complaint keys standing at the last morning reckoning. */
	public List<String> standingComplaints() {
		return List.copyOf(this.standingComplaints);
	}

	/** Replaces the snapshot with this morning's complaint keys. */
	public void setStandingComplaints(List<String> keys) {
		this.standingComplaints.clear();
		this.standingComplaints.addAll(keys);
	}

	/**
	 * Notes something worth the village's own record: a death, a wedding, an engagement.
	 * Recording the same text again refreshes the existing entry rather than stacking a
	 * duplicate — recurring complaints (no work, no food) stay one line however often the
	 * condition re-fires.
	 */
	public void recordEvent(long gameTime, String text) {
		MemoryEntry entry = MemoryEntry.of(gameTime, text);
		this.eventLog.removeIf(existing -> existing.text().equals(entry.text()));
		this.eventLog.add(entry);
		while (this.eventLog.size() > Tuning.Settlement.EVENT_LOG_LIMIT) {
			this.eventLog.remove(0);
		}
	}

	public List<MemoryEntry> eventLog() {
		return List.copyOf(this.eventLog);
	}

	/** Wipes the village's event log clean. Called once a day. */
	public void clearEventLog() {
		this.eventLog.clear();
	}
}
