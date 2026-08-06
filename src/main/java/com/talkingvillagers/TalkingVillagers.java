package com.talkingvillagers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.talkingvillagers.book.Books;
import com.talkingvillagers.book.RenamePrompts;
import com.talkingvillagers.book.VillageBook;
import com.talkingvillagers.book.VillagerBook;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.conversation.ConversationManager;
import com.talkingvillagers.conversation.SpeechBubbles;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.data.VillagerTracker;
import com.talkingvillagers.family.Births;
import com.talkingvillagers.family.Courtship;
import com.talkingvillagers.family.FamilySeeding;
import com.talkingvillagers.family.Grief;
import com.talkingvillagers.family.Weddings;
import com.talkingvillagers.gossip.GossipScheduler;
import com.talkingvillagers.identity.VillagerSouls;
import com.talkingvillagers.llm.OllamaService;
import com.talkingvillagers.settlement.Complaints;
import com.talkingvillagers.settlement.Housing;
import com.talkingvillagers.settlement.Jobs;
import com.talkingvillagers.settlement.Needs;
import com.talkingvillagers.settlement.Settlement;
import com.talkingvillagers.settlement.SettlementData;
import com.talkingvillagers.settlement.SettlementPresence;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.VillagerSoul;
import com.talkingvillagers.ui.Notify;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BellBlock;

/**
 * Entry point for Talking Villagers.
 *
 * <p>The mod is server-side only. Everything a vanilla client sees is produced through vanilla
 * mechanisms the server already drives — entity custom names, Text Display entities, particles
 * and chat — so players join with no mods installed.
 */
public class TalkingVillagers implements ModInitializer {
	public static final String MOD_ID = "talkingvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * How often the periodic sweep over loaded villagers runs, in ticks. Aging, courtship and
	 * gossip do not need per-tick resolution, and this keeps the per-tick cost near zero in
	 * worlds with many villagers.
	 */
	public static final int SWEEP_INTERVAL_TICKS = 40;

	private static volatile TalkingVillagersConfig config = TalkingVillagersConfig.defaults();
	private static final VillagerTracker TRACKER = new VillagerTracker();
	private static final SpeechBubbles BUBBLES = new SpeechBubbles();
	private static final ConversationManager CONVERSATIONS = new ConversationManager(BUBBLES);
	private static final GossipScheduler GOSSIP = new GossipScheduler(BUBBLES);
	private static final SettlementPresence PRESENCE = new SettlementPresence();
	private static final RenamePrompts RENAME_PROMPTS = new RenamePrompts();
	/**
	 * Work deliberately delayed to the next server tick rather than run on the spot, for two
	 * unrelated reasons:
	 *
	 * <p>Entity work that would otherwise run from inside vanilla's own per-chunk entity iteration
	 * ({@code PersistentEntitySectionManager.updateChunkStatus}, reached from both
	 * {@code ENTITY_LOAD} and {@code ENTITY_UNLOAD}). Discarding — or spawning — an entity from
	 * inside that iteration structurally modifies the very list vanilla is walking and throws a
	 * {@code ConcurrentModificationException}, intermittently: only when the entity being touched
	 * shares a chunk section with other entities loading or unloading at that same moment. Two
	 * known triggers: a leftover speech bubble noticed on load, and a live one whose speaker just
	 * unloaded mid-conversation.
	 *
	 * <p>Handing over a book from a <em>block</em> right-click ({@code UseBlockCallback}). A
	 * vanilla client sneak-clicking a block with an item sends a second, separate "use held
	 * item" packet alongside the block-use one — entities do not get this second packet, which
	 * is why this is only a block problem. If the book is swapped for its written copy in the
	 * same tick, that second packet is processed against the item now sitting in the player's
	 * hand rather than the one they actually clicked with, and a written book's own vanilla
	 * {@code use()} opens it right back up. Waiting a tick lets that stray packet resolve
	 * against the original, unwritten book — which has no {@code use()} of its own — first.
	 */
	private static final java.util.ArrayDeque<Runnable> NEXT_TICK_WORK = new java.util.ArrayDeque<>();
	/**
	 * Players who were just handed a book from a block or entity interaction, mapped to the game
	 * time the suppression expires.
	 *
	 * <p>The client's sneak-click always fires a second, separate "use held item" packet right
	 * alongside the block- or entity-use one (see the {@code NEXT_TICK_WORK} comment). That packet
	 * reaches the server before the deferred {@code give()} above has swapped the book, so it is
	 * still holding whatever book the player had a moment ago — old or blank — and a written
	 * book's own vanilla {@code use()} would pop it open right there. This is what actually
	 * suppresses that stray open, rather than relying on the swap happening fast enough: it does
	 * not matter which book is in hand when the packet arrives, because {@code UseItemCallback}
	 * below swallows it outright while a player is in this set.
	 */
	private static final Map<UUID, Long> SUPPRESS_BOOK_OPEN_UNTIL = new java.util.HashMap<>();
	/** How many ticks the book-open suppression stays armed for. */
	private static final int SUPPRESS_BOOK_OPEN_TICKS = 2;
	private static OllamaService ollama;
	private static volatile MinecraftServer server;

