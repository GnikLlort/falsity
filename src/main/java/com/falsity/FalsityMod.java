package com.falsity;

import com.falsity.ai.ApiKeyProvider;
import com.falsity.ai.ConversationManager;
import com.falsity.ai.GroqClient;
import com.falsity.ai.OfflineFallback;
import com.falsity.command.FalsityCommands;
import com.falsity.config.FalsityConfig;
import com.falsity.horror.HorrorManager;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemoryStorage;
import com.falsity.personality.PersonalityManager;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Falsity mod.
 *
 * <p>Initialisation order matters: the configuration is read first, then the
 * registries are populated, then the intelligence managers (memory,
 * personality, horror, conversation) are wired, then commands are hooked in.
 * Nothing here talks to a network or to a client, so the integrated and
 * dedicated servers follow the same path.
 */
public class FalsityMod implements ModInitializer {
	public static final String MOD_ID = "falsity";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static FalsityConfig config = new FalsityConfig();
	private static MemoryManager memories;
	private static PersonalityManager personalities;
	private static HorrorManager horror;
	private static ConversationManager conversations;
	private static Path gameDir;

	@Override
	public void onInitialize() {
		gameDir = FabricLoader.getInstance().getGameDir();
		config = FalsityConfig.load(FabricLoader.getInstance().getConfigDir().resolve("falsity.json"));

		FalsityRegistries.initialize();

		MemoryStorage storage = new MemoryStorage(gameDir);
		memories = new MemoryManager(storage);
		personalities = new PersonalityManager(memories, config);
		horror = new HorrorManager(memories, config);
		conversations = new ConversationManager(
			config,
			new GroqClient(),
			new ApiKeyProvider(),
			memories,
			personalities,
			horror,
			new OfflineFallback(),
			gameDir
		);

		FalsityCommands.initialize();

		boolean groqReady = config.groq.enabled && new ApiKeyProvider().resolve(config, gameDir).isPresent();
		LOGGER.info(
			"Falsity ready ({} companions registered, debug logging {}, Groq {}).",
			FalsityRegistries.FALSITY == null ? "no" : "one",
			config.debugLogging ? "on" : "off",
			groqReady ? "connected (" + config.groq.model + ")" : "offline (fallback lines)"
		);
	}

	/** Namespaced identifier for this mod's resources. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** The live, shared server-side configuration. */
	public static FalsityConfig config() {
		return config;
	}

	/** Replaces the live configuration; used by the reload command and by tests. */
	public static void setConfig(FalsityConfig newConfig) {
		config = newConfig;
	}

	/** The game directory (server-side). Memory and secrets live under it. */
	public static Path gameDir() {
		return gameDir;
	}

	/** Per-player persistent memory. */
	public static MemoryManager memories() {
		return memories;
	}

	/** Per-player personality evolution. */
	public static PersonalityManager personalities() {
		return personalities;
	}

	/** Per-player horror progression. */
	public static HorrorManager horror() {
		return horror;
	}

	/** Player-to-Falsity conversations. */
	public static ConversationManager conversations() {
		return conversations;
	}

	public static void debug(String message, Object... arguments) {
		if (config.debugLogging) {
			LOGGER.info(message, arguments);
		}
	}
}
