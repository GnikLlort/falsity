package com.falsity.personality;

import com.falsity.memory.PlayerMemory;
import java.util.ArrayList;
import java.util.List;

/**
 * Where Falsity and one player stand with each other right now, in a few
 * plain sentences for the prompt.
 *
 * <p>Nothing here is invented and nothing is stored. Every sentence is read
 * off what the mod already tracks for the player: who the owner is, how long
 * they have known each other, how often they have talked and petted, the live
 * personality values and the mood derived from them, how long the player was
 * away, and how they have treated Falsity lately. The personality values are
 * the ones {@link PersonalityProfile} drifts over time; this class only words
 * them.
 */
public final class RelationshipState {
	/** Longest description produced, in characters. */
	public static final int MAX_CHARS = 460;

	/** A return from an absence is still mentioned this long afterwards. */
	static final long RECENT_RETURN_MILLIS = 12L * 60L * 60L * 1000L;

	/** How many of the latest moments of treatment count as "lately". */
	static final int RECENT_MOMENTS = 6;

	/** A trait at or above this, but short of dominant, still colours the mood. */
	private static final double UNDERCURRENT = 35.0;

	private RelationshipState() {
	}

	/**
	 * Describes the relationship.
	 *
	 * @param memory      what is stored about the player, or null for someone unknown
	 * @param personality the live personality towards the player, or null
	 * @param mood        the mood derived from it, or null
	 * @param isOwner     whether the player is the one this Falsity is bonded to
	 * @param daysAway    whole days the player had been gone when they spoke just now
	 * @param nowMillis   the current time, epoch millis
	 */
	public static String describe(
		PlayerMemory memory,
		PersonalityProfile personality,
		EmotionalState mood,
		boolean isOwner,
		long daysAway,
		long nowMillis
	) {
		String name = displayName(memory);
		if (!isOwner) {
			return (name.isEmpty() ? "This player" : name)
				+ " is a stranger, not your owner. Be polite but reserved; do not pretend to know them.";
		}

		List<String> sentences = new ArrayList<>();
		sentences.add((name.isEmpty() ? "This player" : name) + " is your owner, the one you bonded with.");
		if (memory != null) {
			String history = history(memory, nowMillis);
			if (!history.isEmpty()) {
				sentences.add(history);
			}
		}
		String feelings = feelings(personality, mood);
		if (!feelings.isEmpty()) {
			sentences.add(feelings);
		}
		if (memory != null) {
			String absence = absence(memory, daysAway, nowMillis);
			if (!absence.isEmpty()) {
				sentences.add(absence);
			}
			String treatment = treatment(memory);
			if (!treatment.isEmpty()) {
				sentences.add(treatment);
			}
		}

		StringBuilder text = new StringBuilder();
		for (String sentence : sentences) {
			if (text.length() + sentence.length() + 1 > MAX_CHARS) {
				break;
			}
			if (text.length() > 0) {
				text.append(' ');
			}
			text.append(sentence);
		}
		return text.toString();
	}

	/** How long they have known each other and what they have done together. Only what is counted. */
	static String history(PlayerMemory memory, long nowMillis) {
		List<String> parts = new ArrayList<>();
		if (memory.conversationCount > 0) {
			parts.add("talked " + times(memory.conversationCount));
		}
		if (memory.petCount > 0) {
			parts.add("been petted " + times(memory.petCount));
		}
		boolean metKnown = memory.firstMetEpochMillis > 0L;
		long days = memory.daysKnown(nowMillis);
		if (parts.isEmpty()) {
			// Nothing counted yet. Only say "no past" when nothing else is stored either.
			boolean blank = memory.facts.size() <= 1 && memory.recentInteractions.isEmpty();
			if (metKnown && days >= 1L) {
				return "You have known them for " + days(days) + ".";
			}
			return blank ? "You have only just met, so you share no past yet." : "";
		}
		StringBuilder sentence = new StringBuilder();
		if (!metKnown) {
			sentence.append("You have ");
		} else if (days <= 0L) {
			sentence.append("You met them today and have ");
		} else {
			sentence.append("You have known them for ").append(days(days)).append(" and have ");
		}
		return sentence.append(String.join(" and ", parts)).append('.').toString();
	}

	/**
	 * The live personality values in words: the dominant traits as
	 * {@link PersonalityProfile#describe()} gives them, the darker traits that
	 * are raised without yet dominating, and the mood of the moment.
	 */
	static String feelings(PersonalityProfile personality, EmotionalState mood) {
		if (personality == null) {
			return "";
		}
		StringBuilder sentence = new StringBuilder("Your feelings towards them: ");
		sentence.append(personality.describe().replace("lonely without you", "lonely without them"));
		List<String> under = new ArrayList<>();
		addUndercurrent(under, "a little jealous", personality.jealousy, 60.0);
		addUndercurrent(under, "a little wary", personality.suspicion, 60.0);
		addUndercurrent(under, "a little lonely", personality.loneliness, 60.0);
		addUndercurrent(under, "a little nervous", personality.fear, 70.0);
		if (!under.isEmpty()) {
			sentence.append(", and ").append(String.join(", ", under));
		}
		sentence.append('.');
		if (mood != null) {
			sentence.append(" Mood: ").append(mood.phrase().replace("currently ", "")).append('.');
		}
		return sentence.toString();
	}

	private static void addUndercurrent(List<String> parts, String phrase, double value, double dominantFrom) {
		if (value >= UNDERCURRENT && value < dominantFrom) {
			parts.add(phrase);
		}
	}

	/** A return from an absence of a day or more: either this very message, or one earlier the same day. */
	static String absence(PlayerMemory memory, long daysAway, long nowMillis) {
		if (daysAway >= 1L) {
			return "They have just come back after " + days(daysAway) + " away.";
		}
		if (memory.lastAbsenceDays >= 1L && memory.lastReturnEpochMillis > 0L
			&& nowMillis >= memory.lastReturnEpochMillis
			&& nowMillis - memory.lastReturnEpochMillis <= RECENT_RETURN_MILLIS) {
			return "They came back a short while ago after " + days(memory.lastAbsenceDays) + " away.";
		}
		return "";
	}

	/** How the player has treated Falsity lately, from the kept moments of kind words, harsh words and pets. */
	static String treatment(PlayerMemory memory) {
		if (memory.lastTreatment() == PlayerMemory.HARSH) {
			return "The last thing they said to you was harsh, and it stung.";
		}
		int harsh = memory.recentTreatment(PlayerMemory.HARSH, RECENT_MOMENTS);
		int kind = memory.recentTreatment(PlayerMemory.KIND, RECENT_MOMENTS) + memory.recentTreatment(PlayerMemory.PET, RECENT_MOMENTS);
		if (harsh >= 2 && harsh >= kind) {
			return "They have been harsh with you lately; you are a little guarded, though you still care.";
		}
		if (kind >= 3 && harsh == 0) {
			return "They have been kind to you lately.";
		}
		return "";
	}

	/** The player's name as it may be written into a prompt: short, and nothing but name characters. */
	static String displayName(PlayerMemory memory) {
		if (memory == null || memory.playerName == null) {
			return "";
		}
		String cleaned = memory.playerName.replaceAll("[^\\p{L}\\p{N}_ .\\-]", "").trim();
		return cleaned.length() > 24 ? cleaned.substring(0, 24).trim() : cleaned;
	}

	private static String times(int count) {
		return count == 1 ? "once" : count == 2 ? "twice" : count + " times";
	}

	private static String days(long count) {
		return count == 1L ? "1 day" : count + " days";
	}
}
