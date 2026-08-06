package com.talkingvillagers.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.VillagerIdentity;
import com.talkingvillagers.settlement.Needs;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.MemoryEntry;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.VillagerSoul;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/**
 * Builds the prompts sent to Ollama.
 *
 * <p>Written for a small local model, which drives every choice here. The persona card is
 * short and concrete rather than literary; only a handful of relationships and memories are
 * included, chosen by significance instead of dumping everything; and the rules are stated as
 * blunt imperatives, because a 3B model follows those far better than it follows tone.
 *
 * <p>The system prompt is the stable part and the user prompt is the situation, which keeps
 * the two separable if a future version wants to cache personas.
 */
public final class PromptBuilder {
	/** Relationships mentioned in a persona card. More than this crowds out the situation. */
	private static final int RELATIONSHIP_LIMIT = 4;
	/** Recent memories mentioned. */
	private static final int MEMORY_LIMIT = 3;
	/** Recent gossip mentioned. */
	private static final int GOSSIP_LIMIT = 2;
	/** How long a concluded raid stays worth bringing up unprompted. */
	private static final long RAID_NEWS_TICKS = 3L * 24000L;

	private PromptBuilder() {
	}

	/**
	 * The persona card: who this villager is, who matters to them, and how to answer.
	 *
	 * @param level needed to resolve the names of the villagers they have relationships with
	 */
	public static String personaCard(Villager villager, VillagerSoul soul, ServerLevel level) {
		VillagerIdentity identity = soul.identity();
		StringBuilder prompt = new StringBuilder(512);

		prompt.append("You are ").append(identity.fullName())
			.append(", a villager in a Minecraft village. Always reply in exactly ONE short sentence.\n");
		prompt.append("You are ").append(describeAge(villager, identity)).append(".\n");
		prompt.append("Your job: ").append(describeProfession(villager)).append(".\n");
		prompt.append("Your character: ").append(identity.mbti().flavour()).append(".\n");

		if (soul.grieving(level.getGameTime())) {
			prompt.append("You are grieving someone you lost recently and it colours everything you say.\n");
		}

		String people = describeRelationships(soul, level);
		if (!people.isEmpty()) {
			prompt.append("People you know: ").append(people).append(".\n");
		}

		String memories = describeMemories(soul);
		if (!memories.isEmpty()) {
			prompt.append("Recent events: ").append(memories).append(".\n");
		}

		if (!soul.summary().isEmpty()) {
			prompt.append("Older memories: ").append(soul.summary()).append(".\n");
		}

		String gossip = describeGossipHeard(soul);
		if (!gossip.isEmpty()) {
			prompt.append("Gossip you've heard: ").append(gossip).append(".\n");
		}

		String troubles = describeVillageTroubles(soul, level);
		if (!troubles.isEmpty()) {
			prompt.append("On your mind: ").append(troubles).append(".\n");
		}

		prompt.append("Right now: ").append(describeSetting(level)).append(".\n");

		prompt.append("""

			Rules:
			1. Exactly ONE short sentence, always - about 10 words, never more than 16. Never a
			   second sentence, no matter how much there is to say.
			2. Reply as %s, fully in character.
			   Let your personality and mood shape HOW you say it, not just what you say — do
			   not flatten every reply into the same flat tone.
			3. Only talk about your own world: your trade, the people listed above, this village
			   and its troubles, gossip you've heard, and whoever you are talking to. Reach for
			   the concrete over the vague, and never invent people, places or events that are
			   not in the facts above.
			4. You do not have to keep talking forever. If you are busy, said your piece, or do
			   not want to keep talking to this person, end it with a real goodbye (bye,
			   farewell, see you, take care) instead of dragging on.
			5. No asterisks, no stage directions, no emoji, no quotation marks, and never mention
			   being an AI, a model, or a game.
			6. If you do not know something, say so plainly, in character.
			7. Stay consistent. Never contradict the facts above or anything already said in the
			   conversation. Asked the same thing twice, give the same answer. You are only ever
			   %s — never write the other person's lines."""
			.formatted(identity.firstName(), identity.firstName()));

		return prompt.toString();
	}

	/** The one or two most recent pieces of gossip this villager has heard or told. */
	private static String describeGossipHeard(VillagerSoul soul) {
		List<MemoryEntry> gossip = soul.gossipHeard();
		if (gossip.isEmpty()) {
			return "";
		}
		int from = Math.max(0, gossip.size() - GOSSIP_LIMIT);
		List<String> recent = new ArrayList<>();
		for (MemoryEntry entry : gossip.subList(from, gossip.size())) {
			recent.add(entry.text());
		}
		return String.join("; ", recent);
	}

