package com.falsity.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The last few exchanges between each player and Falsity, kept in memory.
 *
 * <p>Without this the model sees every message in isolation and cannot follow
 * a conversation ("what about the other one?"). The transcript is deliberately
 * short-lived and bounded: a handful of turns per player, each side clipped,
 * dropped after a period of silence. Long-term memory lives in
 * {@link com.falsity.memory.PlayerMemory}; this is only the thread of the
 * current chat.
 *
 * <p>It also remembers what Falsity said recently, so offline lines can avoid
 * repeating themselves.
 */
public final class ConversationHistory {
	/** Turns kept per player. */
	public static final int MAX_TURNS = 12;

	/** Longest text kept for either side of a turn, in characters. */
	public static final int MAX_SIDE_CHARS = 240;

	/** Total characters of history handed to one prompt. */
	public static final int MAX_PROMPT_CHARS = 1200;

	/** A transcript is forgotten after this much silence. */
	public static final long IDLE_EXPIRY_MILLIS = 30L * 60L * 1000L;

	/** One exchange: what the player said and what Falsity answered. */
	public record Turn(String player, String falsity, long atMillis) {
		public Turn {
			player = player == null ? "" : player;
			falsity = falsity == null ? "" : falsity;
		}
	}

	private final Map<UUID, Deque<Turn>> turns = new HashMap<>();

	/** Records one exchange. Blank sides are ignored. */
	public synchronized void record(UUID player, String playerSaid, String falsityReplied, long nowMillis) {
		if (player == null) {
			return;
		}
		String said = clip(playerSaid);
		String replied = clip(falsityReplied);
		if (said.isEmpty() || replied.isEmpty()) {
			return;
		}
		Deque<Turn> deque = this.turns.computeIfAbsent(player, key -> new ArrayDeque<>());
		deque.addLast(new Turn(said, replied, nowMillis));
		while (deque.size() > MAX_TURNS) {
			deque.removeFirst();
		}
	}

	/**
	 * The most recent exchanges, oldest first, limited to {@code maxTurns} and
	 * to {@link #MAX_PROMPT_CHARS} in total. A transcript that has been silent
	 * for longer than {@link #IDLE_EXPIRY_MILLIS} yields nothing.
	 */
	public synchronized List<Turn> recent(UUID player, int maxTurns, long nowMillis) {
		List<Turn> result = new ArrayList<>();
		Deque<Turn> deque = player == null ? null : this.turns.get(player);
		if (deque == null || deque.isEmpty() || maxTurns <= 0) {
			return result;
		}
		if (nowMillis - deque.peekLast().atMillis() > IDLE_EXPIRY_MILLIS) {
			this.turns.remove(player);
			return result;
		}

		int budget = MAX_PROMPT_CHARS;
		Iterator<Turn> newestFirst = deque.descendingIterator();
		while (newestFirst.hasNext() && result.size() < maxTurns) {
			Turn turn = newestFirst.next();
			int cost = turn.player().length() + turn.falsity().length();
			if (cost > budget) {
				break;
			}
			budget -= cost;
			result.add(0, turn);
		}
		return result;
	}

	/** What Falsity said in its last {@code count} replies, newest first. */
	public synchronized List<String> recentReplies(UUID player, int count) {
		List<String> result = new ArrayList<>();
		Deque<Turn> deque = player == null ? null : this.turns.get(player);
		if (deque == null || count <= 0) {
			return result;
		}
		Iterator<Turn> newestFirst = deque.descendingIterator();
		while (newestFirst.hasNext() && result.size() < count) {
			result.add(newestFirst.next().falsity());
		}
		return result;
	}

	/** Number of exchanges currently kept for the player. */
	public synchronized int size(UUID player) {
		Deque<Turn> deque = player == null ? null : this.turns.get(player);
		return deque == null ? 0 : deque.size();
	}

	/** Forgets one player's transcript. */
	public synchronized void clear(UUID player) {
		if (player != null) {
			this.turns.remove(player);
		}
	}

	/** Drops every transcript that has gone quiet. */
	public synchronized void expire(long nowMillis) {
		this.turns.entrySet().removeIf(entry ->
			entry.getValue().isEmpty() || nowMillis - entry.getValue().peekLast().atMillis() > IDLE_EXPIRY_MILLIS);
	}

	private static String clip(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = text.replaceAll("\\s+", " ").trim();
		if (cleaned.length() > MAX_SIDE_CHARS) {
			cleaned = cleaned.substring(0, MAX_SIDE_CHARS - 3).trim() + "...";
		}
		return cleaned;
	}
}
