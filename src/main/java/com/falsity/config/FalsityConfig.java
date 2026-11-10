package com.falsity.config;

import com.falsity.FalsityMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Falsity's server-side configuration.
 *
 * <p>Only settings that are actually implemented live here. Settings for
 * features that do not exist yet are deliberately absent rather than present
 * and ignored: the file should never promise behaviour the mod does not have.
 *
 * <p>Secrets do not belong in this file. The Groq API key is read from the
 * {@code GROQ_API_KEY} environment variable first, then from a git-ignored
 * {@code secrets.json} in the game directory, and only then from the
 * {@code groq.apiKey} field here (which ships empty). The key is never
 * logged, never written back to disk by the mod, and never sent to a client.
 *
 * <p>The class is a plain data holder so it can be exercised by unit tests
 * without starting a game: {@link #fromJson(String)} and {@link #toJson()} are
 * pure, and only {@link #load(Path)} and {@link #save(Path)} touch disk.
 */
public final class FalsityConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The settings format written by this version of the mod. */
	public static final int CURRENT_VERSION = 2;

	/** The conversation cooldown that shipped before version 2 of the format. */
	private static final int LEGACY_COOLDOWN_SECONDS = 10;

	/**
	 * Format version of the file. Absent (0) in files written before 0.2.2;
	 * {@link #migrate()} brings those up to date. Not meant to be edited.
	 */
	public int configVersion;

	/** Extra logging for development. Never affects gameplay. */
	public boolean debugLogging;

	public final Companion companion = new Companion();
	public final Groq groq = new Groq();
	public final Conversation conversation = new Conversation();
	public final Memory memory = new Memory();
	public final Personality personality = new Personality();
	public final Horror horror = new Horror();
	public final Chat chat = new Chat();
	public final Items items = new Items();
	public final Building building = new Building();
	public final Autonomy autonomy = new Autonomy();
	public final World world = new World();

	public static final class Companion {
		/** Whether Falsity follows the player it has bonded with. */
		public boolean followOwner = true;
		/** How far away the owner has to be, in blocks, before Falsity follows. */
		public double followStartDistance = 6.0;
		/** Further than this, in blocks, and Falsity catches up by other means. */
		public double teleportDistance = 24.0;
		/** Multiplier applied to Falsity's walking speed while it follows. */
		public double followSpeed = 1.1;
	}

	/**
	 * Groq conversation settings.
	 *
	 * <p>The model default is Groq's current fast hosted model at the time of
	 * writing. Groq retires models without much notice, so the value is
	 * configurable: if replies stop arriving, check the Groq console for the
	 * current model list and update this field. The endpoint is the
	 * OpenAI-compatible chat-completions URL.
	 */
	public static final class Groq {
		/** Master switch. When off, Falsity always uses its offline lines. */
		public boolean enabled = true;
		/**
		 * API key fallback. Leave empty and prefer the {@code GROQ_API_KEY}
		 * environment variable or {@code secrets.json} instead. Never commit a
		 * real key to this file.
		 */
		public String apiKey = "";
		/** Groq model id, e.g. {@code openai/gpt-oss-20b}. */
		public String model = "openai/gpt-oss-20b";
		/** Chat-completions endpoint. */
		public String endpoint = "https://api.groq.com/openai/v1/chat/completions";
		/** Seconds to wait for a Groq reply before falling back offline. */
		public int timeoutSeconds = 15;
		/** Maximum tokens in a single reply. */
		public int maxTokens = 150;
		/** Sampling temperature, 0.0 (deterministic) to 2.0 (wild). */
		public double temperature = 0.7;
		/**
		 * How hard a reasoning model (such as {@code openai/gpt-oss-20b}) thinks
		 * before answering: {@code low}, {@code medium} or {@code high}. Leave
		 * empty to send no reasoning settings at all. Ignored by other models.
		 */
		public String reasoningEffort = "low";
		/**
		 * Extra completion tokens reserved for a reasoning model's thinking, on
		 * top of {@link #maxTokens}. Without headroom such a model can spend its
		 * whole budget thinking and return an empty reply.
		 */
		public int reasoningHeadroomTokens = 512;
		/** How many earlier exchanges are sent along so replies stay on topic. 0 sends none. */
		public int historyTurns = 6;
	}

	/**
	 * Conversation pacing: how often a player's messages are sent to Groq.
	 *
	 * <p>Only Groq requests are paced. Orders, item requests and questions about
	 * the world are answered on the spot. A message that arrives while Falsity
	 * is still answering, or inside the cooldown, is held and answered next
	 * instead of being dropped.
	 */
	public static final class Conversation {
		/** Master switch for talking. Petting and orders are unaffected. */
		public boolean enabled = true;
		/** Seconds between Groq requests for the same player. */
		public int cooldownSeconds = 3;
		/** Hard cap on Groq requests per minute across the whole server. */
		public int maxRequestsPerMinute = 10;
		/** Longest player message accepted, in characters. */
		public int maxMessageLength = 256;
	}

	/** Persistent memory limits. Small on purpose: memory must stay bounded. */
	public static final class Memory {
		/** Maximum remembered facts kept per player. Oldest trivia is dropped. */
		public int maxStoredFacts = 50;
		/** Maximum recent interactions kept per player. */
		public int maxRecentInteractions = 20;
		/** Maximum characters in the generated memory summary. */
		public int maxSummaryChars = 1000;
		/** Days after which a fact may be forgotten. 0 means never. */
		public int retentionDays = 365;
	}

	/**
	 * Starting personality values, each 0 (absent) to 100 (dominant).
	 *
	 * <p>These are the values a brand-new Falsity starts with. Once a player
	 * has met Falsity, the live values evolve with interactions and are
	 * persisted per player; editing these only affects new relationships.
	 */
	public static final class Personality {
		public int curiosity = 70;
		public int affection = 60;
		public int attachment = 40;
		public int playfulness = 50;
		public int jealousy = 10;
		public int suspicion = 5;
		public int loneliness = 20;
		public int fear = 5;
		public int hostility = 0;
		/** Multiplier on how fast personality values drift. 0 freezes them. */
		public double evolutionRate = 1.0;
	}

	/**
	 * Horror progression settings.
	 *
	 * <p>Progression is deliberately slow and capped: this milestone only
	 * allows the first step beyond innocence (stage 1, "Strange"). Later
	 * milestones raise the cap. Falsity never becomes hostile in this
	 * milestone regardless of these settings.
	 */
	public static final class Horror {
		/** Master switch. When off, the stage never advances. */
		public boolean enabled = true;
		/** Multiplier on progression speed. 0 disables escalation entirely. */
		public double progressionSpeed = 1.0;
		/**
		 * Highest stage Falsity may reach: 0 Innocence, 1 Strange,
		 * 2 Suspicion, 3 Fear, 4 Nightmare. This milestone clamps to 1.
		 */
		public int maxStage = 1;
		/** Whether Falsity may stop and stare at its owner (harmless). */
		public boolean allowStaring = true;
		/** Whether Falsity may use subtly unsettling offline lines. */
		public boolean allowUnsettlingLines = true;
	}

	/**
	 * Natural-chat behaviour.
	 *
	 * <p>Falsity listens to ordinary player chat, so no command is needed to
	 * talk to it. A message is only answered when it addresses Falsity by name
	 * or continues a conversation that is still warm. That keeps ordinary
	 * chatter from being forwarded to Groq and keeps other players'
	 * conversations private.
	 */
	public static final class Chat {
		/** Master switch. When off, Falsity only answers through {@code /falsity talk}. */
		public boolean enabled = true;
		/** Seconds a contextual conversation stays warm after the last message. */
		public int contextTimeoutSeconds = 120;
		/** How far away a bonded Falsity may be and still hear the player. */
		public double maxConversationDistance = 32.0;
		/** Longest accepted message, in characters. */
		public int maxMessageLength = 256;
	}

	/**
	 * Item-assistance permissions.
	 *
	 * <p>Whether Falsity may conjure an item is decided here, never by the AI:
	 * the model can ask, but the server checks the mode, the quantity, the
	 * player's level and the per-player cooldown before anything is created.
	 */
	public static final class Items {
		/** Master switch for item assistance. */
		public boolean enabled = true;
		/** Who may ask Falsity to create an item. */
		public ItemPermissionMode permissionMode = ItemPermissionMode.OWNER;
		/** Largest number of a single item one request may create. */
		public int maxPerRequest = 64;
		/** Largest number of different items one request may create. */
		public int maxTypesPerRequest = 8;
		/** Seconds between item requests for the same player. */
		public int cooldownSeconds = 15;
		/** Requests larger than this ask for confirmation first. */
		public int confirmAboveCount = 128;
	}

	/**
	 * Building behaviour and safeguards.
	 *
	 * <p>Builds are planned, validated and executed server-side. The AI never
	 * places a block directly: it produces a structured plan, the server checks
	 * it against these limits, and only then are blocks placed gradually.
	 */
	public static final class Building {
		/** Master switch. */
		public boolean enabled = true;
		/** Largest allowed width, length or height of a structure, in blocks. */
		public int maxDimension = 32;
		/** Largest number of blocks one structure may place. */
		public int maxBlocks = 500;
		/** Block placements performed per server tick while building. */
		public int blockPlacementsPerTick = 6;
		/** Whether large or destructive builds must be confirmed first. */
		public boolean requireConfirmation = true;
		/** Builds larger than this many blocks ask for confirmation first. */
		public int confirmAboveBlocks = 150;
		/** Consume the player's own materials instead of generating them. */
		public boolean inventoryBased = true;
		/** Allow generating materials (creative/server-authorised). */
		public boolean creativeGeneration = false;
		/** How far from the player a build may start, in blocks. */
		public int maxDistanceFromPlayer = 48;
		/** How often a progress update is sent, in ticks. */
		public int progressUpdateTicks = 100;
	}

	/**
	 * Autonomous behaviour switches.
	 *
	 * <p>Each is off by default where it could change the world in a way the
	 * player did not ask for. Combat assistance is opt-in because it makes
	 * Falsity attack hostile mobs.
	 */
	public static final class Autonomy {
		/** Exploration: identify nearby threats and landmarks when asked. */
		public boolean exploration = true;
		/** Combat: help attack hostile mobs near the owner. */
		public boolean combatAssistance = true;
		/** Resource assistance: collect or retrieve permitted items. */
		public boolean resourceAssistance = true;
	}

	/**
	 * What Falsity may tell a player about the world.
	 *
	 * <p>There are two tiers. Observations (where the player stands, the biome,
	 * what they are looking at, the weather) are things the player could read
	 * off their own screen, so anyone Falsity answers may ask. Discoveries (the
	 * nearest village, ore or cave) reveal things the player cannot see, so they
	 * are gated by {@link #locatePermission}, bounded in range and rate limited.
	 * Falsity only ever reports what the server actually found.
	 */
	public static final class World {
		/** Master switch for world questions. */
		public boolean enabled = true;
		/** Whether Falsity may say coordinates. Also off when the reducedDebugInfo game rule is on. */
		public boolean shareCoordinates = true;
		/** Who may ask Falsity to find structures, ores and caves. */
		public ItemPermissionMode locatePermission = ItemPermissionMode.OWNER;
		/** How far, in chunks, a structure search may reach. */
		public int structureSearchRadiusChunks = 64;
		/** How far, in blocks, Falsity looks for ores and caves. Only loaded chunks are read. */
		public int scanRadius = 24;
		/** Seconds between searches for the same player. */
		public int locateCooldownSeconds = 5;
	}

	/**
	 * Reads the config file without falling back to defaults: empty when the
	 * file is missing or cannot be parsed. Used by the reload command, which
	 * must keep the running settings rather than silently reset them.
	 */
	public static Optional<FalsityConfig> read(Path path) {
		if (path == null || !Files.exists(path)) {
			return Optional.empty();
		}
		try {
			FalsityConfig loaded = fromJson(Files.readString(path, StandardCharsets.UTF_8));
			loaded.migrate();
			loaded.sanitise();
			return Optional.of(loaded);
		} catch (IOException | JsonSyntaxException | IllegalStateException exception) {
			FalsityMod.LOGGER.warn("Could not read {}; keeping the current settings. ({})", path, exception.toString());
			return Optional.empty();
		}
	}

	/**
	 * Copies every setting from another config into this one, in place.
	 * Everything that was handed this object at startup sees the new values
	 * immediately, which is what makes a reload take effect without a restart.
	 */
	public void copyFrom(FalsityConfig other) {
		if (other == null || other == this) {
			return;
		}
		try {
			for (Field field : FalsityConfig.class.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
					continue;
				}
				if (Modifier.isFinal(modifiers)) {
					// A settings block: keep the object, replace what it holds.
					Object mine = field.get(this);
					Object theirs = field.get(other);
					for (Field inner : mine.getClass().getDeclaredFields()) {
						int innerModifiers = inner.getModifiers();
						if (Modifier.isStatic(innerModifiers) || Modifier.isFinal(innerModifiers) || inner.isSynthetic()) {
							continue;
						}
						inner.set(mine, inner.get(theirs));
					}
				} else {
					field.set(this, field.get(other));
				}
			}
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Could not apply the reloaded settings", exception);
		}
	}

	/** Reads the configuration, falling back to defaults if the file is unusable. */
	public static FalsityConfig load(Path path) {
		FalsityConfig config = new FalsityConfig();

		if (!Files.exists(path)) {
			config.configVersion = CURRENT_VERSION;
			config.sanitise();
			config.save(path);
			return config;
		}

		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			FalsityConfig loaded = fromJson(json);
			int previousVersion = loaded.configVersion;
			boolean migrated = loaded.migrate();
			loaded.sanitise();
			if (migrated) {
				// Rewritten so the new settings are visible in the file.
				FalsityMod.LOGGER.info(
					"Updated {} from settings format {} to {}; new options were added with their defaults.",
					path, previousVersion, CURRENT_VERSION);
				loaded.save(path);
			}
			return loaded;
		} catch (IOException | JsonSyntaxException | IllegalStateException exception) {
			FalsityMod.LOGGER.warn("Could not read {}; using defaults. The file was left untouched.", path, exception);
			config.sanitise();
			return config;
		}
	}

	/** Writes the configuration atomically so a crash cannot leave a half file. */
	public void save(Path path) {
		try {
			Path parent = path.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temporary, toJson(), StandardCharsets.UTF_8);

			try {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			FalsityMod.LOGGER.warn("Could not write {}; settings apply for this session only.", path, exception);
		}
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	/** Parses configuration text. Missing fields keep their documented defaults. */
	public static FalsityConfig fromJson(String json) {
		FalsityConfig parsed = GSON.fromJson(json, FalsityConfig.class);

		if (parsed == null) {
			throw new JsonSyntaxException("Configuration is empty");
		}

		return parsed;
	}

	/**
	 * Upgrades settings written by an older version of the mod. Returns whether
	 * anything changed.
	 *
	 * <p>Before format 2 the conversation cooldown defaulted to 10 seconds and a
	 * message sent inside it was dropped. The cooldown now only paces Groq
	 * requests and late messages are held, so a file that still carries the old
	 * default is moved to the new one. Any other value was chosen by hand and is
	 * kept.
	 */
	public boolean migrate() {
		if (this.configVersion >= CURRENT_VERSION) {
			return false;
		}
		if (this.configVersion < 2 && this.conversation.cooldownSeconds == LEGACY_COOLDOWN_SECONDS) {
			this.conversation.cooldownSeconds = new Conversation().cooldownSeconds;
		}
		this.configVersion = CURRENT_VERSION;
		return true;
	}

	/**
	 * Brings every value into a range the game can actually use. Called on load
	 * and after any programmatic edit, so a hand-edited file cannot make Falsity
	 * teleport every tick or walk at a thousand blocks a second.
	 */
	public void sanitise() {
		companion.followStartDistance = clamp(companion.followStartDistance, 2.0, 24.0);
		companion.teleportDistance = clamp(companion.teleportDistance, companion.followStartDistance + 2.0, 128.0);
		companion.followSpeed = clamp(companion.followSpeed, 0.1, 2.0);

		if (groq.apiKey == null) {
			groq.apiKey = "";
		}
		if (groq.model == null || groq.model.isBlank()) {
			groq.model = "openai/gpt-oss-20b";
		}
		if (groq.endpoint == null || groq.endpoint.isBlank()) {
			groq.endpoint = "https://api.groq.com/openai/v1/chat/completions";
		}
		groq.timeoutSeconds = clampInt(groq.timeoutSeconds, 2, 60);
		groq.maxTokens = clampInt(groq.maxTokens, 16, 1024);
		groq.temperature = clamp(groq.temperature, 0.0, 2.0);
		groq.reasoningEffort = groq.reasoningEffort == null ? "" : groq.reasoningEffort.trim().toLowerCase(Locale.ROOT);
		if (!groq.reasoningEffort.isEmpty()
			&& !groq.reasoningEffort.equals("low")
			&& !groq.reasoningEffort.equals("medium")
			&& !groq.reasoningEffort.equals("high")) {
			groq.reasoningEffort = "low";
		}
		groq.reasoningHeadroomTokens = clampInt(groq.reasoningHeadroomTokens, 0, 4096);
		groq.historyTurns = clampInt(groq.historyTurns, 0, 12);

		conversation.cooldownSeconds = clampInt(conversation.cooldownSeconds, 0, 300);
		conversation.maxRequestsPerMinute = clampInt(conversation.maxRequestsPerMinute, 1, 30);
		conversation.maxMessageLength = clampInt(conversation.maxMessageLength, 16, 1024);

		memory.maxStoredFacts = clampInt(memory.maxStoredFacts, 0, 500);
		memory.maxRecentInteractions = clampInt(memory.maxRecentInteractions, 0, 200);
		memory.maxSummaryChars = clampInt(memory.maxSummaryChars, 0, 8000);
		memory.retentionDays = clampInt(memory.retentionDays, 0, 3650);

		personality.curiosity = clampInt(personality.curiosity, 0, 100);
		personality.affection = clampInt(personality.affection, 0, 100);
		personality.attachment = clampInt(personality.attachment, 0, 100);
		personality.playfulness = clampInt(personality.playfulness, 0, 100);
		personality.jealousy = clampInt(personality.jealousy, 0, 100);
		personality.suspicion = clampInt(personality.suspicion, 0, 100);
		personality.loneliness = clampInt(personality.loneliness, 0, 100);
		personality.fear = clampInt(personality.fear, 0, 100);
		personality.hostility = clampInt(personality.hostility, 0, 100);
		personality.evolutionRate = clamp(personality.evolutionRate, 0.0, 5.0);

		horror.progressionSpeed = clamp(horror.progressionSpeed, 0.0, 10.0);
		// This milestone never allows more than the first step beyond innocence.
		horror.maxStage = clampInt(horror.maxStage, 0, 1);

		chat.contextTimeoutSeconds = clampInt(chat.contextTimeoutSeconds, 0, 3600);
		chat.maxConversationDistance = clamp(chat.maxConversationDistance, 4.0, 256.0);
		chat.maxMessageLength = clampInt(chat.maxMessageLength, 16, 1024);

		if (items.permissionMode == null) {
			items.permissionMode = ItemPermissionMode.OWNER;
		}
		items.maxPerRequest = clampInt(items.maxPerRequest, 1, 512);
		items.maxTypesPerRequest = clampInt(items.maxTypesPerRequest, 1, 36);
		items.cooldownSeconds = clampInt(items.cooldownSeconds, 0, 600);
		items.confirmAboveCount = clampInt(items.confirmAboveCount, 0, 4096);

		building.maxDimension = clampInt(building.maxDimension, 2, 128);
		building.maxBlocks = clampInt(building.maxBlocks, 1, 5000);
		building.blockPlacementsPerTick = clampInt(building.blockPlacementsPerTick, 1, 64);
		building.confirmAboveBlocks = clampInt(building.confirmAboveBlocks, 0, 5000);
		building.maxDistanceFromPlayer = clampInt(building.maxDistanceFromPlayer, 4, 256);
		building.progressUpdateTicks = clampInt(building.progressUpdateTicks, 10, 6000);

		if (world.locatePermission == null) {
			world.locatePermission = ItemPermissionMode.OWNER;
		}
		world.structureSearchRadiusChunks = clampInt(world.structureSearchRadiusChunks, 8, 128);
		world.scanRadius = clampInt(world.scanRadius, 4, 48);
		world.locateCooldownSeconds = clampInt(world.locateCooldownSeconds, 0, 600);
	}

	private static double clamp(double value, double minimum, double maximum) {
		if (Double.isNaN(value)) {
			return minimum;
		}

		return Math.max(minimum, Math.min(maximum, value));
	}

	private static int clampInt(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
