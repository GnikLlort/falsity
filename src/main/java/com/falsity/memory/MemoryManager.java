package com.falsity.memory;

import com.falsity.config.FalsityConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory cache of every player's memory, backed by {@link MemoryStorage}.
 *
 * <p>All methods are synchronized: conversations complete on background
 * threads while the server thread keeps playing, and both sides touch this
 * cache. Writes go through immediately (save-on-mutate) so a crash loses at
 * most the interaction in flight, never a whole session.
 *
 * <p>Each player's memory is fully isolated: lookups are by UUID and there is
 * no shared mutable state between players, so one player can never read or
 * overwrite another's memories.
 */
public final class MemoryManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(MemoryManager.class);

	private final MemoryStorage storage;
	private final Map<UUID, PlayerMemory> cache = new HashMap<>();

	public MemoryManager(MemoryStorage storage) {
		this.storage = storage;
	}

	/** The storage backing this manager. */
	public MemoryStorage storage() {
		return this.storage;
	}

	/**
	 * Returns the player's memory, creating and persisting a fresh one on
	 * first meeting. The player name is refreshed on every call so renames
	 * are picked up without forking the memory.
	 */
	public synchronized PlayerMemory getOrCreate(UUID playerUuid, String playerName) {
		PlayerMemory cached = this.cache.get(playerUuid);
		if (cached != null) {
			if (playerName != null && !playerName.isBlank()) {
				cached.playerName = playerName;
			}
			return cached;
		}

		Optional<PlayerMemory> loaded = this.storage.load(playerUuid.toString());
		if (loaded.isPresent()) {
			PlayerMemory memory = loaded.get();
			if (playerName != null && !playerName.isBlank()) {
				memory.playerName = playerName;
			}
			this.cache.put(playerUuid, memory);
			return memory;
		}

		PlayerMemory fresh = new PlayerMemory(playerUuid.toString(), playerName == null ? "" : playerName, System.currentTimeMillis());
		this.cache.put(playerUuid, fresh);
		this.storage.save(fresh);
		LOGGER.debug("Falsity met {} for the first time; memory created.", fresh.playerName);
		return fresh;
	}

	/** Returns the player's memory if it exists, without creating one. */
	public synchronized Optional<PlayerMemory> get(UUID playerUuid) {
		PlayerMemory cached = this.cache.get(playerUuid);
		if (cached != null) {
			return Optional.of(cached);
		}
		Optional<PlayerMemory> loaded = this.storage.load(playerUuid.toString());
		loaded.ifPresent(memory -> this.cache.put(playerUuid, memory));
		return loaded;
	}

	/** Persists one player's memory immediately. */
	public synchronized void save(UUID playerUuid) {
		PlayerMemory memory = this.cache.get(playerUuid);
		if (memory != null) {
			this.storage.save(memory);
		}
	}

	/** Persists every cached memory. Called on server shutdown and by tests. */
	public synchronized void saveAll() {
		for (PlayerMemory memory : this.cache.values()) {
			this.storage.save(memory);
		}
	}

	/** Forgets one player entirely: cache entry and file. */
	public synchronized void clear(UUID playerUuid) {
		this.cache.remove(playerUuid);
		this.storage.delete(playerUuid.toString());
	}

	/** How many players currently have cached memory. */
	public synchronized int cachedCount() {
		return this.cache.size();
	}

	/**
	 * Applies retention and refreshes summaries for every cached player.
	 * Called occasionally (on conversation), never every tick.
	 */
	public synchronized void maintain(FalsityConfig config) {
		long now = System.currentTimeMillis();
		for (PlayerMemory memory : this.cache.values()) {
			memory.applyRetention(now, config.memory.retentionDays);
			MemorySummarizer.refresh(memory, config.memory.maxSummaryChars);
		}
	}
}
