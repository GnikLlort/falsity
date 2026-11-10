package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityProfile;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The offline fallback must always answer, and only unsettle when allowed. */
class OfflineFallbackTest {
	@Test
	void alwaysReturnsANonEmptyLine() {
		OfflineFallback fallback = new OfflineFallback(new Random(42));
		PersonalityProfile personality = new PersonalityProfile();

		for (int i = 0; i < 50; i++) {
			String line = fallback.respond(null, personality, HorrorStage.INNOCENCE, "Hello?", true);
			assertNotNull(line);
			assertFalse(line.isBlank(), "Fallback must never return a blank line");
		}
	}

	@Test
	void strangersGetReservedLines() {
		OfflineFallback fallback = new OfflineFallback(new Random(7));
		String line = fallback.respond(null, new PersonalityProfile(), HorrorStage.INNOCENCE, "Hi", false);

		assertTrue(line.contains("owner") || line.contains("trust") || line.contains("stares"), "Stranger lines should be reserved, was: " + line);
	}

	@Test
	void unsettlingMemoryLinesOnlyAtStrangeOrBeyond() {
		PlayerMemory memory = new PlayerMemory("uuid", "Steve", 0L);
		memory.recordFact("Player's favourite block is diamonds", 50);

		OfflineFallback fallback = new OfflineFallback(new Random(1));
		PersonalityProfile personality = new PersonalityProfile();

		boolean sawUnsettling = false;
		for (int i = 0; i < 100; i++) {
			String line = fallback.respond(memory, personality, HorrorStage.STRANGE, "Hi", true);
			if (line.startsWith("I still remember")) {
				sawUnsettling = true;
				break;
			}
		}
		assertTrue(sawUnsettling, "At Strange, Falsity should sometimes recall an old fact");

		for (int i = 0; i < 100; i++) {
			String line = fallback.respond(memory, personality, HorrorStage.INNOCENCE, "Hi", true);
			assertFalse(line.startsWith("I still remember"), "At Innocence, Falsity must not unsettle");
		}
	}

	@Test
	void welcomeBackCountsTheDays() {
		OfflineFallback fallback = new OfflineFallback(new Random(3));

		assertTrue(fallback.welcomeBack(1L).contains("counted"));
		assertTrue(fallback.welcomeBack(5L).contains("5 days"));
		assertFalse(fallback.welcomeBack(0L).isBlank());
	}

	@Test
	void firstGreetingAndPetLinesAreNonEmpty() {
		OfflineFallback fallback = new OfflineFallback(new Random(9));
		assertFalse(fallback.firstGreeting().isBlank());
		assertFalse(fallback.petLine().isBlank());
	}
}
