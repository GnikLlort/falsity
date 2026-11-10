package com.falsity.actions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Holds requests that are waiting for the player's confirmation.
 *
 * <p>Some actions (a very large item request, a big or destructive build) are
 * not executed immediately. The handler stores a question plus the action to run
 * once the player answers, and the chat listener asks on its behalf. When the
 * player replies affirmatively the stored action runs on the server thread and
 * its result is returned to be spoken.
 *
 * <p>Confirmations expire, are keyed by player, and are cleared whenever the
 * player starts a new conversation, so a stale "yes" can never trigger an
 * unrelated action and one player can never confirm another's request.
 */
public final class ConfirmationManager {
	private final Map<UUID, Pending> pending = new HashMap<>();
	private final LongSupplier clock;

	public ConfirmationManager(LongSupplier clock) {
		this.clock = clock;
	}

	/** A question waiting for an answer. */
	public record Pending(String question, long expiresAtMillis, Supplier<ActionResult> action) {
		public boolean expired(long nowMillis) {
			return nowMillis > this.expiresAtMillis;
		}
	}

	public synchronized void request(UUID player, String question, Supplier<ActionResult> action, long ttlMillis) {
		if (player == null || question == null || action == null) {
			return;
		}
		this.pending.put(player, new Pending(question, this.clock.getAsLong() + Math.max(0, ttlMillis), action));
	}

	public synchronized boolean hasPending(UUID player) {
		Pending stored = this.pending.get(player);
		if (stored == null) {
			return false;
		}
		if (stored.expired(this.clock.getAsLong())) {
			this.pending.remove(player);
			return false;
		}
		return true;
	}

	/** Runs and removes the pending action for this player, if any. */
	public synchronized Optional<ActionResult> answer(UUID player) {
		Pending stored = this.pending.remove(player);
		if (stored == null || stored.expired(this.clock.getAsLong())) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(stored.action().get());
		} catch (RuntimeException exception) {
			return Optional.of(ActionResult.failure("Falsity tried, but something went wrong."));
		}
	}

	public synchronized void clear(UUID player) {
		this.pending.remove(player);
	}

	public synchronized void expire() {
		long now = this.clock.getAsLong();
		this.pending.entrySet().removeIf(entry -> entry.getValue().expired(now));
	}

	public synchronized int size() {
		return this.pending.size();
	}
}
