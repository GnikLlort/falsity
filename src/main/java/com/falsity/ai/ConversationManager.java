package com.falsity.ai;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionExecutor;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.actions.IntentRecognizer;
import com.falsity.actions.items.ItemCatalog;
import com.falsity.actions.items.ItemRequestParser;
import com.falsity.actions.items.ItemResolver;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.falsity.horror.HorrorEvent;
import com.falsity.horror.HorrorManager;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.FactExtractor;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemorySummarizer;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityManager;
import com.falsity.personality.PersonalityProfile;
import com.falsity.personality.PersonalityPrompt;
import com.falsity.personality.PersonalityPromptStore;
import com.falsity.personality.StageDirections;
import com.falsity.world.WorldQueryRecognizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one conversation turn: a player says something, Falsity answers.
 *
 * <p>Every accepted message gets exactly one reply, and none is dropped
 * without the player being told. The order of work is:
 * <ol>
 *   <li><b>Orders and world questions</b> ("follow me", "give me 16 torches",
 *       "where is the nearest village") are recognised locally and carried out
 *       at once. No network call, no cooldown, same result with or without
 *       Groq.</li>
 *   <li><b>Conversation</b> goes to Groq when it is enabled and a key is set.
 *       The request is built in layers by {@link PromptAssembler}: the mod's
 *       rules, the personality text from {@code config/falsity/personality.txt},
 *       how that personality comes out at the player's horror stage, where the
 *       two of them stand, what Falsity remembers, what the server has verified
 *       about the world, the last few exchanges and then the message itself. A
 *       message that arrives during the cooldown, or while the previous reply
 *       is still on its way, waits its turn instead of being discarded.</li>
 *   <li><b>Any Groq failure</b> (no key, rejected key, rate limit, timeout,
 *       empty reply) is logged with its reason and answered by
 *       {@link OfflineFallback}, which replies to what was actually said.</li>
 * </ol>
 *
 * <p>The model never acts on the world. It may ask for an action with a tag;
 * the server parses it, checks it and decides. Where the model's words and the
 * server's result could disagree, the server's result is what the player hears
 * (see {@link ReplyComposer}), and a reply that quotes coordinates the server
 * did not verify is replaced with an honest "I don't know".
 *
 * <p>All game-state work happens on the server thread; the Groq call is
 * asynchronous and its result is handed back to the server thread.
 */
