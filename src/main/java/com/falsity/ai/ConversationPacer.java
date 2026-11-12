package com.falsity.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paces conversations per player without ever throwing a message away.
 *
 * <p>A player may only start one request per cooldown, and only one at a time.
 * A message that arrives too early is not dropped: it waits here and is
 * released as soon as the player's cooldown has passed and the previous reply
 * has arrived. One message waits per player; a newer one replaces it, and the
 * caller is handed the old one so it can be merged rather than lost.
 *
 * <p>Pure and clock-free: every method takes the current time, so the rules
 * are unit tested without waiting.
 *
 * @param <T> what the caller needs to resume a waiting message
 */
public final class ConversationPacer<T> {
	/** A request that never reports back stops counting as busy after this long. */
	public static final long BUSY_TIMEOUT_MILLIS = 90_000L;

	/** A waiting message that is now due. */
	public record Ready<T>(UUID player, T payload) {
	}

	private record Waiting<T>(T payload, long notBeforeMillis) {
	}

	private final Map<UUID, Long> lastStartMillis = new HashMap<>();
	private final Map<UUID, Long> busySinceMillis = new HashMap<>();
	private final Map<UUID, Waiting<T>> waiting = new LinkedHashMap<>();

	/**
	 * Claims the player's cooldown slot if it is free. Returns false while the
	 * cooldown from the last start is still running.
	 */
	public synchronized boolean tryStart(UUID player, long nowMillis, long cooldownMillis) {
		Long last = this.lastStartMillis.get(player);
		if (last != null && nowMillis - last < cooldownMillis) {
			return false;
		}
		this.lastStartMillis.put(player, nowMillis);
		return true;
	}

	/** Milliseconds until the player's cooldown ends; zero when it already has. */
	public synchronized long remainingMillis(UUID player, long nowMillis, long cooldownMillis) {
		Long last = this.lastStartMillis.get(player);
		if (last == null) {
			return 0L;
		}
		return Math.max(0L, cooldownMillis - (nowMillis - last));
	}

	/** Marks that a request for the player is on its way. */
	public synchronized void markBusy(UUID player, long nowMillis) {
		this.busySinceMillis.put(player, nowMillis);
	}

	/** Marks that the player's request has come back. */
	public synchronized void markIdle(UUID player) {
		this.busySinceMillis.remove(player);
	}

	/** Whether a request for the player is still on its way. */
	public synchronized boolean isBusy(UUID player, long nowMillis) {
		Long since = this.busySinceMillis.get(player);
		if (since == null) {
			return false;
		}
		if (nowMillis - since > BUSY_TIMEOUT_MILLIS) {
			this.busySinceMillis.remove(player);
			return false;
		}
		return true;
	}

	/**
	 * Holds a message until the player may talk again, and not before
	 * {@code notBeforeMillis}. Returns the message that was already waiting for
	 * this player, if any, so the caller can merge the two.
	 */
	public synchronized T enqueue(UUID player, T payload, long notBeforeMillis) {
		Waiting<T> previous = this.waiting.put(player, new Waiting<>(payload, notBeforeMillis));
		return previous == null ? null : previous.payload();
	}

	/** The message currently waiting for the player, or null. */
	public synchronized T peek(UUID player) {
		Waiting<T> entry = this.waiting.get(player);
		return entry == null ? null : entry.payload();
	}

	/**
	 * Releases every waiting message whose player is no longer busy and whose
	 * cooldown has passed, claiming the cooldown slot for each.
	 */
	public synchronized List<Ready<T>> takeReady(long nowMillis, long cooldownMillis) {
		List<Ready<T>> ready = new ArrayList<>();
		Iterator<Map.Entry<UUID, Waiting<T>>> iterator = this.waiting.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Waiting<T>> entry = iterator.next();
			UUID player = entry.getKey();
			if (nowMillis < entry.getValue().notBeforeMillis() || isBusy(player, nowMillis)) {
				continue;
			}
			Long last = this.lastStartMillis.get(player);
			if (last != null && nowMillis - last < cooldownMillis) {
				continue;
			}
			this.lastStartMillis.put(player, nowMillis);
			ready.add(new Ready<>(player, entry.getValue().payload()));
			iterator.remove();
		}
		return ready;
	}

	/** How many messages are waiting. */
	public synchronized int waitingCount() {
		return this.waiting.size();
	}

	/** Forgets everything about one player. */
	public synchronized void forget(UUID player) {
		this.lastStartMillis.remove(player);
		this.busySinceMillis.remove(player);
		this.waiting.remove(player);
	}
}
