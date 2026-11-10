package com.falsity.personality;

import com.falsity.config.FalsityConfig;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.PlayerMemory;
import java.util.UUID;

/**
 * Facade for per-player personality evolution.
 *
 * <p>Profiles live inside each player's memory (see {@link PlayerMemory}),
 * so this manager is a thin, named entry point rather than a second store.
 * Keeping the entry point separate means game code asks for personality
 * changes by event ("pet", "conversation") instead of mutating numbers
 * directly, and the evolution rules stay in one place.
 */
public final class PersonalityManager {
	private final MemoryManager memories;
	private final FalsityConfig config;

	public PersonalityManager(MemoryManager memories, FalsityConfig config) {
		this.memories = memories;
		this.config = config;
	}

	/** The player's live personality profile. */
	public PersonalityProfile profileFor(UUID playerUuid, String playerName) {
		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		if (memory.personality == null) {
			memory.personality = PersonalityProfile.fromConfig(this.config.personality);
		}
		return memory.personality;
	}

	/** Records an interaction event and persists the result. */
	public void applyEvent(UUID playerUuid, String playerName, PersonalityProfile.Event event) {
		PersonalityProfile profile = profileFor(playerUuid, playerName);
		profile.evolve(event, this.config.personality.evolutionRate);
		this.memories.save(playerUuid);
	}

	/** The player's current derived mood. */
	public EmotionalState moodFor(UUID playerUuid, String playerName) {
		return EmotionalState.from(profileFor(playerUuid, playerName));
	}
}