public final class ConversationManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(ConversationManager.class);

	/** A message waits at most this long for its turn; beyond that the player is asked to wait. */
	static final long MAX_QUEUE_WAIT_MILLIS = 20_000L;

	/** A rate-limited request is retried once if Groq asks for no more than this. */
	static final long MAX_RATE_LIMIT_RETRY_MILLIS = 8_000L;

	/** Supplies what the server can verify about the world for one player. */
	public interface WorldFacts {
		/** A short plain-text line of checked facts, or empty. Called on the server thread. */
		String factsFor(Player player, FalsityEntity falsity);
	}

	private final FalsityConfig initialConfig;
	private final GroqClient groq;
	private final ApiKeyProvider apiKeys;
	private final MemoryManager memories;
	private final PersonalityManager personalities;
	private final HorrorManager horror;
	private final OfflineFallback fallback;
	private final Path gameDir;
	private final LongSupplier clock;

	private final ConversationPacer<Waiting> pacer = new ConversationPacer<>();
	private final ConversationHistory history = new ConversationHistory();
	private final AtomicLong turnCounter = new AtomicLong();

	private Supplier<FalsityConfig> configSource;
	private ActionExecutor executor;
	private ItemCatalog itemCatalog;
	private WorldFacts worldFacts;
	private PersonalityPromptStore personalityPrompts = PersonalityPromptStore.builtIn();

	/**
	 * A message waiting for the player's turn.
	 *
	 * @param unknownItem what the player asked for that is not an item, or empty
	 * @param attempt     how many times this message has already been sent to Groq
	 */
	private record Waiting(Player player, FalsityEntity falsity, String message, String unknownItem, int attempt) {
	}

	/** Everything one turn needs, gathered once on the server thread. */
	private static final class Turn {
		private final long id;
		private final Player player;
		private final FalsityEntity falsity;
		private final UUID falsityId;
		private final String message;
		private final boolean isOwner;
		private final PlayerMemory memory;
		private final PersonalityProfile personality;
		/** The stage whose voice is used, with Groq or without: the horror system's stage, unless its switch for unsettling lines is off. */
		private final HorrorStage voice;
		/** Whole days the player had been away when they spoke. */
		private final long daysAway;
		private final AtomicBoolean answered = new AtomicBoolean();
		private String verifiedFacts = "";
		private String unknownItem = "";
		private int attempt;

		private Turn(long id, Player player, FalsityEntity falsity, String message, boolean isOwner,
			PlayerMemory memory, PersonalityProfile personality, HorrorStage voice, long daysAway) {
			this.id = id;
			this.player = player;
			this.falsity = falsity;
			this.falsityId = falsity.getUUID();
			this.message = message;
			this.isOwner = isOwner;
			this.memory = memory;
			this.personality = personality;
			this.voice = voice;
			this.daysAway = daysAway;
		}
	}

	public ConversationManager(
		FalsityConfig config,
		GroqClient groq,
		ApiKeyProvider apiKeys,
		MemoryManager memories,
		PersonalityManager personalities,
		HorrorManager horror,
		OfflineFallback fallback,
		Path gameDir
	) {
		this(config, groq, apiKeys, memories, personalities, horror, fallback, gameDir, System::currentTimeMillis);
	}

	/** Visible for tests: injects the clock. */
	public ConversationManager(
		FalsityConfig config,
		GroqClient groq,
		ApiKeyProvider apiKeys,
		MemoryManager memories,
		PersonalityManager personalities,
		HorrorManager horror,
		OfflineFallback fallback,
		Path gameDir,
		LongSupplier clock
	) {
		this.initialConfig = config;
		this.groq = groq;
		this.apiKeys = apiKeys;
		this.memories = memories;
		this.personalities = personalities;
		this.horror = horror;
		this.fallback = fallback;
		this.gameDir = gameDir;
		this.clock = clock;
	}

	/** Wires the executor that carries out structured actions. */
	public void setActionExecutor(ActionExecutor executor) {
		this.executor = executor;
	}

	/**
	 * Reads the configuration from a live source, so a reloaded config takes
	 * effect without restarting. Without one, the config given at construction
	 * is used.
	 */
	public void setConfigSource(Supplier<FalsityConfig> configSource) {
		this.configSource = configSource;
	}

	/** Lets the manager tell a real item from "a hug" before treating a wish as an item request. */
	public void setItemCatalog(ItemCatalog itemCatalog) {
		this.itemCatalog = itemCatalog;
	}

	/** Supplies verified world facts for prompts. */
	public void setWorldFacts(WorldFacts worldFacts) {
		this.worldFacts = worldFacts;
	}

	/**
	 * Supplies the personality text for prompts. Without one, the built-in
	 * default personality is used and no file is read.
	 */
	public void setPersonalityPrompts(PersonalityPromptStore personalityPrompts) {
		this.personalityPrompts = personalityPrompts == null ? PersonalityPromptStore.builtIn() : personalityPrompts;
	}

	/** Where the personality text comes from, for status reporting and reloading. */
	public PersonalityPromptStore personalityPrompts() {
		return this.personalityPrompts;
	}

	/** The Groq client, for status reporting. */
	public GroqClient groq() {
		return this.groq;
	}

	/** Where the API key currently comes from. Never exposes the key. */
	public ApiKeyProvider.Located locateKey() {
		return this.apiKeys.locate(config(), this.gameDir);
	}

	/** How many messages are waiting for their turn. */
	public int waitingCount() {
		return this.pacer.waitingCount();
	}

	/** The short-term transcript, for the memory-clear command. */
	public ConversationHistory history() {
		return this.history;
	}

	private FalsityConfig config() {
		FalsityConfig live = this.configSource == null ? null : this.configSource.get();
		return live == null ? this.initialConfig : live;
	}

	/** Outcome of a talk attempt. */
	public enum Outcome {
		/** Falsity has answered, or the request is on its way to Groq. */
		ACCEPTED,
		DISABLED,
		TOO_LONG,
		/** The player must wait longer than a message is allowed to queue. */
		ON_COOLDOWN,
		/** The message is waiting for the player's turn and will be answered shortly. */
		QUEUED
	}

	/**
	 * Handles a message from {@code player} to {@code falsity}. Never blocks:
	 * orders and offline replies are delivered before this returns, a Groq
	 * reply arrives a moment later on the server thread.
	 */
	public synchronized Outcome requestConversation(Player player, FalsityEntity falsity, String message) {
		FalsityConfig config = config();
		if (!config.conversation.enabled) {
			return Outcome.DISABLED;
		}

		String cleaned = message == null ? "" : message.trim();
		if (cleaned.length() > config.conversation.maxMessageLength) {
			return Outcome.TOO_LONG;
		}
		if (cleaned.isEmpty()) {
			return Outcome.TOO_LONG;
		}

		UUID playerUuid = player.getUUID();
		long now = this.clock.getAsLong();

		// 1. Orders and world questions: carried out now, no network, no cooldown.
		String unknownItem = "";
		Optional<ActionRequest> local = IntentRecognizer.recognize(cleaned);
		if (local.isPresent()) {
			unknownItem = unidentifiedItem(local.get());
			if (unknownItem.isEmpty()) {
				ActionResult result = executeAction(player, falsity, local.get(), config);
				if (result != null) {
					// The order has been carried out; this turn ends here whatever it said.
					Turn turn = openTurn(player, falsity, cleaned, now, config);
					String outcome = result.message() == null || result.message().isBlank() ? "Okay." : result.message();
					String reply = this.fallback.acknowledge(outcome, this.history.recentReplies(playerUuid, 6));
					finish(turn, reply, config);
					FalsityMod.debug("Turn {} for {}: handled locally as {}.", turn.id, player.getName().getString(), local.get().type());
					return Outcome.ACCEPTED;
				}
			}
		}

		// 2. Conversation without Groq: answered now.
		String apiKey = config.groq.enabled ? this.apiKeys.resolve(config, this.gameDir).orElse("") : "";
		if (apiKey.isEmpty()) {
			Turn turn = openTurn(player, falsity, cleaned, now, config);
			turn.unknownItem = unknownItem;
			deliverOffline(turn, config);
			FalsityMod.debug("Turn {} for {}: answered offline ({}).", turn.id, player.getName().getString(),
				config.groq.enabled ? "no Groq API key was found" : "Groq is disabled");
			return Outcome.ACCEPTED;
		}

		// 3. Conversation with Groq: one request per cooldown, one at a time; the rest wait.
		long cooldownMillis = config.conversation.cooldownSeconds * 1000L;
		Waiting earlier = this.pacer.peek(playerUuid);
		// A message that is already waiting goes first, so a new one joins it.
		if (earlier == null && !this.pacer.isBusy(playerUuid, now) && tryStartRequest(playerUuid, now)) {
			Turn turn = openTurn(player, falsity, cleaned, now, config);
			turn.unknownItem = unknownItem;
			sendToGroq(turn, apiKey, config, now);
			return Outcome.ACCEPTED;
		}

		long wait = this.pacer.remainingMillis(playerUuid, now, cooldownMillis);
		if (wait > MAX_QUEUE_WAIT_MILLIS) {
			return Outcome.ON_COOLDOWN;
		}
		String queued = cleaned;
		String queuedUnknownItem = unknownItem;
		if (earlier != null) {
			// Keep both rather than lose the earlier one.
			String merged = earlier.message() + " " + cleaned;
			if (merged.length() <= config.conversation.maxMessageLength) {
				queued = merged;
				if (queuedUnknownItem.isEmpty()) {
					queuedUnknownItem = earlier.unknownItem();
				}
			}
		}
		this.pacer.enqueue(playerUuid, new Waiting(player, falsity, queued, queuedUnknownItem, 0), now);
		FalsityMod.debug("Queued a message from {} ({} ms until their turn).", player.getName().getString(), wait);
		return Outcome.QUEUED;
	}

	/**
	 * Releases messages whose turn has come. Called every server tick; cheap
	 * when nothing is waiting.
	 */
	public synchronized void tick() {
		if (this.pacer.waitingCount() == 0) {
			return;
		}
		FalsityConfig config = config();
		long now = this.clock.getAsLong();
		List<ConversationPacer.Ready<Waiting>> ready =
			this.pacer.takeReady(now, config.conversation.cooldownSeconds * 1000L);
		for (ConversationPacer.Ready<Waiting> entry : ready) {
			Waiting waiting = entry.payload();
			Player player = waiting.player();
			FalsityEntity falsity = waiting.falsity();
			if (player.isRemoved() || !player.isAlive() || falsity.isRemoved()) {
				LOGGER.info("Falsity dropped a waiting message from {}: the player or companion is gone.",
					player.getName().getString());
				continue;
			}

			Turn turn = openTurn(player, falsity, waiting.message(), now, config);
			turn.attempt = waiting.attempt();
			turn.unknownItem = waiting.unknownItem();
			String apiKey = config.groq.enabled ? this.apiKeys.resolve(config, this.gameDir).orElse("") : "";
			if (apiKey.isEmpty()) {
				deliverOffline(turn, config);
			} else {
				sendToGroq(turn, apiKey, config, now);
			}
		}
	}

	/**
	 * Sends a short fixed prompt to Groq and reports what came back. Used by
	 * the status command so an operator can see, in one line, whether the key,
	 * the model and the network actually work.
	 */
	public CompletableFuture<GroqClient.Exchange> testConnection() {
		FalsityConfig config = config();
		String apiKey = this.apiKeys.resolve(config, this.gameDir).orElse("");
		List<GroqClient.ChatMessage> messages = List.of(
			new GroqClient.ChatMessage("system", "You are Falsity, a small plush companion. Reply with one short friendly sentence."),
			new GroqClient.ChatMessage("user", "Say hello.")
		);
		return this.groq.exchange(new GroqClient.Request(
			config.groq.endpoint,
			apiKey,
			config.groq.model,
			messages,
			config.groq.temperature,
			config.groq.maxTokens,
			config.groq.timeoutSeconds,
			// A test is counted like any request but is never refused by the
			// local limiter: the point is to see what Groq itself says.
			Integer.MAX_VALUE,
			config.groq.reasoningEffort,
			config.groq.reasoningHeadroomTokens
		));
	}

	private Turn openTurn(Player player, FalsityEntity falsity, String message, long now, FalsityConfig config) {
		UUID playerUuid = player.getUUID();
		String playerName = player.getName().getString();

		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		boolean isOwner = falsity.isBondedTo(playerUuid);
		// Read before the memory is touched: afterwards the player was "last seen" just now.
		long daysAway = memory.daysSinceSeen(now);
		memory.touch(now);
		if (daysAway >= 1L) {
			memory.recordReturn(now, daysAway);
			memory.addRecent(HorrorEvent.returned(now, daysAway).description(), config.memory.maxRecentInteractions);
		}
		MemorySummarizer.refresh(memory, config.memory.maxSummaryChars);
		this.memories.save(playerUuid);
		if (daysAway >= 1L && isOwner) {
			// The same event a pet after an absence has always raised.
			this.personalities.applyEvent(playerUuid, playerName, PersonalityProfile.Event.OWNER_RETURNED);
		}

		PersonalityProfile personality = this.personalities.profileFor(playerUuid, playerName);
		HorrorStage voice = PromptAssembler.voiceStage(this.horror, playerUuid, playerName);
		return new Turn(this.turnCounter.incrementAndGet(), player, falsity, message,
			isOwner, memory, personality, voice, daysAway);
	}

	private void sendToGroq(Turn turn, String apiKey, FalsityConfig config, long now) {
		UUID playerUuid = turn.player.getUUID();
		ServerLevel level = turn.falsity.level() instanceof ServerLevel serverLevel ? serverLevel : null;

		String facts = "";
		if (this.worldFacts != null && config.world.enabled) {
			try {
				facts = this.worldFacts.factsFor(turn.player, turn.falsity);
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not gather world facts for a prompt: {}", exception.toString());
			}
		}
		turn.verifiedFacts = facts == null ? "" : facts;

		String hint = "";
		if (!turn.unknownItem.isEmpty()) {
			hint = "The player asked for '" + turn.unknownItem + "'. That is not an item you can hand over. "
				+ "Answer in character; if it was meant as an item, ask what they mean.";
		} else if (WorldQueryRecognizer.asksForLocation(turn.message)) {
			hint = "You have no verified location for what the player is asking about. Say you do not know where it is.";
		}

		List<GroqClient.ChatMessage> messages;
		try {
			// Saving config/falsity/personality.txt is enough: a changed file is read again here.
			PersonalityPrompt personality = this.personalityPrompts.refreshIfChanged();
			PromptAssembler.Assembled assembled = PromptAssembler.assemble(
				personality,
				new PromptAssembler.Turn(
					turn.falsity.getName().getString(), turn.memory, turn.personality, turn.voice, turn.isOwner,
					turn.daysAway, turn.message, turn.verifiedFacts, hint),
				this.history.recent(playerUuid, config.groq.historyTurns, now),
				now,
				apiKey);
			messages = assembled.messages();
			FalsityMod.debug("Turn {} for {}: prompt is {}; {}; {} voice.", turn.id, turn.player.getName().getString(),
				assembled.layers().sizes(), personality.describe(), StageDirections.voiceName(turn.voice));
		} catch (RuntimeException exception) {
			// Never a reason to leave the player without an answer.
			LOGGER.warn("Falsity could not build a prompt for {}; an offline line was used. ({})",
				turn.player.getName().getString(), exception.toString());
			deliverOffline(turn, config);
			return;
		}

		this.pacer.markBusy(playerUuid, now);
		this.groq.exchange(new GroqClient.Request(
				config.groq.endpoint,
				apiKey,
				config.groq.model,
				messages,
				config.groq.temperature,
				config.groq.maxTokens,
				config.groq.timeoutSeconds,
				config.conversation.maxRequestsPerMinute,
				config.groq.reasoningEffort,
				config.groq.reasoningHeadroomTokens
			))
			.whenComplete((exchange, error) -> {
				// Game state (memory, chat, actions) must be touched on the server thread.
				Runnable deliver = () -> deliverGroq(turn, exchange, error);
				if (level != null && level.getServer() != null) {
					level.getServer().execute(deliver);
				} else {
					deliver.run();
				}
			});
	}

	private synchronized void deliverGroq(Turn turn, GroqClient.Exchange exchange, Throwable error) {
		UUID playerUuid = turn.player.getUUID();
		this.pacer.markIdle(playerUuid);
		FalsityConfig config = config();
		String playerName = turn.player.getName().getString();

		// The player may have left, or the companion died, while we waited.
		if (turn.player.isRemoved() || !turn.player.isAlive()) {
			return;
		}
		if (turn.falsity.isRemoved() || !turn.falsity.getUUID().equals(turn.falsityId)) {
			return;
		}

		GroqClient.Diagnostics diagnostics = exchange == null ? null : exchange.diagnostics();
		if (error != null || exchange == null || !(exchange.result() instanceof GroqClient.Result.Success success)) {
			// A short rate limit is worth one more try; the message waits instead of failing.
			if (diagnostics != null && diagnostics.failure() == GroqClient.Failure.RATE_LIMITED && turn.attempt == 0
				&& diagnostics.retryAfterMillis() > 0L && diagnostics.retryAfterMillis() <= MAX_RATE_LIMIT_RETRY_MILLIS
				&& this.pacer.peek(playerUuid) == null) {
				LOGGER.info("Groq asked Falsity to slow down; retrying {}'s message in {} ms. ({})",
					playerName, diagnostics.retryAfterMillis(), diagnostics.summary());
				this.pacer.enqueue(playerUuid, new Waiting(turn.player, turn.falsity, turn.message, turn.unknownItem, 1),
					this.clock.getAsLong() + diagnostics.retryAfterMillis());
				return;
			}

			LOGGER.warn("Groq did not answer {}; Falsity used an offline line. {} {}",
				playerName,
				diagnostics == null ? "error=" + error : diagnostics.summary(),
				diagnostics == null ? "" : AiStatusReport.adviceFor(diagnostics.failure()));
			deliverOffline(turn, config);
			return;
		}

		FalsityMod.debug("Turn {} for {}: answered by Groq. {}", turn.id, playerName, diagnostics.summary());

		String dialogue = null;
		ActionRequest action = null;
		var parsed = AIResponseParser.parse(success.content());
		if (parsed.isPresent()) {
			dialogue = parsed.get().dialogue();
			action = parsed.get().structuredAction();
			if (action == null) {
				action = legacyAction(parsed.get().action());
			}
			if (parsed.get().rejectedAction()) {
				// Otherwise invisible: the words arrive, the thing asked for never happens.
				LOGGER.warn("Groq's reply to {} asked for an action that is unknown or malformed; only its words were used. {}",
					playerName, diagnostics.summary());
			}
		} else {
			// A reply that is nothing but an action tag still means the action.
			action = AIResponseParser.extractAction(success.content()).orElse(null);
		}

		if (dialogue == null && action == null) {
			LOGGER.warn("Groq answered {} with nothing usable; Falsity used an offline line. {}", playerName, diagnostics.summary());
			deliverOffline(turn, config);
			return;
		}

		if (dialogue != null) {
			String guarded = ReplyComposer.guardLocation(dialogue, turn.verifiedFacts);
			if (!guarded.equals(dialogue)) {
				LOGGER.info("Falsity discarded a model reply to {} that quoted an unverified location.", playerName);
				dialogue = guarded;
			}
		}

		ActionResult result = null;
		if (action != null && action.type().isReal()) {
			result = executeAction(turn.player, turn.falsity, action, config);
		}
		String reply = ReplyComposer.compose(dialogue, action == null ? null : action.type(), result);
		if (reply.isBlank()) {
			deliverOffline(turn, config);
			return;
		}

		LOGGER.info("Falsity answered {} through Groq. {}", playerName, diagnostics.summary());
		finish(turn, reply, config);
	}

	private void deliverOffline(Turn turn, FalsityConfig config) {
		if (turn.player.isRemoved() || !turn.player.isAlive() || turn.falsity.isRemoved()) {
			return;
		}

		List<String> recent = this.history.recentReplies(turn.player.getUUID(), 6);
		String reply;
		if (!turn.unknownItem.isEmpty() && turn.isOwner
			&& OfflineFallback.classify(turn.message) != OfflineFallback.Topic.AFFECTION) {
			// "Give me a flibber": say so, rather than answering with small talk.
			reply = this.fallback.unknownItem(turn.unknownItem, recent);
		} else {
			reply = this.fallback.respond(turn.memory, turn.personality, turn.voice, turn.message, turn.isOwner, recent);
		}
		finish(turn, reply, config);
	}

	/** Says the reply and records the turn. Each turn is answered once, whatever calls this. */
	private void finish(Turn turn, String reply, FalsityConfig config) {
		if (!turn.answered.compareAndSet(false, true)) {
			LOGGER.warn("Falsity suppressed a second reply to the same message (turn {}).", turn.id);
			return;
		}

		UUID playerUuid = turn.player.getUUID();
		String playerName = turn.player.getName().getString();
		say(turn.player, turn.falsity, reply);

		this.history.record(playerUuid, turn.message, reply, this.clock.getAsLong());
		turn.memory.recordConversation(turn.message, reply, config.memory.maxRecentInteractions);
		// Kind words, harsh words or neither: how the player speaks to Falsity is part of the relationship.
		turn.memory.recordTreatment(OfflineFallback.treatmentOf(turn.message));
		FactExtractor.extract(turn.memory, turn.message, config.memory.maxStoredFacts);
		this.memories.save(playerUuid);
		this.personalities.applyEvent(playerUuid, playerName, PersonalityProfile.Event.CONVERSATION);
		this.horror.recordInteraction(playerUuid, playerName);
	}

	/**
	 * For an item request where nothing asked for is a real item and there is
	 * nothing specific to say about it, returns what was asked for ("a hug").
	 * Such a message is conversation, not an order. Returns empty when the
	 * request should go to the item handler, or when no catalog is wired.
	 */
	private String unidentifiedItem(ActionRequest request) {
		if (this.itemCatalog == null) {
			return "";
		}
		List<ItemRequestParser.Entry> entries;
		if (request.type() == ActionType.GIVE_ITEM) {
			entries = List.of(ItemRequestParser.Entry.of(request.arg("item", ""), 1));
		} else if (request.type() == ActionType.GIVE_ITEMS) {
			entries = ItemRequestParser.parse(request.arg("items", ""));
		} else {
			return "";
		}

		List<String> names = new ArrayList<>();
		for (ItemRequestParser.Entry entry : entries) {
			ItemResolver.Resolution resolution = ItemResolver.resolve(entry.name(), this.itemCatalog);
			if (resolution.known() || !resolution.reason().isEmpty()) {
				return "";
			}
			names.add(entry.name());
		}
		return names.isEmpty() ? "" : String.join(" and ", names);
	}

	/**
	 * Validates and executes a structured action. Returns null when there is
	 * nothing to do (no action, no executor, or not a real server player).
	 */
	private ActionResult executeAction(Player player, FalsityEntity falsity, ActionRequest action, FalsityConfig config) {
		if (action == null || !action.type().isReal() || this.executor == null) {
			return null;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return null;
		}
		ServerLevel level = falsity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) {
			return null;
		}
		ActionContext context = new ActionContext(serverPlayer, falsity, level, config);
		return this.executor.execute(context, action);
	}

	private static ActionRequest legacyAction(AIResponseParser.SuggestedAction action) {
		return switch (action) {
			case SIT -> ActionRequest.of(ActionType.SIT);
			case FOLLOW -> ActionRequest.of(ActionType.FOLLOW);
			case GREET -> ActionRequest.of(ActionType.GREET);
			case NONE -> null;
		};
	}

	/** Sends a Falsity line to the player as a chat-style message. */
	public static void say(Player player, FalsityEntity falsity, String line) {
		String name = falsity.getName().getString();
		player.sendSystemMessage(Component.literal("<" + name + "> " + line));
	}

	/**
	 * Checks the player's cooldown and, if clear, records the request.
	 * Returns false when the player must wait. Package-private so unit tests
	 * can exercise cooldowns without a running game.
	 */
	synchronized boolean tryStartRequest(UUID playerUuid, long nowEpochMillis) {
		return this.pacer.tryStart(playerUuid, nowEpochMillis, config().conversation.cooldownSeconds * 1000L);
	}

	/** Seconds left on the player's cooldown, or 0 if they may talk now. */
	public synchronized long cooldownRemainingSeconds(UUID playerUuid) {
		long remaining = this.pacer.remainingMillis(
			playerUuid, this.clock.getAsLong(), config().conversation.cooldownSeconds * 1000L);
		return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
	}

	/** Whether the player may start a conversation right now. */
	public synchronized boolean canTalk(UUID playerUuid) {
		return cooldownRemainingSeconds(playerUuid) <= 0L;
	}
}
