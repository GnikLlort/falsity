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
	/**
	 * How the player has treated Falsity lately, one letter per moment, newest
	 * last: {@code K} kind words, {@code H} harsh words, {@code P} a pet,
	 * {@code N} ordinary talk. Capped at {@link #MAX_TREATMENT_MOMENTS}.
	 */
	public String treatment = "";
	/** How many times the player has said something kind to Falsity. */
	public int kindWords;
	/** How many times the player has said something harsh to Falsity. */
	public int harshWords;
	/** Length in days of the player's most recent absence of a day or more, or 0. */
	public long lastAbsenceDays;
	/** Epoch millis of the moment the player came back from that absence, or 0. */
	public long lastReturnEpochMillis;
	/** Live personality for this relationship. Never null after load. */
	public PersonalityProfile personality = new PersonalityProfile();
	/** Horror progression state for this relationship. Never null after load. */
	public HorrorState horror = new HorrorState();

	/** What a recent note of something the player said starts with. */
	public static final String PLAYER_PREFIX = "Player: ";
	/** What a recent note of something Falsity said starts with. */
	public static final String FALSITY_PREFIX = "Falsity: ";

	/** How many recent moments of treatment are kept. */
	public static final int MAX_TREATMENT_MOMENTS = 16;

	/** A kind thing said to Falsity. */
	public static final char KIND = 'K';
	/** A harsh thing said to Falsity. */
	public static final char HARSH = 'H';
	/** A pet. */
	public static final char PET = 'P';
	/** Ordinary talk: neither kind nor harsh. */
	public static final char NEUTRAL = 'N';

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
		addRecent(PLAYER_PREFIX + truncate(playerSaid, 120), maxRecent);
		addRecent(FALSITY_PREFIX + truncate(falsityReplied, 120), maxRecent);
	}

	/**
	 * Records something Falsity said without being spoken to. It is kept as a
	 * note like any other line of Falsity's, but it is not a conversation: the
	 * player has not done anything, so nothing is counted.
	 */
	public void recordUnprompted(String falsitySaid, int maxRecent) {
		String line = truncate(falsitySaid, 120);
		if (!line.isEmpty()) {
			addRecent(FALSITY_PREFIX + line, maxRecent);
		}
	}

	/** Records a petting interaction. */
	public void recordPet(int maxRecent) {
		this.petCount++;
		addRecent("Petted Falsity.", maxRecent);
		recordTreatment(PET);
	}

	/**
	 * Records one moment of how the player treated Falsity: {@link #KIND},
	 * {@link #HARSH}, {@link #PET} or {@link #NEUTRAL}. Anything else is
	 * ignored. Only the most recent moments are kept.
	 */
	public void recordTreatment(char moment) {
		if (moment != KIND && moment != HARSH && moment != PET && moment != NEUTRAL) {
			return;
		}
		if (moment == KIND) {
			this.kindWords++;
		} else if (moment == HARSH) {
			this.harshWords++;
		}
		String log = (this.treatment == null ? "" : this.treatment) + moment;
		if (log.length() > MAX_TREATMENT_MOMENTS) {
			log = log.substring(log.length() - MAX_TREATMENT_MOMENTS);
		}
		this.treatment = log;
	}

	/** How many of the last {@code window} moments of treatment were of one kind. */
	public int recentTreatment(char moment, int window) {
		String log = this.treatment == null ? "" : this.treatment;
		int count = 0;
		for (int index = Math.max(0, log.length() - Math.max(0, window)); index < log.length(); index++) {
			if (log.charAt(index) == moment) {
				count++;
			}
		}
		return count;
	}

	/** The most recent moment of treatment, or 0 when there is none. */
	public char lastTreatment() {
		String log = this.treatment == null ? "" : this.treatment;
		return log.isEmpty() ? 0 : log.charAt(log.length() - 1);
	}

	/**
	 * Records that the player has just come back after {@code daysAway} days.
	 * Absences shorter than a day are not recorded.
	 */
	public void recordReturn(long nowEpochMillis, long daysAway) {
		if (daysAway < 1L) {
			return;
		}
		this.lastAbsenceDays = daysAway;
		this.lastReturnEpochMillis = nowEpochMillis;
	}

	/** Whole days since the first meeting, or 0 when it is unknown. */
	public long daysKnown(long nowEpochMillis) {
		if (this.firstMetEpochMillis <= 0L) {
			return 0L;
		}
		return Math.max(0L, nowEpochMillis - this.firstMetEpochMillis) / (24L * 60L * 60L * 1000L);
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

	/** One remembered exchange: what the player said and what Falsity answered. */
	public record Exchange(String player, String falsity) {
	}

	/**
	 * The last {@code max} exchanges still held in the recent notes, oldest
	 * first. These survive a restart, unlike the live transcript, so they are
	 * what lets Falsity pick up where the two of them left off.
	 */
	public List<Exchange> lastExchanges(int max) {
		List<Exchange> found = new ArrayList<>();
		if (max <= 0) {
			return found;
		}
		for (int index = this.recentInteractions.size() - 1; index >= 1 && found.size() < max; index--) {
			String answer = this.recentInteractions.get(index);
			String said = this.recentInteractions.get(index - 1);
			if (answer != null && said != null && answer.startsWith(FALSITY_PREFIX) && said.startsWith(PLAYER_PREFIX)) {
				String playerText = said.substring(PLAYER_PREFIX.length()).trim();
				String falsityText = answer.substring(FALSITY_PREFIX.length()).trim();
				if (!playerText.isEmpty() && !falsityText.isEmpty()) {
					found.add(0, new Exchange(playerText, falsityText));
				}
				index--;
			}
		}
		return found;
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
		if (this.treatment == null) {
			this.treatment = "";
		}
		StringBuilder moments = new StringBuilder();
		for (int index = 0; index < this.treatment.length(); index++) {
			char moment = this.treatment.charAt(index);
			if (moment == KIND || moment == HARSH || moment == PET || moment == NEUTRAL) {
				moments.append(moment);
			}
		}
		if (moments.length() > MAX_TREATMENT_MOMENTS) {
			moments.delete(0, moments.length() - MAX_TREATMENT_MOMENTS);
		}
		this.treatment = moments.toString();
		this.kindWords = Math.max(0, this.kindWords);
		this.harshWords = Math.max(0, this.harshWords);
		this.lastAbsenceDays = Math.max(0L, this.lastAbsenceDays);
		this.lastReturnEpochMillis = Math.max(0L, this.lastReturnEpochMillis);
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
