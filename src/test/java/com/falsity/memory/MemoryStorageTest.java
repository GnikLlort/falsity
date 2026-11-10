package com.falsity.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Memory persistence: round-trip, corruption tolerance, deletion. */
class MemoryStorageTest {
	@TempDir
	Path tempDir;

	@Test
	void saveAndLoadRoundTrips() {
		MemoryStorage storage = new MemoryStorage(this.tempDir);
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 1234L);
		memory.recordFact("Player likes diamonds", 50);
		memory.conversationCount = 7;
		memory.personality.affection = 88.0;

		storage.save(memory);
		Optional<PlayerMemory> loaded = storage.load("uuid-1");

		assertTrue(loaded.isPresent());
		assertEquals("Steve", loaded.get().playerName);
		assertEquals(1234L, loaded.get().firstMetEpochMillis);
		assertEquals(7, loaded.get().conversationCount);
		assertEquals(88.0, loaded.get().personality.affection);
		assertEquals(1, loaded.get().facts.size());
		assertTrue(loaded.get().facts.get(0).contains("diamonds"));
	}

	@Test
	void missingMemoryIsEmptyNotAnError() {
		MemoryStorage storage = new MemoryStorage(this.tempDir);
		assertTrue(storage.load("no-such-player").isEmpty());
	}

	@Test
	void corruptFileStartsFreshInsteadOfCrashing() throws Exception {
		MemoryStorage storage = new MemoryStorage(this.tempDir);
		Files.createDirectories(storage.directory());
		Files.writeString(storage.directory().resolve("uuid-bad.json"), "{not valid json!!!");

		Optional<PlayerMemory> loaded = storage.load("uuid-bad");

		assertTrue(loaded.isEmpty(), "A corrupt memory file must be discarded, not crash the server");
	}

	@Test
	void deleteRemovesTheFile() {
		MemoryStorage storage = new MemoryStorage(this.tempDir);
		storage.save(new PlayerMemory("uuid-1", "Steve", 0L));
		assertTrue(storage.load("uuid-1").isPresent());

		storage.delete("uuid-1");

		assertTrue(storage.load("uuid-1").isEmpty());
		assertFalse(Files.exists(storage.directory().resolve("uuid-1.json")));
	}

	@Test
	void listPlayerUuidsFindsStoredPlayers() {
		MemoryStorage storage = new MemoryStorage(this.tempDir);
		storage.save(new PlayerMemory("uuid-a", "A", 0L));
		storage.save(new PlayerMemory("uuid-b", "B", 0L));

		assertTrue(storage.listPlayerUuids().contains("uuid-a"));
		assertTrue(storage.listPlayerUuids().contains("uuid-b"));
	}
}
