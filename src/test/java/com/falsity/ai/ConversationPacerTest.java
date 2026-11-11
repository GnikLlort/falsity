package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pacing conversations without throwing messages away. */
class ConversationPacerTest {
	private static final long COOLDOWN = 3_000L;
	private final UUID alex = UUID.randomUUID();
	private final UUID steve = UUID.randomUUID();

	@Test
	void oneStartPerCooldown() {
		ConversationPacer<String> pacer = new ConversationPacer<>();

		assertTrue(pacer.tryStart(this.alex, 1_000L, COOLDOWN));
		assertFalse(pacer.tryStart(this.alex, 2_000L, COOLDOWN));
		assertEquals(2_000L, pacer.remainingMillis(this.alex, 2_000L, COOLDOWN));
		assertTrue(pacer.tryStart(this.alex, 4_000L, COOLDOWN));
		assertEquals(0L, pacer.remainingMillis(this.steve, 4_000L, COOLDOWN), "another player is unaffected");
		assertTrue(pacer.tryStart(this.steve, 4_000L, COOLDOWN));
	}

	@Test
	void aMessageSentTooEarlyWaitsAndIsReleasedWhenTheCooldownEnds() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.tryStart(this.alex, 0L, COOLDOWN);

		assertNull(pacer.enqueue(this.alex, "second message", 500L));
		assertEquals(1, pacer.waitingCount());
		assertTrue(pacer.takeReady(1_000L, COOLDOWN).isEmpty(), "still cooling down");
		assertEquals(1, pacer.waitingCount(), "and still waiting, not dropped");

		List<ConversationPacer.Ready<String>> ready = pacer.takeReady(3_000L, COOLDOWN);

		assertEquals(1, ready.size());
		assertEquals("second message", ready.get(0).payload());
		assertEquals(this.alex, ready.get(0).player());
		assertEquals(0, pacer.waitingCount());
		assertFalse(pacer.tryStart(this.alex, 3_500L, COOLDOWN), "releasing a message starts a new cooldown");
		assertTrue(pacer.takeReady(10_000L, COOLDOWN).isEmpty(), "a message is released once");
	}

	@Test
	void aWaitingMessageIsHeldWhileTheReplyBeforeItIsStillOnItsWay() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.tryStart(this.alex, 0L, COOLDOWN);
		pacer.markBusy(this.alex, 0L);
		pacer.enqueue(this.alex, "are you there?", 100L);

		assertTrue(pacer.isBusy(this.alex, 5_000L));
		assertTrue(pacer.takeReady(5_000L, COOLDOWN).isEmpty(), "the cooldown is over but the first reply has not arrived");

		pacer.markIdle(this.alex);

		assertFalse(pacer.isBusy(this.alex, 5_100L));
		assertEquals(1, pacer.takeReady(5_100L, COOLDOWN).size());
	}

	@Test
	void aRequestThatNeverReportsBackDoesNotBlockThePlayerForever() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.markBusy(this.alex, 0L);
		pacer.enqueue(this.alex, "hello?", 0L);

		assertTrue(pacer.isBusy(this.alex, ConversationPacer.BUSY_TIMEOUT_MILLIS));
		assertFalse(pacer.isBusy(this.alex, ConversationPacer.BUSY_TIMEOUT_MILLIS + 1L));
		assertEquals(1, pacer.takeReady(ConversationPacer.BUSY_TIMEOUT_MILLIS + 1L, COOLDOWN).size());
	}

	@Test
	void aNewerMessageHandsBackTheOneItReplacesSoNothingIsLost() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.tryStart(this.alex, 0L, COOLDOWN);

		assertNull(pacer.enqueue(this.alex, "first", 100L));
		assertEquals("first", pacer.peek(this.alex));
		String replaced = pacer.enqueue(this.alex, "first second", 200L);

		assertEquals("first", replaced);
		assertEquals(1, pacer.waitingCount(), "one message waits per player");
		assertEquals("first second", pacer.takeReady(3_000L, COOLDOWN).get(0).payload());
	}

	@Test
	void aMessageCanBeHeldUntilAGivenTime() {
		ConversationPacer<String> pacer = new ConversationPacer<>();

		pacer.enqueue(this.alex, "retry me", 8_000L);

		assertTrue(pacer.takeReady(7_999L, COOLDOWN).isEmpty(), "Groq asked for a pause; it is respected");
		assertEquals(1, pacer.takeReady(8_000L, COOLDOWN).size());
	}

	@Test
	void playersDoNotHoldEachOtherUp() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.tryStart(this.alex, 0L, COOLDOWN);
		pacer.markBusy(this.alex, 0L);
		pacer.enqueue(this.alex, "from alex", 0L);
		pacer.enqueue(this.steve, "from steve", 0L);

		List<ConversationPacer.Ready<String>> ready = pacer.takeReady(100L, COOLDOWN);

		assertEquals(1, ready.size());
		assertEquals("from steve", ready.get(0).payload());
		assertEquals("from alex", pacer.peek(this.alex));
	}

	@Test
	void aZeroCooldownNeverMakesAnyoneWait() {
		ConversationPacer<String> pacer = new ConversationPacer<>();

		assertTrue(pacer.tryStart(this.alex, 5L, 0L));
		assertTrue(pacer.tryStart(this.alex, 5L, 0L));
		pacer.enqueue(this.alex, "now", 5L);
		assertEquals(1, pacer.takeReady(5L, 0L).size());
	}

	@Test
	void forgettingAPlayerClearsEverythingAboutThem() {
		ConversationPacer<String> pacer = new ConversationPacer<>();
		pacer.tryStart(this.alex, 0L, COOLDOWN);
		pacer.markBusy(this.alex, 0L);
		pacer.enqueue(this.alex, "waiting", 0L);

		pacer.forget(this.alex);

		assertEquals(0, pacer.waitingCount());
		assertFalse(pacer.isBusy(this.alex, 1L));
		assertTrue(pacer.tryStart(this.alex, 1L, COOLDOWN));
	}
}