	/**
	 * Whatever is actually wrong (or recently was) in this villager's own settlement — hunger,
	 * or a raid within the last few days — so a villager has real complaints to reach for instead
	 * of generic small talk. Empty for a village that has nothing currently worth complaining
	 * about.
	 */
	private static String describeVillageTroubles(VillagerSoul soul, ServerLevel level) {
		Optional<Settlement> home = soul.settlement().flatMap(id -> Settlements.byId(id, level));
		if (home.isEmpty()) {
			return "";
		}
		Settlement settlement = home.get();
		List<String> troubles = new ArrayList<>();

		if (Needs.isStarving(settlement)) {
			troubles.add("the village is starving");
		} else if (Needs.isShort(settlement, Settlements.residents(settlement, level))) {
			troubles.add("food is running short");
		}

		if (settlement.lastRaidGameTime() > 0
			&& level.getGameTime() - settlement.lastRaidGameTime() <= RAID_NEWS_TICKS) {
			troubles.add(settlement.lastRaidVictory()
				? "the village just fought off a raid"
				: "the village was just raided");
		}

		return String.join("; ", troubles);
	}

	/**
	 * A one-line grounding of the moment: time of day and weather. Small, but it is the
	 * difference between a villager who could be anywhere and one standing in this village right
	 * now, and it gives the model something concrete to reach for instead of generic small talk.
	 */
	private static String describeSetting(ServerLevel level) {
		String time = describeTimeOfDay(level.getOverworldClockTime() % 24000L);
		String weather = level.isThundering() ? "a thunderstorm"
			: level.isRaining() ? "raining"
			: "clear";
		return time + ", weather " + weather;
	}

	private static String describeTimeOfDay(long ticks) {
		if (ticks < 1000) {
			return "early morning";
		}
		if (ticks < 6000) {
			return "morning";
		}
		if (ticks < 9000) {
			return "midday";
		}
		if (ticks < 12000) {
			return "afternoon";
		}
		if (ticks < 13500) {
			return "evening";
		}
		if (ticks < 18000) {
			return "night";
		}
		if (ticks < 22000) {
			return "late night";
		}
		return "just before dawn";
	}

	/**
	 * The situation prompt for a player speaking to this villager.
	 *
	 * <p>{@code transcript} must not include the line being replied to — it is quoted separately
	 * below, and a small model that sees the same line twice tends to answer it twice. Lines are
	 * labelled with real names on both sides, with a reminder of which name is the model's own:
	 * an earlier version labelled the player's lines "You:", which read to the model as its own
	 * words and produced replies agreeing with things it never said.
	 */
	public static String playerSaid(
		String villagerName, String playerName, String message, List<String> transcript
	) {
		StringBuilder prompt = new StringBuilder(256);
		if (!transcript.isEmpty()) {
			prompt.append("The conversation so far, oldest first (")
				.append(villagerName).append(" is you):\n");
			for (String line : transcript) {
				prompt.append(line).append('\n');
			}
			prompt.append('\n');
		}
		prompt.append(playerName).append(" now says to you: \"").append(message).append("\"\n");
		prompt.append("Reply to ").append(playerName).append(" as ").append(villagerName)
			.append(", one sentence.");
		return prompt.toString();
	}

	/**
	 * The prompt asking the model to compress a finished player conversation into one line fit
	 * for the villager's memory and the village gossip log. The output shape is pinned hard —
	 * length, tense, person — because a small model drifts into commentary otherwise.
	 */
	public static String summariseConversation(
		String villagerName, String playerName, List<String> transcript
	) {
		StringBuilder prompt = new StringBuilder(256);
		prompt.append(villagerName).append(", a villager, talked with ")
			.append(playerName).append(", a visitor:\n");
		for (String line : transcript) {
			prompt.append(line).append('\n');
		}
		prompt.append("\nSummarise what was said in ONE line: under 18 words, past tense, third ")
			.append("person, mentioning ").append(playerName).append(" by name.\n")
			.append("Keep any promises, requests or facts. Use only names that appear above. ")
			.append("No quotation marks, no preamble.");
		return prompt.toString();
	}

