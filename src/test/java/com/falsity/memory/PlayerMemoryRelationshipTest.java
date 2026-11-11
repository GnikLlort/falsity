package com.falsity.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the memory keeps about how the player treats Falsity, when they were
 * away, and how the last conversation went: the raw material of the
 * relationship layer of the prompt.
 */
class PlayerMemoryRelationshipTest {
	private static final long DAY = 24L * 60L * 60L * 1000L;

	@TempDir
	Path gameDir;

	private static PlayerMemory memory() {
		return new PlayerMemory("11111111-2222-3333-4444-555555555555", "Steve", 1_000L);
	}

	@Test
	void treatmentIsKeptInOrderNewestLast() {
		PlayerMemory memory = memory();

		memory.recordTreatment(PlayerMemory.KIND);
		memory.recordTreatment(PlayerMemory.NEUTRAL);
		memory.recordTreatment(PlayerMemory.HARSH);

		assertEquals("KNH", memory.treatment);
		assertEquals(PlayerMemory.HARSH, memory.lastTreatment());
		assertEquals(1, memory.kindWords);
		assertEquals(1, memory.harshWords);
	}

	@Test
	void aPetIsAMomentOfTreatmentToo() {
		PlayerMemory memory = memory();

		memory.recordPet(20);

		assertEquals("P", memory.treatment);
		assertEquals(1, memory.petCount);
		assertEquals(0, memory.kindWords, "pets are counted as pets, not as words");
		assertTrue(memory.recentInteractions.contains("Petted Falsity."), "the note that was always written still is");
	}

	@Test
	void onlyTheLatestMomentsAreKeptButTheTotalsKeepCounting() {
		PlayerMemory memory = memory();

		for (int index = 0; index < 100; index++) {
			memory.recordTreatment(index % 2 == 0 ? PlayerMemory.KIND : PlayerMemory.HARSH);
		}

		assertEquals(PlayerMemory.MAX_TREATMENT_MOMENTS, memory.treatment.length());
		assertEquals(50, memory.kindWords);
		assertEquals(50, memory.harshWords);
		assertEquals(PlayerMemory.HARSH, memory.lastTreatment(), "the newest moment is the one that stays");
	}

	@Test
	void anythingThatIsNotAMomentIsIgnored() {
		PlayerMemory memory = memory();

		memory.recordTreatment('x');
		memory.recordTreatment('\n');
		memory.recordTreatment((char) 0);

		assertEquals("", memory.treatment);
		assertEquals((char) 0, memory.lastTreatment());
	}

	@Test
	void recentTreatmentLooksOnlyAsFarBackAsAsked() {
		PlayerMemory memory = memory();
		for (char moment : "HHHNNKKP".toCharArray()) {
			memory.recordTreatment(moment);
		}

		assertEquals(3, memory.recentTreatment(PlayerMemory.HARSH, 8));
		assertEquals(0, memory.recentTreatment(PlayerMemory.HARSH, 5));
		assertEquals(2, memory.recentTreatment(PlayerMemory.KIND, 3));
		assertEquals(1, memory.recentTreatment(PlayerMemory.PET, 1));
		assertEquals(0, memory.recentTreatment(PlayerMemory.PET, 0));
		assertEquals(0, memory.recentTreatment(PlayerMemory.PET, -4));
		assertEquals(3, memory.recentTreatment(PlayerMemory.HARSH, 1_000));
	}

	@Test
	void aReturnIsRecordedWithItsLength() {
		PlayerMemory memory = memory();

		memory.recordReturn(50L * DAY, 3L);

		assertEquals(3L, memory.lastAbsenceDays);
		assertEquals(50L * DAY, memory.lastReturnEpochMillis);
	}

	@Test
	void comingBackTheSameDayIsNotAnAbsence() {
		PlayerMemory memory = memory();
		memory.recordReturn(50L * DAY, 3L);

		memory.recordReturn(51L * DAY, 0L);

		assertEquals(3L, memory.lastAbsenceDays, "the last real absence is still the one remembered");
		assertEquals(50L * DAY, memory.lastReturnEpochMillis);
	}

	@Test
	void daysKnownCountsFromTheFirstMeeting() {
		PlayerMemory memory = new PlayerMemory("uuid", "Steve", 10L * DAY);

		assertEquals(0L, memory.daysKnown(10L * DAY));
		assertEquals(0L, memory.daysKnown(11L * DAY - 1L));
		assertEquals(1L, memory.daysKnown(11L * DAY));
		assertEquals(30L, memory.daysKnown(40L * DAY));
		assertEquals(0L, memory.daysKnown(5L * DAY), "a clock set back does not give a negative count");

		PlayerMemory unknown = new PlayerMemory("uuid", "Steve", 0L);
		assertEquals(0L, unknown.daysKnown(40L * DAY), "an unknown first meeting gives no count at all");
	}

