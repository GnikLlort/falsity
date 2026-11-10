package com.falsity.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Memory creation, limits, retrieval and isolation between players. */
class PlayerMemoryTest {
	@Test
	void newMemoryRecordsFirstMeeting() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 1000L);

		assertEquals("uuid-1", memory.playerUuid);
		assertEquals("Steve", memory.playerName);
		assertEquals(1000L, memory.firstMetEpochMillis);
		assertEquals(1000L, memory.lastSeenEpochMillis);
	}

	@Test
	void factsAreCappedAndOldestDropped() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);

		for (int i = 0; i < 10; i++) {
			memory.recordFact("Fact number " + i, 5);
		}

		assertEquals(5, memory.facts.size());
		assertEquals("Fact number 5", memory.facts.get(0));
		assertEquals("Fact number 9", memory.facts.get(4));
	}

	@Test
	void duplicateFactsAreIgnored() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		memory.recordFact("Player likes diamonds", 50);
		memory.recordFact("Player likes diamonds", 50);

		assertEquals(1, memory.facts.size());
	}

	@Test
	void recentInteractionsAreCapped() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		for (int i = 0; i < 10; i++) {
			memory.addRecent("Note " + i, 3);
		}
		assertEquals(3, memory.recentInteractions.size());
		assertEquals("Note 9", memory.recentInteractions.get(2));
	}

	@Test
	void retrievalPrefersMatchingMemories() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		memory.recordFact("Player's favourite block is diamonds", 50);
		memory.recordFact("Player lives in a spruce hut", 50);
		memory.recordFact("Player hates creepers", 50);

		List<String> relevant = memory.relevantMemories("Do you remember my diamonds?", 2);

		assertFalse(relevant.isEmpty());
		assertTrue(relevant.get(0).contains("diamonds"), "Diamond fact should rank first, was: " + relevant);
	}

	@Test
	void memoriesAreIsolatedBetweenPlayers() {
		PlayerMemory steve = new PlayerMemory("uuid-steve", "Steve", 0L);
		PlayerMemory alex = new PlayerMemory("uuid-alex", "Alex", 0L);

		steve.recordFact("Steve likes diamonds", 50);
		alex.recordFact("Alex likes emeralds", 50);

		assertTrue(steve.relevantMemories("diamonds", 5).stream().anyMatch(fact -> fact.contains("diamonds")));
		assertTrue(alex.relevantMemories("diamonds", 5).stream().noneMatch(fact -> fact.contains("diamonds")), "Alex must never see Steve's memories");
		assertTrue(alex.relevantMemories("emeralds", 5).stream().anyMatch(fact -> fact.contains("emeralds")));
	}

	@Test
	void sanitiseSurvivesNulls() {
		PlayerMemory memory = new PlayerMemory();
		memory.playerUuid = null;
		memory.playerName = null;
		memory.facts = null;
		memory.recentInteractions = null;
		memory.summary = null;
		memory.personality = null;
		memory.horror = null;

		memory.sanitise();

		assertEquals("", memory.playerUuid);
		assertEquals("", memory.playerName);
		assertTrue(memory.facts.isEmpty());
		assertTrue(memory.recentInteractions.isEmpty());
		assertEquals("", memory.summary);
		assertTrue(memory.personality != null);
		assertTrue(memory.horror != null);
	}

	@Test
	void daysSinceSeenCountsCorrectly() {
		long day = 24L * 60L * 60L * 1000L;
		long metAt = 1000L;
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", metAt);

		assertEquals(0L, memory.daysSinceSeen(metAt));
		assertEquals(3L, memory.daysSinceSeen(metAt + 3L * day + 1000L));
	}

	@Test
	void neverSeenPlayerHasNoAbsence() {
		// A memory with no timestamp means "not seen yet", not "seen at the
		// epoch", so the absence is unknown rather than fifty years.
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);

		assertEquals(0L, memory.daysSinceSeen(1_000_000L));
	}
}