	/**
	 * The prompt asking the model to judge how the player treated the villager in a finished
	 * conversation. One word out, with the meaning of each option spelled out — a small model
	 * grades far more consistently against stated criteria than against its own taste, and
	 * anything it answers other than the two extremes is read as neutral.
	 */
	public static String judgePlayerTone(
		String villagerName, String playerName, List<String> transcript
	) {
		StringBuilder prompt = new StringBuilder(256);
		prompt.append(villagerName).append(", a villager, talked with ")
			.append(playerName).append(", a visitor:\n");
		for (String line : transcript) {
			prompt.append(line).append('\n');
		}
		prompt.append("\nHow did ").append(playerName).append(" treat ").append(villagerName)
			.append("?\n")
			.append("KIND means compliments, warmth, gratitude or offers of help. ")
			.append("RUDE means insults, threats, mockery or contempt. ")
			.append("Ordinary small talk is NEUTRAL.\n")
			.append("Answer with exactly one word: KIND, RUDE or NEUTRAL.");
		return prompt.toString();
	}

	/**
	 * The prompt asking the model to invent one of a village's daily conversation topics,
	 * grounded in the two villagers about to discuss it: their trades and the people in their
	 * lives. The grounding is what keeps the pool about <em>this</em> village — its jobs, its
	 * neighbours, its feuds — instead of generic fantasy filler, and the taken topics are
	 * listed back because without that a small model converges on the weather five times out
	 * of five.
	 */
	public static String gossipTopicPrompt(
		Villager speaker, VillagerSoul speakerSoul, Villager listener, VillagerSoul listenerSoul,
		List<String> taken, List<String> visitorTalk, ServerLevel level
	) {
		String speakerName = speakerSoul.identity().firstName();
		String listenerName = listenerSoul.identity().firstName();

		StringBuilder prompt = new StringBuilder(384);
		prompt.append("Two villagers in a small fantasy village are about to chat.\n");
		prompt.append(speakerName).append(" is a ").append(professionNoun(speaker))
			.append("; ").append(listenerName).append(" is a ").append(professionNoun(listener))
			.append(".\n");
		String speakerPeople = describeRelationships(speakerSoul, level);
		if (!speakerPeople.isEmpty()) {
			prompt.append(speakerName).append(" knows: ").append(speakerPeople).append(".\n");
		}
		String listenerPeople = describeRelationships(listenerSoul, level);
		if (!listenerPeople.isEmpty()) {
			prompt.append(listenerName).append(" knows: ").append(listenerPeople).append(".\n");
		}
		if (!visitorTalk.isEmpty()) {
			prompt.append("Visitors have been talking with the villagers today: ")
				.append(String.join("; ", visitorTalk)).append(".\n");
		}
		prompt.append("Right now it is ").append(describeSetting(level)).append(".\n");
		prompt.append("Invent ONE topic these two would gossip about, drawn from their own ")
			.append("lives: their work, the people named above, what the visitors said, or ")
			.append("small village happenings that touch them. Nothing epic, nobody and ")
			.append("nowhere that is not named above.\n");
		if (!taken.isEmpty()) {
			prompt.append("Already being talked about today: ")
				.append(String.join("; ", taken))
				.append(". Your topic must be about something else entirely — do not repeat, ")
				.append("reword or add detail to any of these.\n");
		}
		prompt.append("Answer with only the topic: a phrase of 3 to 10 words, lowercase ")
			.append("except names, not a full sentence.");
		return prompt.toString();
	}

	/**
	 * The situation prompt for a villager opening a conversation with a player.
	 *
	 * <p>An empty {@code standing} is what marks a genuine stranger, so a villager who has spoken
	 * to this player before is told so explicitly and pointed at the memories in their persona
	 * card. Without that the model reaches for "nice to meet you" every single time, which is
	 * what makes a villager feel like they have never seen you.
	 */
	public static String greetPlayer(String playerName, Optional<Relationship> standing) {
		if (standing.isEmpty()) {
			return "You have never met " + playerName + " before.\nGreet this stranger in your own "
				+ "words, briefly.";
		}

		String feeling = switch (standing.get().label()) {
			case SWORN_ENEMY -> "You hate " + playerName + " and will never forgive them.";
			case ENEMY -> "You distrust " + playerName + ".";
			case FRIEND -> "You are fond of " + playerName + ".";
			case BEST_FRIEND -> "You think the world of " + playerName + ".";
			default -> "You know " + playerName + " a little.";
		};
		return feeling + "\nYou have spoken with " + playerName + " before; anything they told you "
			+ "is in your memories above.\nGreet " + playerName + " again in your own words, "
			+ "briefly, as someone you already know.";
	}