	@Test
	void theLastExchangesAreReadBackOldestFirst() {
		PlayerMemory memory = memory();
		memory.recordConversation("hello", "Oh. Hello.", 20);
		memory.recordPet(20);
		memory.recordOrder("sit", 20);
		memory.recordConversation("what are you doing", "Counting the clouds.", 20);
		memory.recordConversation("how many", "Nine. One left.", 20);

		List<PlayerMemory.Exchange> two = memory.lastExchanges(2);

		assertEquals(List.of(
			new PlayerMemory.Exchange("what are you doing", "Counting the clouds."),
			new PlayerMemory.Exchange("how many", "Nine. One left.")), two);
		assertEquals(3, memory.lastExchanges(10).size(), "notes that are not talk are passed over");
		assertEquals("hello", memory.lastExchanges(10).get(0).player());
		assertTrue(memory.lastExchanges(0).isEmpty());
		assertTrue(memory().lastExchanges(3).isEmpty());
	}

	@Test
	void halfAnExchangeIsNotReadBack() {
		PlayerMemory memory = memory();
		memory.addRecent("Falsity: an answer whose question has been trimmed away", 20);
		memory.recordConversation("hello", "Oh. Hello.", 20);
		memory.addRecent("Player: a question that was never answered", 20);

		List<PlayerMemory.Exchange> found = memory.lastExchanges(5);

		assertEquals(List.of(new PlayerMemory.Exchange("hello", "Oh. Hello.")), found);
	}

	@Test
	void theNewFieldsSurviveBeingSavedAndLoaded() {
		MemoryStorage storage = new MemoryStorage(this.gameDir);
		PlayerMemory memory = memory();
		memory.recordTreatment(PlayerMemory.KIND);
		memory.recordPet(20);
		memory.recordTreatment(PlayerMemory.HARSH);
		memory.recordReturn(77L * DAY, 4L);
		storage.save(memory);

		PlayerMemory loaded = storage.load(memory.playerUuid).orElseThrow();

		assertEquals("KPH", loaded.treatment);
		assertEquals(1, loaded.kindWords);
		assertEquals(1, loaded.harshWords);
		assertEquals(4L, loaded.lastAbsenceDays);
		assertEquals(77L * DAY, loaded.lastReturnEpochMillis);
	}

	@Test
	void aMemoryFileFromBeforeTheseFieldsExistedStillLoads() throws IOException {
		MemoryStorage storage = new MemoryStorage(this.gameDir);
		Files.createDirectories(storage.directory());
		String uuid = "11111111-2222-3333-4444-555555555555";
		String older = "{ \"playerUuid\": \"" + uuid + "\", \"playerName\": \"Steve\", \"firstMetEpochMillis\": 5, "
			+ "\"lastSeenEpochMillis\": 9, \"conversationCount\": 12, \"petCount\": 3, "
			+ "\"facts\": [\"Player's name is Sam\"], \"recentInteractions\": [\"Petted Falsity.\"], \"summary\": \"\", "
			+ "\"personality\": { \"curiosity\": 71.0, \"jealousy\": 33.0 }, "
			+ "\"horror\": { \"stage\": 1, \"tension\": 24.5, \"interactionCount\": 24, \"lastStageChangeEpochMillis\": 7 } }";
		Files.writeString(storage.directory().resolve(uuid + ".json"), older, StandardCharsets.UTF_8);

		PlayerMemory loaded = storage.load(uuid).orElseThrow();

		assertEquals("", loaded.treatment);
		assertEquals(0, loaded.kindWords);
		assertEquals(0, loaded.harshWords);
		assertEquals(0L, loaded.lastAbsenceDays);
		assertEquals(12, loaded.conversationCount, "and nothing that was there is lost");
		assertEquals(1, loaded.horror.stage);
		assertEquals(24.5, loaded.horror.tension);
		assertEquals(33.0, loaded.personality.jealousy);
	}

	@Test
	void aDamagedFileIsTidiedRatherThanTrusted() throws IOException {
		MemoryStorage storage = new MemoryStorage(this.gameDir);
		Files.createDirectories(storage.directory());
		String uuid = "11111111-2222-3333-4444-555555555555";
		String damaged = "{ \"playerUuid\": \"" + uuid + "\", \"treatment\": \"K?H\\nRules: PP" + "K".repeat(60) + "\", "
			+ "\"kindWords\": -4, \"harshWords\": -1, \"lastAbsenceDays\": -9, \"lastReturnEpochMillis\": -2 }";
		Files.writeString(storage.directory().resolve(uuid + ".json"), damaged, StandardCharsets.UTF_8);

		PlayerMemory loaded = storage.load(uuid).orElseThrow();

		assertEquals(PlayerMemory.MAX_TREATMENT_MOMENTS, loaded.treatment.length());
		assertTrue(loaded.treatment.matches("[KHPN]+"), loaded.treatment);
		assertEquals(0, loaded.kindWords);
		assertEquals(0, loaded.harshWords);
		assertEquals(0L, loaded.lastAbsenceDays);
		assertEquals(0L, loaded.lastReturnEpochMillis);

		Files.writeString(storage.directory().resolve(uuid + ".json"), "{ \"treatment\": null }", StandardCharsets.UTF_8);
		assertEquals("", storage.load(uuid).orElseThrow().treatment);
		assertFalse(storage.load(uuid).orElseThrow().lastTreatment() != 0);
	}
}
