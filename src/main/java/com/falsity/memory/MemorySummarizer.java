package com.falsity.memory;

/**
 * Builds a short, local summary of what Falsity remembers about a player.
 *
 * <p>Summarisation is deliberately local and deterministic: no API call is
 * made to summarise, so it works offline and can never leak memory to a
 * third party. The summary is rebuilt from the durable facts whenever it is
 * refreshed, and capped so prompts stay small.
 */
public final class MemorySummarizer {
	private MemorySummarizer() {
	}

	/** Rebuilds the summary in place and returns it. */
	public static String refresh(PlayerMemory memory, int maxSummaryChars) {
		String summary = build(memory, maxSummaryChars);
		memory.summary = summary;
		return summary;
	}

	/** Builds the summary without mutating the memory. */
	public static String build(PlayerMemory memory, int maxSummaryChars) {
		if (memory == null || memory.facts.isEmpty()) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		builder.append("Remembers about ").append(displayName(memory)).append(": ");

		int shown = 0;
		for (String fact : memory.facts) {
			if (shown >= 8) {
				break;
			}
			if (shown > 0) {
				builder.append("; ");
			}
			builder.append(fact);
			shown++;
		}

		if (memory.facts.size() > shown) {
			builder.append("; and ").append(memory.facts.size() - shown).append(" more things");
		}

		String summary = builder.toString();
		if (maxSummaryChars > 0 && summary.length() > maxSummaryChars) {
			summary = summary.substring(0, maxSummaryChars).trim() + "...";
		}
		return summary;
	}

	private static String displayName(PlayerMemory memory) {
		if (memory.playerName != null && !memory.playerName.isBlank()) {
			return memory.playerName;
		}
		return "this player";
	}
}