	private int tickCounter;

	/**
	 * The active configuration. Replaced wholesale on reload, so a caller that reads several
	 * fields always sees one consistent snapshot.
	 */
	public static TalkingVillagersConfig config() {
		return config;
	}

	public static VillagerTracker tracker() {
		return TRACKER;
	}

	public static SpeechBubbles bubbles() {
		return BUBBLES;
	}

	public static ConversationManager conversations() {
		return CONVERSATIONS;
	}

	public static GossipScheduler gossip() {
		return GOSSIP;
	}

	public static RenamePrompts renamePrompts() {
		return RENAME_PROMPTS;
	}

	public static OllamaService ollama() {
		return ollama;
	}

	/** The running server, or null when none is running. */
	public static MinecraftServer server() {
		return server;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		config = TalkingVillagersConfig.load(FabricLoader.getInstance().getConfigDir());
		Attachments.register();
		ollama = new OllamaService();

		registerServerLifecycle();
		registerVillagerLifecycle();
		registerPlayerInteraction();

		LOGGER.info("Talking Villagers initialised (server-side only)");
	}

	private void registerServerLifecycle() {
		ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> server = startedServer);

		ServerLifecycleEvents.SERVER_STOPPING.register(stoppingServer -> {
			// Before the final save, so no speech bubble is written into the world.
			CONVERSATIONS.clear();
			BUBBLES.clear();
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(stoppedServer -> {
			TRACKER.clear();
			GOSSIP.clear();
			PRESENCE.clear();
			RENAME_PROMPTS.clear();
			server = null;
			if (ollama != null) {
				// Reset, not close. In single-player this fires every time the player returns to
				// the title screen, and closing the HTTP client left every later world unable to
				// reach Ollama at all.
				ollama.reset();
			}
		});

		ServerLevelEvents.UNLOAD.register((unloadingServer, level) -> TRACKER.onLevelUnload(level));
	}

	/**
	 * Wires up villager bookkeeping: tracking which villagers are loaded, giving every one of
	 * them an identity, and running the periodic sweep.
	 */
	private void registerVillagerLifecycle() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) {
				TRACKER.onLoad(villager, level);
				// Retrofit point: villagers from worlds that predate the mod, spawn eggs and
				// cured zombie villagers all arrive here without a soul and get one.
				VillagerSouls.ensureSoul(villager, level);
				return;
			}

