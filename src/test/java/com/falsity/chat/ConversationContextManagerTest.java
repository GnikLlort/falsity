package com.falsity.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Warm-context tracking: who is in an active conversation, and for how long. */
class ConversationContextManagerTest {
	private final AtomicLong clock = new AtomicLong(0L);
	private final UUID player = UUID.randomUUID();
	private final UUID falsity = UUID.randomUUID();

	private ConversationContextManager manager(int timeoutSeconds) {
		return new ConversationContextManager(timeoutSeconds, clock::get);
	}

	@Test
	void activateThenTouchKeepsContextWarm() {
		ConversationContextManager manager = manager(30);
		manager.activate(player, falsity);
		assertTrue(manager.isActive(player, falsity));

		clock.set(20_000L);
		manager.touch(player);
		assertTrue(manager.isActive(player, falsity), "touch extends the window");
	}

	@Test
	void contextExpiresAfterTimeoutWithoutTouch() {
		ConversationContextManager manager = manager(10);
		manager.activate(player, falsity);
		clock.set(11_000L);
		manager.expire();
		assertFalse(manager.isActive(player, falsity));
	}

	@Test
	void contextIsScopedToTheFalsity() {
		ConversationContextManager manager = manager(30);
		UUID other = UUID.randomUUID();
		manager.activate(player, falsity);
		assertFalse(manager.isActive(player, other), "a different companion is not in this conversation");
		assertTrue(manager.isActive(player, falsity));
	}

	@Test
	void clearDropsContextImmediately() {
		ConversationContextManager manager = manager(30);
		manager.activate(player, falsity);
		manager.clear(player);
		assertFalse(manager.isActive(player, falsity));
		assertTrue(manager.size() == 0);
	}

	@Test
	void differentPlayersAreIndependent() {
		ConversationContextManager manager = manager(30);
		UUID second = UUID.randomUUID();
		manager.activate(player, falsity);
		manager.activate(second, falsity);
		assertTrue(manager.isActive(player, falsity));
		assertTrue(manager.isActive(second, falsity));
		manager.clear(player);
		assertFalse(manager.isActive(player, falsity));
		assertTrue(manager.isActive(second, falsity), "clearing one player leaves the other");
	}
}