	private static String describeAge(Villager villager, VillagerIdentity identity) {
		String gender = identity.genderIdentity().name().toLowerCase();
		if (villager.isBaby()) {
			return "a " + gender + " child";
		}
		String orientation = identity.sexuality()
			.map(sexuality -> ", " + sexuality.getSerializedName())
			.orElse("");
		return "an adult " + gender + orientation;
	}

	/**
	 * The villager's trade as a third-person noun phrase, for prompts that talk <em>about</em>
	 * them rather than to them — "Merrick is a blacksmith".
	 */
	private static String professionNoun(Villager villager) {
		Holder<VillagerProfession> profession = villager.getVillagerData().profession();
		if (profession.is(VillagerProfession.NONE)) {
			return "villager with no trade yet";
		}
		if (profession.is(VillagerProfession.NITWIT)) {
			return "nitwit";
		}
		return profession.unwrapKey()
			.map(key -> key.identifier().getPath())
			.orElse("villager")
			.replace('_', ' ');
	}

	/**
	 * The villager's trade as a plain word. Registry ids look like {@code minecraft:farmer},
	 * and a jobless villager reads better as looking for work than as "none".
	 */
	private static String describeProfession(Villager villager) {
		Holder<VillagerProfession> profession = villager.getVillagerData().profession();
		if (profession.is(VillagerProfession.NONE)) {
			return "none yet, and you would like one";
		}
		if (profession.is(VillagerProfession.NITWIT)) {
			return "nitwit; you have never been much use at a trade";
		}

		String name = profession.unwrapKey()
			.map(key -> key.identifier().getPath())
			.orElse("villager");
		return name.replace('_', ' ');
	}

	/**
	 * The few relationships worth spending prompt budget on: family and romance first, then
	 * whoever is felt most strongly about.
	 */
	private static String describeRelationships(VillagerSoul soul, ServerLevel level) {
		Map<UUID, Relationship> relationships = soul.relationships();
		if (relationships.isEmpty()) {
			return "";
		}

		List<Relationship> ranked = new ArrayList<>(relationships.values());
		ranked.sort(Comparator
			.comparingInt((Relationship relationship) -> relationship.bond().family()
				|| relationship.bond().romantic() ? 0 : 1)
			.thenComparingInt(relationship -> -Math.abs(relationship.meter() - Relationship.NEUTRAL)));

		List<String> described = new ArrayList<>();
		for (Relationship relationship : ranked) {
			if (described.size() >= RELATIONSHIP_LIMIT) {
				break;
			}
			Optional<String> name = nameOf(relationship, level);
			if (name.isEmpty()) {
				continue;
			}
			described.add(name.get() + " (" + relationship.label().description() + ")");
		}
		return String.join(", ", described);
	}

	/**
	 * The name behind a relationship, whether it belongs to a villager or to a player.
	 *
	 * <p>Players are looked up first and by name rather than through the entity index, because a
	 * villager should list the player they know alongside their neighbours — being told "People
	 * you know: Alys (sister), Lewis (friend)" is what lets the model recognise a returning
	 * player instead of treating them as a new face.
	 *
	 * <p>Falls back to {@link Relationship#memorialName()} once the target can no longer be
	 * resolved live — the only such case today is a dead spouse, whose name would otherwise be
	 * unrecoverable the moment their entity is gone.
	 *
	 * <p>Empty for anyone unloaded or logged out with no memorial name. They are simply left out
	 * of the card rather than named as a stranger: an unresolvable id has no name to give.
	 */
	private static Optional<String> nameOf(Relationship relationship, ServerLevel level) {
		UUID id = relationship.target();
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
		if (player != null) {
			return Optional.of(player.getName().getString());
		}
		if (level.getEntityInAnyDimension(id) instanceof Villager villager) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null) {
				return Optional.of(soul.identity().firstName());
			}
		}
		return relationship.memorialName().isEmpty()
			? Optional.empty()
			: Optional.of(relationship.memorialName());
	}

	private static String describeMemories(VillagerSoul soul) {
		List<MemoryEntry> memories = soul.memories();
		if (memories.isEmpty()) {
			return "";
		}
		int from = Math.max(0, memories.size() - MEMORY_LIMIT);
		List<String> recent = new ArrayList<>();
		for (MemoryEntry memory : memories.subList(from, memories.size())) {
			recent.add(memory.text());
		}
		return String.join("; ", recent);
	}
}
