package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The short transcript that lets Falsity follow a conversation. */
class ConversationHistoryTest {
	private final UUID alex = UUID.randomUUID();
	private final UUID steve = UUID.randomUUID();

	@Test
	void returnsRecentExchangesOldestFirst() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "hello", "Hi Alex!", 1_000L);
		history.record(this.alex, "what is your favourite colour", "Blue, like the sky.", 2_000L);
		history.record(this.alex, "why", "Because it never ends.", 3_000L);

		List<ConversationHistory.Turn> recent = history.recent(this.alex, 2, 4_000L);

		assertEquals(2, recent.size());
		assertEquals("what is your favourite colour", recent.get(0).player());
		assertEquals("Blue, like the sky.", recent.get(0).falsity());
		assertEquals("why", recent.get(1).player());
		assertEquals(3, history.size(this.alex));
	}

	@Test
	void keepsEachPlayerSeparate() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "my secret is 42", "I will keep it.", 1_000L);

		assertTrue(history.recent(this.steve, 6, 2_000L).isEmpty(), "one player's words never reach another's prompt");
		assertTrue(history.recentReplies(this.steve, 6).isEmpty());
	}

	@Test
	void keepsOnlyTheLastFewExchanges() {
		ConversationHistory history = new ConversationHistory();
		for (int i = 0; i < 40; i++) {
			history.record(this.alex, "message " + i, "reply " + i, 1_000L + i);
		}

		assertEquals(ConversationHistory.MAX_TURNS, history.size(this.alex));
		List<ConversationHistory.Turn> recent = history.recent(this.alex, 100, 2_000L);
		assertEquals("message 39", recent.get(recent.size() - 1).player());
		assertEquals("message " + (40 - ConversationHistory.MAX_TURNS), recent.get(0).player());
	}

	@Test
	void staysWithinThePromptBudget() {
		ConversationHistory history = new ConversationHistory();
		String longLine = "x".repeat(500);
		for (int i = 0; i < 12; i++) {
			history.record(this.alex, longLine, longLine, 1_000L + i);
		}

		int total = 0;
		for (ConversationHistory.Turn turn : history.recent(this.alex, 12, 2_000L)) {
			assertTrue(turn.player().length() <= ConversationHistory.MAX_SIDE_CHARS);
			assertTrue(turn.falsity().length() <= ConversationHistory.MAX_SIDE_CHARS);
			total += turn.player().length() + turn.falsity().length();
		}
		assertTrue(total > 0, "at least the latest exchange fits");
		assertTrue(total <= ConversationHistory.MAX_PROMPT_CHARS, "history must not crowd out the rest of the prompt: " + total);
	}

	@Test
	void anOldConversationIsNotContinued() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "see you tomorrow", "Goodnight!", 1_000L);

		assertEquals(1, history.recent(this.alex, 6, 1_000L + ConversationHistory.IDLE_EXPIRY_MILLIS).size());
		assertTrue(history.recent(this.alex, 6, 1_001L + ConversationHistory.IDLE_EXPIRY_MILLIS).isEmpty());
		assertEquals(0, history.size(this.alex), "and it is forgotten, not just hidden");
	}

	@Test
	void recentRepliesAreNewestFirstForAvoidingRepeats() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "a", "one", 1L);
		history.record(this.alex, "b", "two", 2L);
		history.record(this.alex, "c", "three", 3L);

		assertEquals(List.of("three", "two"), history.recentReplies(this.alex, 2));
		assertEquals(List.of("three", "two", "one"), history.recentReplies(this.alex, 10));
		assertTrue(history.recentReplies(this.alex, 0).isEmpty());
	}

	@Test
	void ignoresEmptySidesAndTidiesWhitespace() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "   ", "reply", 1L);
		history.record(this.alex, "said", null, 1L);
		history.record(null, "said", "reply", 1L);
		history.record(this.alex, "  two\n\nlines  ", " a   reply ", 2L);

		List<ConversationHistory.Turn> recent = history.recent(this.alex, 6, 3L);
		assertEquals(1, recent.size());
		assertEquals("two lines", recent.get(0).player());
		assertEquals("a reply", recent.get(0).falsity());
	}

	@Test
	void clearAndExpireForget() {
		ConversationHistory history = new ConversationHistory();
		history.record(this.alex, "a", "one", 1L);
		history.record(this.steve, "b", "two", 1L + ConversationHistory.IDLE_EXPIRY_MILLIS);

		history.expire(2L + ConversationHistory.IDLE_EXPIRY_MILLIS);
		assertEquals(0, history.size(this.alex));
		assertEquals(1, history.size(this.steve));

		history.clear(this.steve);
		assertEquals(0, history.size(this.steve));
	}
}
