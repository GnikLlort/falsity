package com.falsity.memory;

import com.falsity.personality.PersonalityProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything Falsity remembers about one player.
 *
 * <p>One instance per player UUID, persisted as one JSON file by
 * {@link MemoryStorage}. This is a plain data holder with small, pure
 * behaviours so it can be unit tested without a game: recording, trimming to
 * limits, and picking relevant memories for a prompt.
 *
 * <p>Memory is deliberately bounded. Facts and recent interactions are capped
 * (see {@code FalsityConfig.Memory}); when a cap is hit the oldest entries
 * are dropped first, so a long-lived world cannot grow without limit. The
 * full history is never sent to Groq: {@link #relevantMemories(String, int)}
 * selects only the entries that share words with the current message.
 */
public final class PlayerMemory {
	/** Player UUID as a string, so a damaged file can never throw on load. */
	public String playerUuid = "";
	/** Last known player name. Display only; the UUID is the identity. */
	public String playerName = "";
	/** Epoch millis of the first meeting, or 0 if unknown. */
	public long firstMetEpochMillis;
	/** Epoch millis of the most recent interaction, or 0 if none yet. */
	public long lastSeenEpochMillis;
	/** How many conversations Falsity has had with this player. */
	public int conversationCount;
	/** How many times the player has petted Falsity. */
	public int petCount;
	/** Durable facts: names, preferences, promises, significant events. */
	public List<String> facts = new ArrayList<>();
	/** Short recent interaction notes, newest last. */
	public List<String> recentInteractions = new ArrayList<>();
	/** Locally generated summary, refreshed by {@link MemorySummarizer}. */
	public String summary = "";
	/** Live personality for this relationship. Never null after load. */
	public PersonalityProfile personality = new PersonalityProfile();
	/** Horror progression state for this relationship. Never null after load. */
	public HorrorState horror = new HorrorState();

	/** Horror progression state, stored with the memory it belongs to. */
	public static final class HorrorState {
		/** Current stage ordinal (see {@code HorrorStage}). */
		public int stage;
		/** Accumulated tension; stages are thresholds over this value. */
		public double tension;
		/** Interactions counted towards progression. */
		public int interactionCount;
		/** Epoch millis of the last stage change, or 0 if never changed. */
		public long lastStageChangeEpochMillis;
	}

	public PlayerMemory() {
	}

	public PlayerMemory(String playerUuid, String playerName, long nowEpochMillis) {
		this.playerUuid = playerUuid;
		this.playerName = playerName;
		this.firstMetEpochMillis = nowEpochMillis;
		this.lastSeenEpochMillis = nowEpochMillis;
	}

	/** Records that the player interacted with Falsity just now. */
	public void touch(long nowEpochMillis) {
		if (this.firstMetEpochMillis <= 0L) {
			this.firstMetEpochMillis = nowEpochMillis;
		}
		this.lastSeenEpochMillis = nowEpochMillis;
	}

	/** Records a conversation exchange. Both sides are kept short. */
	public void recordConversation(String playerSaid, String falsityReplied, int maxRecent) {
		this.conversationCount++;
		addRecent("Player: " + truncate(playerSaid, 120), maxRecent);
		addRecent("Falsity: " + truncate(falsityReplied, 120), maxRecent);
	}

	/** Records a petting interaction. */
	public void recordPet(int maxRecent) {
		this.petCount++;
		addRecent("Petted Falsity.", maxRecent);
	}

	/** Records an order (sit / follow). */
	public void recordOrder(String order, int maxRecent) {
		addRecent("Order: " + order + ".", maxRecent);
	}

	/** Adds a durable fact, dropping the oldest when the cap is reached. */
	public void recordFact(String fact, int maxStoredFacts) {
		String cleaned = clean(fact);
		if (cleaned.isEmpty() || maxStoredFacts <= 0) {
			return;
		}
		// Avoid exact duplicates: remembering the same thing twice adds nothing.
		if (this.facts.contains(cleaned)) {
			return;
		}
		this.facts.add(cleaned);
		while (this.facts.size() > maxStoredFacts) {
			this.facts.remove(0);
		}
	}

	/** Adds a recent-interaction note, dropping the oldest past the cap. */
	public void addRecent(String note, int maxRecent) {
		String cleaned = clean(note);
		if (cleaned.isEmpty() || maxRecent <= 0) {
			return;
		}
		this.recentInteractions.add(cleaned);
		while (this.recentInteractions.size() > maxRecent) {
			this.recentInteractions.remove(0);
		}
	}

	/**
	 * Forgets facts older than the retention window.
	 *
	 * <p>Facts do not carry timestamps of their own, so retention is applied
	 * to the whole list only when the player has been absent for longer than
	 * the window: stale trivia is dropped, but the summary (which the
	 * summariser rebuilds from what remains) is kept. A window of 0 disables
	 * forgetting.
	 */
	public void applyRetention(long nowEpochMillis, int retentionDays) {
		if (retentionDays <= 0 || this.lastSeenEpochMillis <= 0L) {
			return;
		}
		long absentMillis = nowEpochMillis - this.lastSeenEpochMillis;
		long windowMillis = retentionDays * 24L * 60L * 60L * 1000L;
		if (absentMillis > windowMillis) {
			// Long absence: keep only the most recent half of the facts.
			int keep = Math.max(1, this.facts.size() / 2);
			while (this.facts.size() > keep) {
				this.facts.remove(0);
			}
		}
	}

	/**
	 * Picks the facts and recent notes most relevant to a player message.
	 *
	 * <p>Scoring is simple keyword overlap: entries sharing more words with
	 * the message rank higher. Ties keep insertion order (oldest first), so
	 * long-standing facts win over throwaway recent notes. The result is
	 * capped so prompts stay small.
	 */
	public List<String> relevantMemories(String query, int maxResults) {
		List<String> ranked = new ArrayList<>();
		if (maxResults <= 0) {
			return ranked;
		}

		Map<String, Integer> scores = new LinkedHashMap<>();
		List<String> queryWords = words(query);
		if (queryWords.isEmpty()) {
			// No signal: return the most recent facts, which is the best guess.
			for (int i = Math.max(0, this.facts.size() - maxResults); i < this.facts.size(); i++) {
				ranked.add(this.facts.get(i));
			}
			return ranked;
		}

		for (String fact : this.facts) {
			scores.put(fact, overlap(queryWords, words(fact)));
		}
		for (String note : this.recentInteractions) {
			scores.merge(note, overlap(queryWords, words(note)), Integer::sum);
		}

		List<Map.Entry<String, Integer>> entries = new ArrayList<>(scores.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		for (Map.Entry<String, Integer> entry : entries) {
			if (ranked.size() >= maxResults) {
				break;
			}
			if (entry.getValue() > 0) {
				ranked.add(entry.getKey());
			}
		}

		if (ranked.isEmpty()) {
			// Nothing matched: fall back to the newest facts so the prompt
			// still carries some context rather than none.
			for (int i = Math.max(0, this.facts.size() - maxResults); i < this.facts.size(); i++) {
				ranked.add(this.facts.get(i));
			}
		}

		return ranked;
	}

	/** Days since the player was last seen, or 0 if never. */
	public long daysSinceSeen(long nowEpochMillis) {
		if (this.lastSeenEpochMillis <= 0L) {
			return 0L;
		}
		long millis = Math.max(0L, nowEpochMillis - this.lastSeenEpochMillis);
		return millis / (24L * 60L * 60L * 1000L);
	}

	/** Normalises nulls after deserialisation so callers never null-check. */
	public void sanitise() {
		if (this.playerUuid == null) {
			this.playerUuid = "";
		}
		if (this.playerName == null) {
			this.playerName = "";
		}
		if (this.facts == null) {
			this.facts = new ArrayList<>();
		}
		if (this.recentInteractions == null) {
			this.recentInteractions = new ArrayList<>();
		}
		if (this.summary == null) {
			this.summary = "";
		}
		if (this.personality == null) {
			this.personality = new PersonalityProfile();
		}
		if (this.horror == null) {
			this.horror = new HorrorState();
		}
	}

	private static String clean(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("\\s+", " ").trim();
	}

	private static String truncate(String text, int maxChars) {
		String cleaned = clean(text);
		if (cleaned.length() <= maxChars) {
			return cleaned;
		}
		return cleaned.substring(0, maxChars).trim() + "...";
	}

	private static List<String> words(String text) {
		List<String> result = new ArrayList<>();
		if (text == null) {
			return result;
		}
		for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (word.length() >= 3) {
				result.add(word);
			}
		}
		return result;
	}

	private static int overlap(List<String> queryWords, List<String> entryWords) {
		int score = 0;
		for (String queryWord : queryWords) {
			if (entryWords.contains(queryWord)) {
				score++;
			}
		}
		return score;
	}
}
