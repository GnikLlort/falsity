package com.falsity.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks which player is currently in a conversation with which Falsity.
 *
 * <p>A player does not have to repeat "Falsity" in every message: once they
 * have addressed Falsity, the next few messages are treated as a follow-up to
 * the same conversation. The window is bounded by an inactivity timeout, so a
 * conversation cannot stay open forever and a later unrelated message cannot be
 * misread as a reply to something long forgotten.
 *
 * <p>State is keyed by player UUID and stores the Falsity UUID, so two players
 * never share a conversation and a player's follow-up cannot reach another
 * player's Falsity.
 */
public final class ConversationContextManager {
	private final int timeoutSeconds;
	private final LongSupplier clock;
	private final Map<UUID, Session> sessions = new HashMap<>();

	public ConversationContextManager(int timeoutSeconds, LongSupplier clock) {
		this.timeoutSeconds = Math.max(0, timeoutSeconds);
		this.clock = clock;
	}

	/** A bound conversation: which Falsity, and when it was last spoken to. */
	private record Session(UUID falsityUuid, long lastActiveMillis) {
	}

	/** Whether the player may continue a conversation with this Falsity right now. */
	public synchronized boolean isActive(UUID player, UUID falsity) {
		Session session = this.sessions.get(player);
		if (session == null || falsity == null || !session.falsityUuid().equals(falsity)) {
			return false;
		}
		return this.clock.getAsLong() - session.lastActiveMillis() <= this.timeoutSeconds * 1000L;
	}

	/** Starts or refreshes a conversation with this Falsity. */
	public synchronized void activate(UUID player, UUID falsity) {
		if (player == null || falsity == null) {
			return;
		}
		this.sessions.put(player, new Session(falsity, this.clock.getAsLong()));
	}

	/** Refreshes the timestamp of an existing conversation, if any. */
	public synchronized void touch(UUID player) {
		Session session = this.sessions.get(player);
		if (session != null) {
			this.sessions.put(player, new Session(session.falsityUuid(), this.clock.getAsLong()));
		}
	}

	/** Forgets the player's conversation entirely. */
	public synchronized void clear(UUID player) {
		this.sessions.remove(player);
	}

	/** Drops every conversation that has gone quiet. */
	public synchronized void expire() {
		long now = this.clock.getAsLong();
		long window = this.timeoutSeconds * 1000L;
		this.sessions.entrySet().removeIf(entry -> now - entry.getValue().lastActiveMillis() > window);
	}

	/** Number of live conversations. Visible for tests. */
	public synchronized int size() {
		return this.sessions.size();
	}
}
