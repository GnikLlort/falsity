package com.falsity;

import com.falsity.actions.ActionExecutor;
import com.falsity.actions.ConfirmationManager;
import com.falsity.actions.companion.ComeActionHandler;
import com.falsity.actions.companion.FightActionHandler;
import com.falsity.actions.companion.FollowActionHandler;
import com.falsity.actions.companion.GreetActionHandler;
import com.falsity.actions.companion.LookAtActionHandler;
import com.falsity.actions.companion.ProtectActionHandler;
import com.falsity.actions.companion.ReportThreatsActionHandler;
import com.falsity.actions.companion.SitActionHandler;
import com.falsity.actions.companion.StandActionHandler;
import com.falsity.actions.companion.StayActionHandler;
import com.falsity.actions.companion.StopFightActionHandler;
import com.falsity.actions.companion.StopFollowActionHandler;
import com.falsity.actions.companion.WalkToActionHandler;
import com.falsity.actions.items.GiveItemHandler;
import com.falsity.actions.items.GiveItemsHandler;
import com.falsity.actions.items.ItemAssistance;
import com.falsity.actions.items.ItemService;
import com.falsity.ai.ApiKeyProvider;
import com.falsity.ai.ConversationManager;
import com.falsity.ai.GroqClient;
import com.falsity.ai.OfflineFallback;
import com.falsity.building.BuildActionHandler;
import com.falsity.building.BuildManager;
import com.falsity.building.StopBuildActionHandler;
import com.falsity.chat.ConversationContextManager;
import com.falsity.chat.NaturalChatListener;
import com.falsity.command.FalsityCommands;
import com.falsity.config.FalsityConfig;
import com.falsity.horror.HorrorManager;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemoryStorage;
import com.falsity.personality.PersonalityManager;
import com.falsity.world.WorldQueryHandler;
import com.falsity.world.WorldService;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
	private static ActionExecutor actions;
	private static ConfirmationManager confirmations;
	private static BuildManager builds;
	private static ConversationContextManager contexts;
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

		ConfirmationManager confirmationManager = new ConfirmationManager(System::currentTimeMillis);
		BuildManager buildManager = new BuildManager();
		ConversationContextManager contextManager =
			new ConversationContextManager(config.chat.contextTimeoutSeconds, System::currentTimeMillis);

		ActionExecutor executor = new ActionExecutor();
		executor.register(new FollowActionHandler());
		executor.register(new StopFollowActionHandler());
		executor.register(new ComeActionHandler());
		executor.register(new SitActionHandler());
		executor.register(new StandActionHandler());
		executor.register(new GreetActionHandler());
		executor.register(new LookAtActionHandler());
		executor.register(new WalkToActionHandler());
		executor.register(new StayActionHandler());
		executor.register(new ProtectActionHandler());
		executor.register(new FightActionHandler());
		executor.register(new StopFightActionHandler());
		executor.register(new ReportThreatsActionHandler());
		ItemService itemService = new ItemService();
		executor.register(new GiveItemHandler(itemService));
		executor.register(new GiveItemsHandler(itemService));
		executor.register(new WorldQueryHandler());
		executor.register(new BuildActionHandler());
		executor.register(new StopBuildActionHandler());

		conversations.setActionExecutor(executor);
		conversations.setConfigSource(FalsityMod::config);
		conversations.setItemCatalog(ItemAssistance.CATALOG);
		conversations.setWorldFacts((player, falsity) -> WorldService.facts(player, falsity, config));

		actions = executor;
		confirmations = confirmationManager;
		builds = buildManager;
		contexts = contextManager;

		NaturalChatListener.register();
		// Waiting messages are released here, so they are answered (or dropped
		// with a log line) even if the companion they were said to is gone.
		ServerTickEvents.END_SERVER_TICK.register(server -> expireIdleState());

		LOGGER.info(
			"Falsity ready ({} companions registered, debug logging {}). {}",
			FalsityRegistries.FALSITY == null ? "no" : "one",
			config.debugLogging ? "on" : "off",
			describeGroqSetup()
		);
	}

	/**
	 * What is known about Groq at startup. A key being present does not prove
	 * that Groq works, so this never says "connected": it says what is
	 * configured and how to check.
	 */
	private static String describeGroqSetup() {
		if (!config.groq.enabled) {
			return "Groq is disabled in the config; Falsity will use its offline lines.";
		}
		ApiKeyProvider.Located key = new ApiKeyProvider().locate(config, gameDir);
		if (!key.present()) {
			return "No Groq API key was found" + (key.note().isEmpty() ? "" : " (" + key.note() + ")")
				+ "; Falsity will use its offline lines. Set the GROQ_API_KEY environment variable, or put the key in "
				+ key.secretsPath() + ", or in groq.apiKey in config/falsity.json.";
		}
		return "Groq is configured (model " + config.groq.model + ", key from " + key.source().description()
			+ ") but not yet verified; run /falsity ai test to check it, and /falsity ai status to see results.";
	}

	/**
	 * Re-reads {@code config/falsity.json} and applies it in place. Returns an
	 * empty string on success, or a sentence explaining what went wrong; on
	 * failure the running settings are left as they were.
	 */
	public static String reloadConfig() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("falsity.json");
		Optional<FalsityConfig> loaded = FalsityConfig.read(path);
		if (loaded.isEmpty()) {
			return "Could not read " + path.getFileName() + "; the current settings were kept. See the server log.";
		}
		config.copyFrom(loaded.get());
		LOGGER.info("Falsity settings reloaded. {}", describeGroqSetup());
		return "";
	}

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

	/** The server-side action executor. */
	public static ActionExecutor actions() {
		return actions;
	}

	/** Pending confirmations waiting for a player's answer. */
	public static ConfirmationManager confirmations() {
		return confirmations;
	}

	/** Currently running builds. */
	public static BuildManager builds() {
		return builds;
	}

	/** Warm, contextual conversations per player. */
	public static ConversationContextManager contexts() {
		return contexts;
	}

	/** Drops conversations and confirmations that have gone quiet. */
	public static void expireIdleState() {
		if (contexts != null) {
			contexts.expire();
		}
		if (confirmations != null) {
			confirmations.expire();
		}
		if (conversations != null) {
			// Releases messages that were waiting for their turn.
			conversations.tick();
		}
	}

	public static void debug(String message, Object... arguments) {
		if (config.debugLogging) {
			LOGGER.info(message, arguments);
		}
	}
}