			// A speech bubble that survived into the save — from an autosave mid-conversation
			// or an unclean shutdown — would otherwise hang in the air forever. Queued rather
			// than discarded here: see the NEXT_TICK_WORK field comment for why.
			if (entity instanceof Display.TextDisplay display
				&& Boolean.TRUE.equals(display.getAttached(Attachments.SPEECH_BUBBLE))) {
				NEXT_TICK_WORK.add(display::discard);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) {
				TRACKER.onUnload(villager, level);
				// Queued for the same reason a stale bubble is: this fires from inside vanilla's
				// own per-chunk entity iteration, and discarding the bubble on the spot here
				// crashes it exactly the same way discarding one on load does.
				UUID villagerId = villager.getUUID();
				NEXT_TICK_WORK.add(() -> BUBBLES.remove(villagerId));
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Villager villager) {
				Grief.onVillagerDied(villager, source);
				if (villager.level() instanceof ServerLevel level) {
					Settlements.onResidentDied(villager, level);
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(tickingServer -> {
			// Safe here: an ordinary tick, well outside vanilla's own entity-section iteration.
			Runnable deferred;
			while ((deferred = NEXT_TICK_WORK.poll()) != null) {
				deferred.run();
			}

			ollama.tick();
			BUBBLES.tick();
			CONVERSATIONS.tick(tickingServer);
			GOSSIP.tickConversations();

			if (++this.tickCounter < SWEEP_INTERVAL_TICKS) {
				return;
			}
			this.tickCounter = 0;
			for (ServerLevel level : tickingServer.getAllLevels()) {
				sweep(level);
			}
		});
	}

	/**
	 * Player-facing hooks: opening a conversation by sneak-interacting with a villager, and
	 * capturing chat while one is open.
	 */
	private void registerPlayerInteraction() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			// Plain right-click is left alone so vanilla trading still works; sneaking is the
			// deliberate "I want something from this villager" gesture.
			if (level.isClientSide() || !player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (!(entity instanceof Villager villager) || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (!(level instanceof ServerLevel serverLevel)) {
				return InteractionResult.PASS;
			}

			// A book asks about them instead of talking to them, and works on children too — a
			// child is worth reading about even though they are too young to hold a conversation.
			// A written book the mod already wrote is rewritten the same way, so the record is
			// never stuck stale.
			ItemStack heldStack = player.getItemInHand(hand);
			if (Books.isBlankBook(heldStack) || Books.isWrittenBook(heldStack)) {
				// See the SUPPRESS_BOOK_OPEN_UNTIL field comment: this is what actually stops the
				// stray follow-up "use held item" packet from popping a book open.
				SUPPRESS_BOOK_OPEN_UNTIL.put(
					serverPlayer.getUUID(), serverLevel.getGameTime() + SUPPRESS_BOOK_OPEN_TICKS);
				// See the NEXT_TICK_WORK field comment: waiting a tick keeps that same stray packet
				// from being processed against the item now sitting in the player's hand.
				NEXT_TICK_WORK.add(() -> VillagerBook.give(serverPlayer, villager, serverLevel, hand));
				return InteractionResult.SUCCESS_SERVER;
			}
			if (villager.isBaby()) {
				return InteractionResult.PASS;
			}

			CONVERSATIONS.start(serverPlayer, villager, false);
			return InteractionResult.SUCCESS_SERVER;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (level.isClientSide() || !player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
				return InteractionResult.PASS;
			}
			ItemStack heldStack = player.getItemInHand(hand);
			if (!Books.isBlankBook(heldStack) && !Books.isWrittenBook(heldStack)) {
				return InteractionResult.PASS;
			}

			BlockPos pos = hitResult.getBlockPos();
			if (!(serverLevel.getBlockState(pos).getBlock() instanceof BellBlock)) {
				return InteractionResult.PASS;
			}

			Optional<Settlement> settlement = Settlements.at(pos, serverLevel);
			if (settlement.isEmpty()) {
				Notify.actionBar(serverPlayer, Component.literal(
						"No village answers to this bell yet - villagers have to live here first.")
					.withStyle(ChatFormatting.GRAY));
				// Consumed regardless, so a sneak-click with a book never also rings the bell.
				return InteractionResult.SUCCESS_SERVER;
			}

			// See the SUPPRESS_BOOK_OPEN_UNTIL and NEXT_TICK_WORK field comments: together these
			// stop the stray follow-up "use held item" packet from popping open whatever book —
			// old or brand new — the player happens to be holding when it arrives.
			SUPPRESS_BOOK_OPEN_UNTIL.put(
				serverPlayer.getUUID(), serverLevel.getGameTime() + SUPPRESS_BOOK_OPEN_TICKS);
			Settlement toWrite = settlement.get();
			NEXT_TICK_WORK.add(() -> VillageBook.give(serverPlayer, toWrite, serverLevel, hand));
			return InteractionResult.SUCCESS_SERVER;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			ItemStack heldStack = player.getItemInHand(hand);
			if (!Books.isBlankBook(heldStack) && !Books.isWrittenBook(heldStack)) {
				return InteractionResult.PASS;
			}

			Long suppressedUntil = SUPPRESS_BOOK_OPEN_UNTIL.remove(serverPlayer.getUUID());
			if (suppressedUntil == null || level.getGameTime() > suppressedUntil) {
				return InteractionResult.PASS;
			}
			// The stray packet described in the SUPPRESS_BOOK_OPEN_UNTIL field comment — swallowed
			// so vanilla's own WrittenBookItem#use never gets to pop a screen open on its own.
			return InteractionResult.SUCCESS_SERVER;
		});

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			// Returning false suppresses the broadcast entirely, which is what routes the
			// player's typing to the mod instead of to global chat.
			//
			// A rename prompt is answered first: it is a direct question the player was just
			// asked, so it takes precedence over a conversation that happens to be open.
			if (RENAME_PROMPTS.handleChat(sender, message.signedContent())) {
				return false;
			}
			return !CONVERSATIONS.handlePlayerMessage(sender, message.signedContent());
		});

		ServerPlayerEvents.LEAVE.register(player -> {
			CONVERSATIONS.end(player.getUUID(), ConversationManager.EndReason.REPLACED);
			// Rejoining should announce wherever they log back in, rather than staying silent
			// because the server still thinks they never left.
			PRESENCE.forget(player.getUUID());
			// And nobody should come back to find their chat still being swallowed by a question
			// they were asked before they logged out.
			RENAME_PROMPTS.forget(player.getUUID());
		});
	}

	/** Periodic per-villager upkeep. Deliberately cheap: it runs over every loaded villager. */
	private void sweep(ServerLevel level) {
		int relationshipCap = config.ollama.relationshipCap;
		long gameTime = level.getGameTime();
		// Courtship and births are evaluated on their own slower cadence, since a village
		// pairing off every two seconds would be absurd as well as expensive.
		boolean familyPass = gameTime % Tuning.Family.COURTSHIP_INTERVAL_TICKS < SWEEP_INTERVAL_TICKS;

		for (Villager villager : TRACKER.loaded(level)) {
			if (!villager.isAlive()) {
				TRACKER.onUnload(villager, level);
				continue;
			}

			VillagerSouls.updateForAge(villager, level).trimRelationships(relationshipCap);
			Grief.tickGrief(villager, level);
			Settlements.assign(villager, level);

			if (familyPass) {
				Courtship.evaluate(villager, level);
				Births.evaluate(villager, level);
			}
		}

		// The visible day/night clock, not getGameTime(): gameTime is a monotonic tick count
		// that never resets, while getOverworldClockTime() is what sleeping through the night
		// skips forward and what a player actually sees as "late afternoon." Using gameTime here
		// would drift out of sync with the visible cycle the moment anyone ever slept.
		long timeOfDay = level.getOverworldClockTime() % 24000L;

		// Once a day, at a fixed time rather than on player action: engaged couples are married
		// and anyone grieving stops. A village keeps its own rhythm instead of waiting on someone
		// to notice and ring a bell for it.
		if (isInDailyWindow(timeOfDay, Tuning.Family.DAILY_CEREMONY_TICK)) {
			Weddings.marryDueCouples(level);
			Grief.endDailyMourning(level);
		}

		// Later the same day, once the mingling is done and villagers have headed home: today's
		// record is wiped clean, ready to be filled in again tomorrow.
		if (isInDailyWindow(timeOfDay, Tuning.Family.DAILY_RESET_TICK)) {
			clearDailyLogs(level);
		}

		// Deep night: whoever is not asleep in a bed by now has none, and after enough such
		// nights they leave the village for good.
		if (isInDailyWindow(timeOfDay, Tuning.Settlement.BED_CHECK_TICK)) {
			Housing.nightlyBedCheck(level);
		}

		GOSSIP.tick(level);
		tickSettlements(level);
	}

	/**
	 * Whether this tick falls in the once-daily window a given daily event fires in. Sized to
	 * {@link #SWEEP_INTERVAL_TICKS} so it is hit exactly once per in-game day regardless of when
	 * in that window the sweep happens to land.
	 */
	private static boolean isInDailyWindow(long timeOfDay, long targetTick) {
		return timeOfDay >= targetTick && timeOfDay < targetTick + SWEEP_INTERVAL_TICKS;
	}

	/**
	 * Wipes every loaded villager's personal gossip-heard memory clean, along with each of this
	 * level's settlements' own gossip and event logs — the village's record of who talked to whom
	 * and what happened, ready to be filled in again tomorrow.
	 */
	private static void clearDailyLogs(ServerLevel level) {
		for (Villager villager : TRACKER.loaded(level)) {
			VillagerSoul soul = villager.getAttached(Attachments.SOUL);
			if (soul != null) {
				soul.clearGossip();
			}
		}

		SettlementData data = SettlementData.get(level.getServer());
		for (Settlement settlement : data.all()) {
			if (!settlement.bell().dimension().equals(level.dimension())) {
				continue;
			}
			settlement.clearGossipLog();
			settlement.clearEventLog();
			settlement.clearGossipTopics();
			data.markChanged();
		}
	}

	/** Settlement-level upkeep: leadership, needs, employment and arrival banners. */
	private void tickSettlements(ServerLevel level) {
		SettlementData data = SettlementData.get(level.getServer());
		RENAME_PROMPTS.expire(level.getGameTime());
		// Repairs duplicates left in worlds played before one-village-per-bell-cluster, and runs
		// before anything reads the list so a merged-away settlement is not announced or led.
		Settlements.mergeOverlapping(level);
		PRESENCE.tick(level);

		for (Settlement settlement : data.all()) {
			if (!settlement.bell().dimension().equals(level.dimension())) {
				continue;
			}
			// A village whose last bell was broken is disbanded here and skipped for good.
			if (!Settlements.verifyBell(settlement, level, data)) {
				continue;
			}

			List<Villager> residents = Settlements.residents(settlement, level);
			if (residents.isEmpty()) {
				// Nobody home: leave it alone rather than starving or electing in absentia.
				continue;
			}

			Settlements.updateLeadership(settlement, level);

			// A village's backstory: couples and family ties among whoever was already here,
			// plus a random opinion of everyone else. Once only, the first time it is seen with
			// more than one resident.
			if (!settlement.familySeeded() && residents.size() > 1) {
				FamilySeeding.seed(residents, level);
				settlement.setFamilySeeded(true);
				data.markChanged();
			}

			// Jobs reads lastNeedsUpdate to rate-limit its complaints, so it runs before Needs
			// advances that clock. Complaints runs after Needs so its morning reckoning reads
			// the food state with the day's harvest already landed.
			Jobs.tick(settlement, level, residents);
			Needs.tick(settlement, level, residents);
			Complaints.tick(settlement, level, residents);
		}
	}
}
