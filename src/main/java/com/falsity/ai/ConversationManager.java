package com.falsity.ai;

import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.falsity.horror.HorrorManager;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemorySummarizer;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityManager;
import com.falsity.personality.PersonalityProfile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates player-to-Falsity conversations.
 *
 * <p>Flow: validate (enabled, message length, per-player cooldown, server
 * rate limit) on the server thread, then ask Groq asynchronously, then
 * schedule the reply back onto the server thread. The server tick is never
 * blocked waiting for the network. Any failure lands on the offline
 * fallback, so Falsity always answers.
 *
 * <p>AI output is dialogue plus at most one validated suggestion
 * (sit / follow / greet). Suggestions are applied through the entity's own
 * methods; the model can never run commands or touch game state directly.
 */
public final class ConversationManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(ConversationManager.class);

	private final FalsityConfig config;
	private final GroqClient groq;
	private final ApiKeyProvider apiKeys;
	private final MemoryManager memories;
	private final PersonalityManager personalities;
	private final HorrorManager horror;
	private final OfflineFallback fallback;
	private final Path gameDir;
	private final LongSupplier clock;

	private final Map<UUID, Long> lastRequestMillis = new HashMap<>();

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
		this.config = config;
		this.groq = groq;
		this.apiKeys = apiKeys;
		this.memories = memories;
		this.personalities = personalities;
		this.horror = horror;
		this.fallback = fallback;
		this.gameDir = gameDir;
		this.clock = clock;
	}

	/** Outcome of a conversation request, for the command to report. */
	public enum Outcome {
		ACCEPTED,
		DISABLED,
		TOO_LONG,
		ON_COOLDOWN
	}

	/**
	 * Starts a conversation. Must be called on the server thread. The reply
	 * is delivered asynchronously; this method only validates and kicks off
	 * the request.
	 */
	public synchronized Outcome requestConversation(Player player, FalsityEntity falsity, String message) {
		if (!this.config.conversation.enabled) {
			return Outcome.DISABLED;
		}

		String cleaned = message == null ? "" : message.trim();
		if (cleaned.length() > this.config.conversation.maxMessageLength) {
			return Outcome.TOO_LONG;
		}
		if (cleaned.isEmpty()) {
			return Outcome.TOO_LONG;
		}

		UUID playerUuid = player.getUUID();
		long now = this.clock.getAsLong();
		if (!tryStartRequest(playerUuid, now)) {
			return Outcome.ON_COOLDOWN;
		}

		boolean isOwner = falsity.isBondedTo(playerUuid);
		String playerName = player.getName().getString();

		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		memory.touch(now);
		MemorySummarizer.refresh(memory, this.config.memory.maxSummaryChars);
		this.memories.save(playerUuid);

		PersonalityProfile personality = this.personalities.profileFor(playerUuid, playerName);
		EmotionalState mood = EmotionalState.from(personality);
		HorrorStage stage = this.horror.stageFor(playerUuid, playerName);

		// Capture everything the async callback needs; the player or entity
		// may be gone by the time Groq answers.
		ServerLevel level = falsity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		UUID falsityId = falsity.getUUID();

		if (!this.config.groq.enabled) {
			deliverOffline(player, falsity, memory, personality, stage, cleaned, isOwner);
			return Outcome.ACCEPTED;
		}

		String apiKey = this.apiKeys.resolve(this.config, this.gameDir).orElse("");
		if (apiKey.isEmpty()) {
			LOGGER.debug("No Groq API key configured; using offline lines for {}.", playerName);
			deliverOffline(player, falsity, memory, personality, stage, cleaned, isOwner);
			return Outcome.ACCEPTED;
		}

		String systemPrompt = PromptBuilder.buildSystemPrompt(memory, personality, mood, stage, isOwner, cleaned);
		List<GroqClient.ChatMessage> messages = PromptBuilder.buildMessages(systemPrompt, cleaned);

		this.groq.chat(
				this.config.groq.endpoint,
				apiKey,
				this.config.groq.model,
				messages,
				this.config.groq.temperature,
				this.config.groq.maxTokens,
				this.config.groq.timeoutSeconds,
				this.config.conversation.maxRequestsPerMinute
			)
			.whenComplete((result, error) -> {
				Runnable deliver = () -> deliverResult(player, falsity, falsityId, level, memory, personality, stage, cleaned, isOwner, result, error);
				if (level != null && level.getServer() != null) {
					level.getServer().execute(deliver);
				} else {
					deliver.run();
				}
			});

		return Outcome.ACCEPTED;
	}

	private void deliverResult(
		Player player,
		FalsityEntity falsity,
		UUID falsityId,
		ServerLevel level,
		PlayerMemory memory,
		PersonalityProfile personality,
		HorrorStage stage,
		String playerMessage,
		boolean isOwner,
		GroqClient.Result result,
		Throwable error
	) {
		// The player may have logged off and the entity may have been
		// removed while Groq was thinking. Saying nothing is correct then.
		if (player.isRemoved() || !player.isAlive()) {
			return;
		}
		if (falsity.isRemoved() || !falsity.getUUID().equals(falsityId)) {
			return;
		}

		if (error != null || !(result instanceof GroqClient.Result.Success success)) {
			deliverOffline(player, falsity, memory, personality, stage, playerMessage, isOwner);
			return;
		}

		var parsed = AIResponseParser.parse(success.content());
		if (parsed.isEmpty()) {
			deliverOffline(player, falsity, memory, personality, stage, playerMessage, isOwner);
			return;
		}

		String dialogue = parsed.get().dialogue();
		say(player, falsity, dialogue);
		applySuggestion(falsity, parsed.get().action());

		memory.recordConversation(playerMessage, dialogue, this.config.memory.maxRecentInteractions);
		extractFact(memory, playerMessage);
		this.memories.save(player.getUUID());
		this.personalities.applyEvent(player.getUUID(), player.getName().getString(), PersonalityProfile.Event.CONVERSATION);
		this.horror.recordInteraction(player.getUUID(), player.getName().getString());
	}

	private void deliverOffline(Player player, FalsityEntity falsity, PlayerMemory memory, PersonalityProfile personality, HorrorStage stage, String playerMessage, boolean isOwner) {
		if (player.isRemoved() || !player.isAlive() || falsity.isRemoved()) {
			return;
		}
		String line = this.fallback.respond(memory, personality, stage, playerMessage, isOwner);
		say(player, falsity, line);
		memory.recordConversation(playerMessage, line, this.config.memory.maxRecentInteractions);
		extractFact(memory, playerMessage);
		this.memories.save(player.getUUID());
		this.personalities.applyEvent(player.getUUID(), player.getName().getString(), PersonalityProfile.Event.CONVERSATION);
		this.horror.recordInteraction(player.getUUID(), player.getName().getString());
	}

	/** Sends one line of dialogue to the player, prefixed with Falsity's name. */
	public static void say(Player player, FalsityEntity falsity, String line) {
		String name = falsity.getName().getString();
		player.sendSystemMessage(Component.literal("<" + name + "> " + line));
	}

	private void applySuggestion(FalsityEntity falsity, AIResponseParser.SuggestedAction action) {
		switch (action) {
			case SIT -> falsity.setSitting(true);
			case FOLLOW -> falsity.setSitting(false);
			case GREET -> falsity.greet();
			case NONE -> {
				// No suggestion: dialogue only.
			}
		}
	}

	/**
	 * Extracts simple durable facts from player messages ("my name is X",
	 * "i like Y", "remember Z"). Local and deterministic; no API involved.
	 */
	static void extractFact(PlayerMemory memory, String playerMessage) {
		if (memory == null || playerMessage == null) {
			return;
		}
		String lower = playerMessage.toLowerCase();
		if (lower.startsWith("my name is ")) {
			String name = playerMessage.substring("my name is ".length()).trim();
			if (!name.isEmpty() && name.length() <= 32) {
				memory.recordFact("Player's name is " + name, 50);
			}
		} else if (lower.startsWith("i like ") || lower.startsWith("i love ") || lower.startsWith("i hate ")) {
			String preference = playerMessage.trim();
			if (preference.length() <= 80) {
				memory.recordFact("Player said: " + preference, 50);
			}
		} else if (lower.startsWith("remember ")) {
			String toRemember = playerMessage.substring("remember ".length()).trim();
			if (!toRemember.isEmpty() && toRemember.length() <= 80) {
				memory.recordFact("Player asked Falsity to remember: " + toRemember, 50);
			}
		}
	}

	/**
	 * Checks the player's cooldown and, if clear, records the request.
	 * Returns false when the player must wait. Package-private so unit tests
	 * can exercise cooldowns without a running game.
	 */
	synchronized boolean tryStartRequest(UUID playerUuid, long nowEpochMillis) {
		Long last = this.lastRequestMillis.get(playerUuid);
		if (last != null && nowEpochMillis - last < this.config.conversation.cooldownSeconds * 1000L) {
			return false;
		}
		this.lastRequestMillis.put(playerUuid, nowEpochMillis);
		return true;
	}

	/** Seconds left on the player's cooldown, or 0 if they may talk now. */
	public synchronized long cooldownRemainingSeconds(UUID playerUuid) {
		Long last = this.lastRequestMillis.get(playerUuid);
		if (last == null) {
			return 0L;
		}
		long elapsed = this.clock.getAsLong() - last;
		long remaining = this.config.conversation.cooldownSeconds * 1000L - elapsed;
		return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
	}

	/** Whether the player may start a conversation right now. */
	public synchronized boolean canTalk(UUID playerUuid) {
		return cooldownRemainingSeconds(playerUuid) <= 0L;
	}
}
