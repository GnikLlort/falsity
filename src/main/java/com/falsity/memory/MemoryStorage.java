package com.falsity.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists {@link PlayerMemory} as one JSON file per player.
 *
 * <p>Files live under {@code <gameDir>/falsity-memory/<uuid>.json}. This is
 * server-side only: memory never leaves the machine except as part of a
 * prompt to Groq, and it is never synced to clients.
 *
 * <p>Writes are atomic (temp file + move) so a crash cannot leave a half
 * file. A corrupt or unreadable file is discarded with a warning and treated
 * as "no memory yet" instead of crashing the server: Falsity simply forgets
 * that player and starts again.
 */
public final class MemoryStorage {
	private static final Logger LOGGER = LoggerFactory.getLogger(MemoryStorage.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path directory;

	public MemoryStorage(Path gameDir) {
		this.directory = gameDir.resolve("falsity-memory");
	}

	/** The directory memory files live in. Exposed for tests and debugging. */
	public Path directory() {
		return this.directory;
	}

	/** Loads one player's memory, or empty if none exists yet. */
	public Optional<PlayerMemory> load(String playerUuid) {
		Path file = fileFor(playerUuid);
		if (!Files.exists(file)) {
			return Optional.empty();
		}

		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			PlayerMemory memory = GSON.fromJson(json, PlayerMemory.class);
			if (memory == null) {
				LOGGER.warn("Memory file {} was empty; starting fresh for that player.", file);
				return Optional.empty();
			}
			memory.sanitise();
			// The filename is the identity; never trust the file to agree.
			memory.playerUuid = playerUuid;
			return Optional.of(memory);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("Could not read memory file {}; that player's memory starts fresh.", file, exception);
			return Optional.empty();
		}
	}

	/** Saves one player's memory atomically. Failures are logged, never thrown. */
	public void save(PlayerMemory memory) {
		if (memory == null || memory.playerUuid == null || memory.playerUuid.isBlank()) {
			return;
		}
		memory.sanitise();

		try {
			Files.createDirectories(this.directory);
			Path file = fileFor(memory.playerUuid);
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temporary, GSON.toJson(memory), StandardCharsets.UTF_8);

			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			LOGGER.warn("Could not write memory for player {}; memory applies for this session only.", memory.playerUuid, exception);
		}
	}

	/** Deletes one player's memory file, if present. */
	public void delete(String playerUuid) {
		try {
			Files.deleteIfExists(fileFor(playerUuid));
		} catch (IOException exception) {
			LOGGER.warn("Could not delete memory for player {}.", playerUuid, exception);
		}
	}

	/** Lists every stored player UUID. Used by admin commands and tests. */
	public List<String> listPlayerUuids() {
		List<String> result = new ArrayList<>();
		if (!Files.isDirectory(this.directory)) {
			return result;
		}
		try (var stream = Files.list(this.directory)) {
			stream.filter(path -> path.getFileName().toString().endsWith(".json"))
				.map(path -> path.getFileName().toString().replace(".json", ""))
				.sorted()
				.forEach(result::add);
		} catch (IOException exception) {
			LOGGER.warn("Could not list memory files in {}.", this.directory, exception);
		}
		return result;
	}

	private Path fileFor(String playerUuid) {
		// UUIDs are hex and dashes; strip anything else so a damaged value
		// can never escape the memory directory.
		String safe = playerUuid.replaceAll("[^a-zA-Z0-9-]", "_");
		return this.directory.resolve(safe + ".json");
	}
}
