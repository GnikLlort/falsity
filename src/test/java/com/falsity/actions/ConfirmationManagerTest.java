package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Pending confirmations: stored, answered, cleared and expired. */
class ConfirmationManagerTest {
	private final AtomicLong clock = new AtomicLong(0L);
	private final UUID player = UUID.randomUUID();

	private ConfirmationManager manager() {
		return new ConfirmationManager(clock::get);
	}

	@Test
	void requestStoresPendingAndAnswerRunsIt() {
		ConfirmationManager manager = manager();
		manager.request(player, "Should I?", () -> ActionResult.ok("built it"), 10_000L);
		assertTrue(manager.hasPending(player));

		Optional<ActionResult> answer = manager.answer(player);
		assertTrue(answer.isPresent());
		assertTrue(answer.get().success());
		assertEquals("built it", answer.get().message());
		assertFalse(manager.hasPending(player), "answering consumes the pending action");
	}

	@Test
	void clearDropsPendingWithoutRunning() {
		ConfirmationManager manager = manager();
		manager.request(player, "Should I?", () -> ActionResult.ok("built it"), 10_000L);
		manager.clear(player);
		assertFalse(manager.hasPending(player));
		assertTrue(manager.answer(player).isEmpty());
	}

	@Test
	void pendingExpiresAfterTtl() {
		ConfirmationManager manager = manager();
		manager.request(player, "Should I?", () -> ActionResult.ok("built it"), 1_000L);
		clock.set(2_000L);
		manager.expire();
		assertFalse(manager.hasPending(player), "expired pending must be dropped");
		assertTrue(manager.answer(player).isEmpty());
	}

	@Test
	void onlyOnePendingPerPlayer() {
		ConfirmationManager manager = manager();
		manager.request(player, "first?", () -> ActionResult.ok("one"), 10_000L);
		manager.request(player, "second?", () -> ActionResult.ok("two"), 10_000L);
		assertEquals(1, manager.size());
		assertEquals("two", manager.answer(player).orElseThrow().message(), "latest request wins");
	}

	@Test
	void pendingIsAnswerableJustBeforeTtl() {
		ConfirmationManager manager = manager();
		manager.request(player, "Should I?", () -> ActionResult.ok("built it"), 10_000L);
		clock.set(9_999L);
		assertTrue(manager.answer(player).isPresent(), "still valid just before TTL");
	}
}
