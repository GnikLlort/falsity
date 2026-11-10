package com.falsity;

import com.falsity.command.FalsityCommands;
import com.falsity.config.FalsityConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Falsity mod.
 *
 * <p>Initialisation order matters: the configuration is read first, then the
 * registries are populated, then commands are hooked in. Nothing here talks to
 * a network or to a client, so the integrated and dedicated servers follow the
 * same path.
 */
public class FalsityMod implements ModInitializer {
	public static final String MOD_ID = "falsity";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static FalsityConfig config = new FalsityConfig();

	@Override
	public void onInitialize() {
		config = FalsityConfig.load(FabricLoader.getInstance().getConfigDir().resolve("falsity.json"));

		FalsityRegistries.initialize();
		FalsityCommands.initialize();

		LOGGER.info(
			"Falsity ready ({} companions registered, debug logging {}).",
			FalsityRegistries.FALSITY == null ? "no" : "one",
			config.debugLogging ? "on" : "off"
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

	public static void debug(String message, Object... arguments) {
		if (config.debugLogging) {
			LOGGER.info(message, arguments);
		}
	}
}
