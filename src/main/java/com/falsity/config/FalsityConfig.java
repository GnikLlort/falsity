package com.falsity.config;

import com.falsity.FalsityMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Falsity's server-side configuration.
 *
 * <p>Only settings that are actually implemented live here. Settings for
 * features that do not exist yet are deliberately absent rather than present
 * and ignored: the file should never promise behaviour the mod does not have.
 *
 * <p>Secrets do not belong in this file. The Groq API key (Milestone 3) will be
 * read from a separate, git-ignored secret file and will never be logged.
 *
 * <p>The class is a plain data holder so it can be exercised by unit tests
 * without starting a game: {@link #fromJson(String)} and {@link #toJson()} are
 * pure, and only {@link #load(Path)} and {@link #save(Path)} touch disk.
 */
public final class FalsityConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Extra logging for development. Never affects gameplay. */
	public boolean debugLogging;

	public final Companion companion = new Companion();

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

	/** Reads the configuration, falling back to defaults if the file is unusable. */
	public static FalsityConfig load(Path path) {
		FalsityConfig config = new FalsityConfig();

		if (!Files.exists(path)) {
			config.sanitise();
			config.save(path);
			return config;
		}

		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			FalsityConfig loaded = fromJson(json);
			loaded.sanitise();
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
	 * Brings every value into a range the game can actually use. Called on load
	 * and after any programmatic edit, so a hand-edited file cannot make Falsity
	 * teleport every tick or walk at a thousand blocks a second.
	 */
	public void sanitise() {
		companion.followStartDistance = clamp(companion.followStartDistance, 2.0, 24.0);
		companion.teleportDistance = clamp(companion.teleportDistance, companion.followStartDistance + 2.0, 128.0);
		companion.followSpeed = clamp(companion.followSpeed, 0.1, 2.0);
	}

	private static double clamp(double value, double minimum, double maximum) {
		if (Double.isNaN(value)) {
			return minimum;
		}

		return Math.max(minimum, Math.min(maximum, value));
	}
}
