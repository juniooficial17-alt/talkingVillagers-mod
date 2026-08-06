package com.talkingvillagers.gossip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.conversation.ConversationManager;
import com.talkingvillagers.conversation.SpeechBubbles;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.VillagerSouls;
import com.talkingvillagers.llm.LlmPriority;
import com.talkingvillagers.llm.LlmRequest;
import com.talkingvillagers.llm.PromptBuilder;
import com.talkingvillagers.settlement.GossipEntry;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Starts conversations between villagers, and lets villagers walk up to players.
 *
 * <p>Villager-to-villager conversations happen whenever both parties are loaded — no player
 * needs to be watching, which is what makes a village feel like it has a life of its own
 * rather than performing for an audience.
 *
 * <p>What they talk about is the village's daily topic pool: the model invents up to
 * {@link Tuning.Settlement#DAILY_GOSSIP_TOPICS} topics a day, and conversations mostly reuse
 * one — always, once the pool is full; all but one in {@link #NEW_TOPIC_CHANCE_IN} while it
 * is still filling. The pool resets with the village's logs each night, so every day has its
 * own handful of things the whole village is talking about.
 */
public final class GossipScheduler {
	/** How close two villagers must be to strike up a conversation, in blocks. */
	private static final double PAIRING_RADIUS = 5.0;

	/**
	 * How far a finished conversation can move the pair's mutual regard, either way. Each one
	 * ends on a uniform roll between minus and plus this — most chats leave little mark, but a
	 * village whose relationships only ever warmed would have no feuds, and no story.
	 */
	private static final int CONVERSATION_SWING = 8;

	/**
	 * While the day's pool is still filling, one conversation in this many commissions a new
	 * topic; the rest reuse an existing one, so the pool fills gradually over the day rather
	 * than in its first few minutes.
	 */
	private static final int NEW_TOPIC_CHANCE_IN = 3;

	/**
	 * What pairs fall back to talking about when there is no pool to draw from — a villager
	 * outside any settlement, or the model down before the day's first topic exists. Canned so
	 * a village is never struck mute by an Ollama outage.
	 */
	private static final List<String> FALLBACK_TOPICS = List.of(
		"the weather", "the day's work", "nothing much", "the price of everything");
	/**
	 * How close a player must be for a villager to consider approaching them.
	 *
	 * <p>Capped at {@code conversation.max_distance} at the point of use: the conversation opens
	 * as the villager sets off walking, so spotting a player further away than the session is
	 * allowed to stretch would open one and end it on the same tick for being too far apart.
	 */
	private static final double APPROACH_RADIUS = 8.0;
	/** Minimum ticks between one villager approaching players, so nobody is pestered. */
	private static final int APPROACH_COOLDOWN_TICKS = 2400;
	/** Base chance per scheduler run that an eligible villager approaches a player. */
	private static final double APPROACH_BASE_CHANCE = 0.04;

	/** Game time each villager last held a conversation, keyed by villager. */
	private final Map<UUID, Long> lastConversation = new HashMap<>();
	/**
	 * Topic generations in flight, keyed by settlement. Counted against the daily cap so a
	 * burst of conversations cannot commission more topics than the pool has room for.
	 */
	private final Map<UUID, Integer> pendingTopics = new HashMap<>();
	/** Conversations happening right now, held still and facing each other until they expire. */
	private final List<ActiveChat> talking = new ArrayList<>();
	/** Game time each player was last approached, so several villagers don't mob them. */
	private final Map<UUID, Long> lastApproach = new HashMap<>();

	private final SpeechBubbles bubbles;

	public GossipScheduler(SpeechBubbles bubbles) {
		this.bubbles = bubbles;
	}

	/**
	 * Keeps villagers who are mid-conversation standing still and facing each other.
	 *
	 * <p>Runs every tick, unlike the scheduling pass, because the villager's own brain sets a
	 * fresh walk target constantly — clearing it once would not stop them wandering off in the
	 * middle of the exchange.
	 */
	public void tickConversations() {
		if (this.talking.isEmpty()) {
			return;
		}

		Iterator<ActiveChat> iterator = this.talking.iterator();
		while (iterator.hasNext()) {
			ActiveChat chat = iterator.next();
			if (!chat.stillValid()) {
				iterator.remove();
				conclude(chat);
				continue;
			}

			ConversationManager.holdAttention(chat.one, chat.two);
			ConversationManager.holdAttention(chat.two, chat.one);
		}
	}

	/**
	 * Settles how a finished conversation left the pair feeling about each other: a roll
	 * between {@code -CONVERSATION_SWING} and {@code +CONVERSATION_SWING}, applied to both
	 * sides equally — they shared the conversation, so they share the outcome. Particles
	 * announce which way it went: happy for a chat that went well, angry for one that soured,
	 * nothing for one that left no impression.
	 */
	private void conclude(ActiveChat chat) {
		if (!(chat.one.level() instanceof ServerLevel level)) {
			return;
		}
		// A chat cut short by a death or an unload settles nothing.
		if (!chat.one.isAlive() || chat.one.isRemoved()
			|| !chat.two.isAlive() || chat.two.isRemoved() || chat.two.level() != level) {
			return;
		}

		int delta = level.getRandom().nextInt(CONVERSATION_SWING * 2 + 1) - CONVERSATION_SWING;
		if (delta == 0) {
			return;
		}

		long gameTime = level.getGameTime();
		VillagerSouls.ensureSoul(chat.one, level)
			.adjustRelationship(chat.two.getUUID(), delta, gameTime);
		VillagerSouls.ensureSoul(chat.two, level)
			.adjustRelationship(chat.one.getUUID(), delta, gameTime);

		if (!TalkingVillagers.config().conversation.particlesWhileTalking) {
			return;
		}
		for (Villager participant : List.of(chat.one, chat.two)) {
			level.sendParticles(
				delta > 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
				participant.getX(), participant.getEyeY() + 0.5, participant.getZ(),
				3, 0.3, 0.2, 0.3, 0.0);
		}
	}

	/**
	 * Runs one scheduling pass over a level. Called from the mod's periodic sweep rather than
	 * every tick, since villagers gossiping a few times a minute is plenty.
	 */
	public void tick(ServerLevel level) {
		TalkingVillagersConfig config = TalkingVillagers.config();
		List<Villager> candidates = eligible(level, config);
		if (candidates.isEmpty()) {
			return;
		}

		RandomSource random = level.getRandom();
		int started = 0;

		for (Villager speaker : candidates) {
			if (started >= Tuning.Conversation.VILLAGER_TO_VILLAGER_MAX_PER_PASS) {
				break;
			}
			if (!isEligible(speaker, level, config)) {
				continue;
			}

			if (maybeApproachPlayer(level, speaker, config, random)) {
				started++;
				continue;
			}

			Optional<Villager> partner = findPartner(speaker, level, config);
			if (partner.isEmpty()) {
				continue;
			}

			converse(level, speaker, partner.get(), random);
			started++;
		}

		pruneCooldowns(level);
	}

	/** Villagers that could plausibly start something this pass. */
	private List<Villager> eligible(ServerLevel level, TalkingVillagersConfig config) {
		List<Villager> out = new ArrayList<>();
		for (Villager villager : TalkingVillagers.tracker().loaded(level)) {
			if (isEligible(villager, level, config)) {
				out.add(villager);
			}
		}
		return out;
	}

	private boolean isEligible(Villager villager, ServerLevel level, TalkingVillagersConfig config) {
		if (!villager.isAlive() || villager.isBaby() || villager.isSleeping()) {
			return false;
		}
		if (TalkingVillagers.conversations().isTalkingToPlayer(villager.getUUID())) {
			return false;
		}
		if (villager.getAttached(Attachments.SOUL) == null) {
			return false;
		}

		Long last = this.lastConversation.get(villager.getUUID());
		return last == null
			|| level.getGameTime() - last >= Tuning.Conversation.VILLAGER_TO_VILLAGER_INTERVAL_TICKS;
	}

	private Optional<Villager> findPartner(Villager speaker, ServerLevel level, TalkingVillagersConfig config) {
		return level.getEntitiesOfClass(
				Villager.class,
				speaker.getBoundingBox().inflate(PAIRING_RADIUS),
				candidate -> candidate != speaker && isEligible(candidate, level, config))
			.stream()
			.findFirst();
	}

	/** Runs one villager-to-villager exchange: picks or commissions a topic and discusses it. */
	private void converse(ServerLevel level, Villager speaker, Villager listener, RandomSource random) {
		VillagerSoul speakerSoul = VillagerSouls.ensureSoul(speaker, level);
		long gameTime = level.getGameTime();

		this.lastConversation.put(speaker.getUUID(), gameTime);
		this.lastConversation.put(listener.getUUID(), gameTime);

		// Both stop and face each other for the duration, rather than exchanging a line in
		// passing. Tracked so tickConversations can keep them still every tick.
		this.talking.add(new ActiveChat(speaker, listener,
			gameTime + Tuning.Conversation.VILLAGER_TO_VILLAGER_DURATION_TICKS));
		BehaviorUtils.lockGazeAndWalkToEachOther(speaker, listener, 0.5F, 2);

		Optional<Settlement> home = speakerSoul.settlement()
			.flatMap(id -> Settlements.byId(id, level));
		if (home.isEmpty()) {
			// A villager outside any village has no shared pool to draw from.
			discuss(level, speaker, listener,
				FALLBACK_TOPICS.get(random.nextInt(FALLBACK_TOPICS.size())));
			return;
		}

		Settlement settlement = home.get();
		List<String> topics = settlement.gossipTopics();
		int pending = this.pendingTopics.getOrDefault(settlement.id(), 0);
		boolean capReached = topics.size() + pending >= Tuning.Settlement.DAILY_GOSSIP_TOPICS;

		if (!topics.isEmpty() && (capReached || random.nextInt(NEW_TOPIC_CHANCE_IN) != 0)) {
			discuss(level, speaker, listener, topics.get(random.nextInt(topics.size())));
		} else if (!capReached && TalkingVillagers.ollama().healthy()) {
			requestTopic(level, settlement, speaker, listener);
		} else if (!topics.isEmpty()) {
			// Room in the pool but the model is down: reuse what the day already has.
			discuss(level, speaker, listener, topics.get(random.nextInt(topics.size())));
		} else {
			discuss(level, speaker, listener,
				FALLBACK_TOPICS.get(random.nextInt(FALLBACK_TOPICS.size())));
		}
	}

	/**
	 * Has the pair visibly talk a topic over, with both remembering what was discussed —
	 * which is how the topic reaches their persona cards when a player speaks to them later.
	 * How the chat leaves them feeling about each other is rolled in {@link #conclude} once
	 * it ends, not decided here.
	 */
	private void discuss(ServerLevel level, Villager speaker, Villager listener, String topic) {
		VillagerSoul speakerSoul = VillagerSouls.ensureSoul(speaker, level);
		VillagerSoul listenerSoul = VillagerSouls.ensureSoul(listener, level);
		long gameTime = level.getGameTime();

		speakerSoul.rememberGossip(gameTime, "talked about " + topic);
		listenerSoul.rememberGossip(gameTime, "talked about " + topic);

		String speakerName = speakerSoul.identity().firstName();
		String listenerName = listenerSoul.identity().firstName();
		// The summary is the bare topic — the village book lists what is being talked about,
		// not who was talking — and the key is the topic too, so each of the day's topics
		// fills one line of the book no matter how many pairs discuss it.
		Settlements.recordVillageGossip(speakerSoul, level, gameTime, "topic:" + topic,
			speakerName, listenerName, topic);

		present(speaker, listener,
			speakerName + " and " + listenerName + " are talking about " + topic);
	}

	/**
	 * Shows the conversation to any players nearby as a summary line. No particles while they
	 * talk — those are saved for {@link #conclude}, where they announce how the chat went.
	 */
	private void present(Villager speaker, Villager listener, String line) {
		this.bubbles.showBetween(speaker, listener, line,
			Tuning.Conversation.VILLAGER_TO_VILLAGER_DURATION_TICKS);
	}

	/**
	 * Commissions the day's next topic from the model. The pair keep facing each other while
	 * it thinks — the reply usually lands well inside the conversation's duration — and the
	 * topic joins the village pool either way, so later conversations reuse it even if this
	 * pair have parted by the time it arrives.
	 */
	private void requestTopic(
		ServerLevel level, Settlement settlement, Villager speaker, Villager listener
	) {
		UUID settlementId = settlement.id();
		this.pendingTopics.merge(settlementId, 1, Integer::sum);

		VillagerSoul speakerSoul = VillagerSouls.ensureSoul(speaker, level);
		VillagerSoul listenerSoul = VillagerSouls.ensureSoul(listener, level);
		TalkingVillagers.ollama().submit(new LlmRequest(
			LlmPriority.BACKGROUND,
			"You invent gossip topics for a fantasy village game. "
				+ "Answer with one short phrase only.",
			PromptBuilder.gossipTopicPrompt(
				speaker, speakerSoul, listener, listenerSoul,
				settlement.gossipTopics(), visitorTalk(settlement), level),
			reply -> {
				this.pendingTopics.merge(settlementId, -1, Integer::sum);
				String topic = normaliseTopic(reply);
				if (topic.isEmpty()) {
					return;
				}
				if (!Settlements.recordGossipTopic(settlement, level, topic)) {
					// The model reworded a topic the village already has. Talk about the
					// established version instead, so the book does not fill up with variants
					// of the same thing — the pool cannot be empty here, since rejection means
					// it either matched an existing topic or was already full.
					List<String> pool = settlement.gossipTopics();
					if (pool.isEmpty()) {
						return;
					}
					topic = pool.get(level.getRandom().nextInt(pool.size()));
				}

				// Either may have wandered off or unloaded while the model was thinking; the
				// topic still joined the pool for the next pair.
				boolean bothPresent = speaker.isAlive() && !speaker.isRemoved()
					&& speaker.level() == level
					&& listener.isAlive() && !listener.isRemoved() && listener.level() == level;
				if (bothPresent) {
					discuss(level, speaker, listener, topic);
				}
			},
			() -> this.pendingTopics.merge(settlementId, -1, Integer::sum)));
	}

	/**
	 * What players have been talking about with this village's residents today — their
	 * conversations land in the gossip log under a {@code player-chat:} key, and feeding them
	 * to the topic generator is what lets the village end up gossiping about the player.
	 */
	private static List<String> visitorTalk(Settlement settlement) {
		List<String> talk = new ArrayList<>();
		for (GossipEntry entry : settlement.gossipLog()) {
			if (entry.key().startsWith("player-chat:") && !entry.summary().isEmpty()) {
				talk.add(entry.summary());
			}
		}
		// Only the freshest few: this seasons the prompt, it should not swamp it.
		return talk.size() <= 3 ? talk : talk.subList(talk.size() - 3, talk.size());
	}

	/**
	 * Trims model scaffolding off a generated topic and rejects anything unusable, returning
	 * empty rather than letting a malformed topic into the pool for the whole day. The case
	 * is left as the model wrote it — a topic may legitimately open with a villager's name.
	 */
	private static String normaliseTopic(String reply) {
		String topic = reply.strip();
		while (topic.endsWith(".") || topic.endsWith("!")) {
			topic = topic.substring(0, topic.length() - 1).stripTrailing();
		}
		return topic.length() > 60 ? "" : topic;
	}

	/**
	 * Occasionally has a villager walk over and start talking to a nearby player.
	 *
	 * @return true if an approach was started
	 */
	private boolean maybeApproachPlayer(
		ServerLevel level, Villager villager, TalkingVillagersConfig config, RandomSource random
	) {
		VillagerSoul soul = villager.getAttached(Attachments.SOUL);
		if (soul == null) {
			return false;
		}

		double chance = APPROACH_BASE_CHANCE * soul.identity().mbti().chattiness();
		if (random.nextDouble() >= chance) {
			return false;
		}

		long gameTime = level.getGameTime();
		double radius = Math.min(APPROACH_RADIUS, config.conversation.maxDistance);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(villager) > radius * radius) {
				continue;
			}
			if (TalkingVillagers.conversations().inConversation(player.getUUID())) {
				continue;
			}
			Long last = this.lastApproach.get(player.getUUID());
			if (last != null && gameTime - last < APPROACH_COOLDOWN_TICKS) {
				continue;
			}

			this.lastApproach.put(player.getUUID(), gameTime);
			this.lastConversation.put(villager.getUUID(), gameTime);
			BehaviorUtils.setWalkAndLookTargetMemories(villager, player, 0.5F, 2);
			TalkingVillagers.conversations().start(player, villager, true);
			return true;
		}
		return false;
	}

	/**
	 * Drops cooldown entries that can no longer matter, so these maps cannot grow without
	 * bound in a long-running world with many villagers passing through.
	 */
	private void pruneCooldowns(ServerLevel level) {
		long gameTime = level.getGameTime();
		this.lastConversation.entrySet().removeIf(entry -> gameTime - entry.getValue() > 24000L);
		this.lastApproach.entrySet().removeIf(
			entry -> gameTime - entry.getValue() > APPROACH_COOLDOWN_TICKS * 4L);
	}

	public void clear() {
		this.lastConversation.clear();
		this.lastApproach.clear();
		this.talking.clear();
		this.pendingTopics.clear();
	}

	/**
	 * A conversation in progress between two villagers.
	 *
	 * <p>Holds the entities directly rather than their UUIDs: these live for a few seconds at
	 * most and are dropped as soon as either participant is gone, so there is nothing to leak.
	 */
	private static final class ActiveChat {
		private final Villager one;
		private final Villager two;
		private final long endsAtGameTime;

		private ActiveChat(Villager one, Villager two, long endsAtGameTime) {
			this.one = one;
			this.two = two;
			this.endsAtGameTime = endsAtGameTime;
		}

		private boolean stillValid() {
			if (!this.one.isAlive() || this.one.isRemoved() || !this.two.isAlive() || this.two.isRemoved()) {
				return false;
			}
			if (!(this.one.level() instanceof ServerLevel level)) {
				return false;
			}
			// A player pulling one of them into conversation takes precedence.
			if (TalkingVillagers.conversations().isTalkingToPlayer(this.one.getUUID())
				|| TalkingVillagers.conversations().isTalkingToPlayer(this.two.getUUID())) {
				return false;
			}
			return level.getGameTime() < this.endsAtGameTime;
		}
	}
}
