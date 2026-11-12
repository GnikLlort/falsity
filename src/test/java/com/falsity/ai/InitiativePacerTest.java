package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** When Falsity may speak without being spoken to: rarely, politely, and never to an empty chair. */
class InitiativePacerTest {
	private static final UUID STEVE = UUID.randomUUID();
	private static final UUID ALEX = UUID.randomUUID();
	private static final long MINUTE = 60_000L;
	private static final long INTERVAL = 10L * MINUTE;

	/** A pacer whose slack is always zero, so the arithmetic in these tests is exact. */
	private static InitiativePacer exact() {
		return new InitiativePacer(new Random() {
			@Override
			public double nextDouble() {
				return 0.0;
			}
		});
	}

	@Test
	void nothingIsSaidTheMomentThePlayerIsFirstSeen() {
		InitiativePacer pacer = exact();

		assertFalse(pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L), "the first look only starts the clock");
		assertFalse(pacer.claim(STEVE, INTERVAL / 2L - 1L, INTERVAL, 0L, 0L), "half an interval has to pass first");
		assertTrue(pacer.claim(STEVE, INTERVAL / 2L, INTERVAL, 0L, 0L));
	}

	@Test
	void afterSpeakingAFullIntervalMustPass() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		long first = INTERVAL / 2L;
		assertTrue(pacer.claim(STEVE, first, INTERVAL, 0L, 0L));

		assertFalse(pacer.claim(STEVE, first + 1L, INTERVAL, 0L, 0L), "claiming schedules the next moment at once");
		assertFalse(pacer.claim(STEVE, first + INTERVAL - 1L, INTERVAL, 0L, 0L));
		assertTrue(pacer.claim(STEVE, first + INTERVAL, INTERVAL, 0L, 0L));
	}

	@Test
	void aConversationInProgressIsNotInterrupted() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		long due = INTERVAL;

		assertFalse(pacer.claim(STEVE, due, INTERVAL, due - 10_000L, 0L), "they spoke ten seconds ago");
		assertFalse(pacer.claim(STEVE, due, INTERVAL, due - INTERVAL / 2L + 1L, 0L), "and not soon after a conversation either");
		assertTrue(pacer.claim(STEVE, due, INTERVAL, due - INTERVAL / 2L, 0L), "once it has been quiet for half an interval");
	}

	@Test
	void withAShortIntervalTheQuietTimeStillApplies() {
		InitiativePacer pacer = exact();
		long interval = 2L * MINUTE;
		pacer.claim(STEVE, 0L, interval, 0L, 0L);

		assertFalse(pacer.claim(STEVE, 5L * MINUTE, interval, 5L * MINUTE - InitiativePacer.QUIET_MILLIS + 1L, 0L));
		assertTrue(pacer.claim(STEVE, 5L * MINUTE, interval, 5L * MINUTE - InitiativePacer.QUIET_MILLIS, 0L));
	}

	@Test
	void aPlayerWhoNeverRespondsIsLeftInPeace() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		long now = INTERVAL;
		int spoken = 0;
		for (int round = 0; round < 50; round++) {
			if (pacer.claim(STEVE, now, INTERVAL, 0L, 0L)) {
				spoken++;
			}
			now += INTERVAL;
		}

		assertEquals(InitiativePacer.MAX_UNANSWERED, spoken, "only so many remarks to someone who is not answering");
		assertEquals(InitiativePacer.MAX_UNANSWERED, pacer.unanswered(STEVE));
	}

	@Test
	void anythingThePlayerDoesInReturnStartsTheCountAgain() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		long now = INTERVAL;
		assertTrue(pacer.claim(STEVE, now, INTERVAL, 0L, 0L));
		now += INTERVAL;
		assertTrue(pacer.claim(STEVE, now, INTERVAL, 0L, 0L));
		now += INTERVAL;
		assertFalse(pacer.claim(STEVE, now, INTERVAL, 0L, 0L), "two unanswered remarks is the limit");

		// A pet a minute ago: the player is there after all.
		long petted = now - MINUTE;
		assertTrue(pacer.claim(STEVE, now, INTERVAL, 0L, petted));
		assertEquals(1, pacer.unanswered(STEVE));
	}

	@Test
	void somethingThePlayerDidBeforeTheLastRemarkDoesNotCountAsAnAnswer() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		long now = INTERVAL;
		long earlier = now - MINUTE;
		assertTrue(pacer.claim(STEVE, now, INTERVAL, 0L, earlier));
		now += INTERVAL;
		assertTrue(pacer.claim(STEVE, now, INTERVAL, 0L, earlier));
		now += INTERVAL;

		assertFalse(pacer.claim(STEVE, now, INTERVAL, 0L, earlier), "the same old pet is not a fresh answer");
	}

	@Test
	void playersAreKeptApart() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		assertTrue(pacer.claim(STEVE, INTERVAL, INTERVAL, 0L, 0L));

		assertFalse(pacer.claim(ALEX, INTERVAL, INTERVAL, 0L, 0L), "Alex has only just been seen");
		assertTrue(pacer.claim(ALEX, 2L * INTERVAL, INTERVAL, 0L, 0L));
		assertEquals(1, pacer.unanswered(STEVE));
		assertEquals(1, pacer.unanswered(ALEX));
	}

	@Test
	void aForgottenPlayerStartsFromTheBeginning() {
		InitiativePacer pacer = exact();
		pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);
		assertTrue(pacer.claim(STEVE, INTERVAL, INTERVAL, 0L, 0L));

		pacer.forget(STEVE);

		assertEquals(0, pacer.unanswered(STEVE));
		assertFalse(pacer.claim(STEVE, 5L * INTERVAL, INTERVAL, 0L, 0L), "seen for the first time again");
	}

	@Test
	void theSlackOnlyEverAddsWaiting() {
		for (long seed = 0; seed < 50; seed++) {
			InitiativePacer pacer = new InitiativePacer(new Random(seed * 7_919L));
			pacer.claim(STEVE, 0L, INTERVAL, 0L, 0L);

			assertFalse(pacer.claim(STEVE, INTERVAL / 2L - 1L, INTERVAL, 0L, 0L), "never earlier than half an interval, seed " + seed);
			assertTrue(pacer.claim(STEVE, INTERVAL, INTERVAL, 0L, 0L), "and never later than a whole one, seed " + seed);
			assertFalse(pacer.claim(STEVE, 2L * INTERVAL - 1L, INTERVAL, 0L, 0L), "then at least a full interval, seed " + seed);
			assertTrue(pacer.claim(STEVE, 2L * INTERVAL + INTERVAL / 2L, INTERVAL, 0L, 0L), "and at most one and a half, seed " + seed);
		}
	}

	@Test
	void nonsenseInputsNeverSayYes() {
		InitiativePacer pacer = exact();

		assertFalse(pacer.claim(null, 0L, INTERVAL, 0L, 0L));
		assertFalse(pacer.claim(STEVE, 0L, 0L, 0L, 0L));
		assertFalse(pacer.claim(STEVE, 0L, -5L, 0L, 0L));
		assertEquals(0, pacer.unanswered(null));
	}
}
