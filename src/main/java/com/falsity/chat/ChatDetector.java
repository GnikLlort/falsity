package com.falsity.chat;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an ordinary chat message is meant for Falsity.
 *
 * <p>Detection is deliberately local and case-insensitive, so it works before
 * any network call and never depends on the AI. A message counts as addressed
 * to Falsity only when the name appears as a whole word in a direct-addressing
 * position (such as at the start or end, after a greeting, or set off as a
 * vocative), so embedded substrings and ordinary noun references like
 * "my falsity meter is broken" never trigger a reply.
 *
 * <p>This class never blocks, rewrites or forwards a message: the listener only
 * reads from it. Ordinary chat keeps working exactly as before.
 */
public final class ChatDetector {
	private static final Set<String> NON_VOCATIVE_PREDECESSORS = Set.of(
		"a", "an", "the", "my", "your", "his", "her", "its", "our", "their",
		"this", "that", "these", "those", "some", "any", "no", "every",
		"each", "such", "another", "certain"
	);

	private static final Set<String> ADDRESS_LEAD_INS = Set.of(
		"hey", "hi", "hiya", "hello", "howdy", "yo", "sup",
		"morning", "afternoon", "evening",
		"ok", "okay", "alright",
		"please", "pls", "plz",
		"thanks", "ty", "sorry", "wait", "listen", "look",
		"bye", "goodbye", "cya", "goodnight", "gm", "gn",
		"oh", "ah", "well", "so", "psst", "oi", "ay", "ayy",
		"yes", "yeah", "yep", "sure", "nope"
	);

	private static final Pattern POSSESSIVE_SUFFIX = Pattern.compile("^['\u2019]s(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE);
	private static final Pattern VOCATIVE_FOLLOW_PUNCT = Pattern.compile("^[,:!?]");
	private static final Pattern TRAILING_PUNCT_ONLY = Pattern.compile("^[.,!?;:\u2026'\"\\)\\]]*$");
	private static final Pattern TRAILING_WORD = Pattern.compile("([\\p{L}]+)$");

	/** What comes after a name when the sentence is about that person rather than to them ("... is here", "... was mining"). */
	private static final Pattern STATEMENT_ABOUT = Pattern.compile(
		"^\\s+(?:is|isn'?t|was|wasn'?t|has|hasn'?t|had|will|won'?t|would|can|can'?t|could|did|didn'?t|does|doesn'?t|said|says|went|got|just|"
		+ "died|left|joined|keeps|wants|needs|took|made|built|found|and|or)\\b",
		Pattern.CASE_INSENSITIVE);

	private ChatDetector() {
	}

	/** Whether the message addresses Falsity by name, ignoring case. */
	public static boolean mentions(String message, String falsityName) {
		if (message == null || message.isBlank()) {
			return false;
		}
		Matcher matcher = wordPattern(falsityName).matcher(message);
		while (matcher.find()) {
			if (isDirectMention(message, matcher.start(), matcher.end())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the message is spoken to one of the others named: it uses one of
	 * their names the way a name is used to call someone ("Alex, come here",
	 * "thanks Alex", "where are you Alex?"). A sentence that is merely about
	 * them ("Alex is here", "I saw Alex") does not count.
	 *
	 * <p>This is what keeps a follow-up window from turning Falsity into an
	 * eavesdropper: a player who was just talking to Falsity and then turns to
	 * another player is no longer talking to Falsity.
	 */
	public static boolean addressesSomeoneElse(String message, Collection<String> otherNames) {
		if (message == null || message.isBlank() || otherNames == null) {
			return false;
		}
		for (String name : otherNames) {
			if (name == null || name.isBlank()) {
				continue;
			}
			String cleaned = name.trim();
			// "@Alex" is addressed to Alex wherever it stands and whatever follows.
			if (Pattern.compile("(?<![\\p{L}\\p{N}_])@" + Pattern.quote(cleaned) + "(?![\\p{L}\\p{N}_])",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(message).find()) {
				return true;
			}
			if (mentions(message, cleaned) && !STATEMENT_ABOUT.matcher(afterLeadingName(message, cleaned)).find()) {
				return true;
			}
		}
		return false;
	}

	/** What follows a name that opens the message, or an empty string when the message does not open with it. */
	private static String afterLeadingName(String message, String name) {
		Matcher matcher = wordPattern(name).matcher(message);
		if (matcher.find() && message.substring(0, matcher.start()).isBlank()) {
			return message.substring(matcher.end());
		}
		return "";
	}

	/**
	 * Removes the name from a message and cleans up the separators left behind.
	 * Returns the message unchanged when the name is not present as a mention.
	 */
	public static String stripMention(String message, String falsityName) {
		if (message == null) {
			return "";
		}
		Pattern pattern = wordPattern(falsityName);
		String current = message;
		boolean removed = false;
		while (true) {
			Matcher matcher = pattern.matcher(current);
			boolean foundMention = false;
			while (matcher.find()) {
				if (isDirectMention(current, matcher.start(), matcher.end())) {
					current = current.substring(0, matcher.start()) + current.substring(matcher.end());
					removed = true;
					foundMention = true;
					break;
				}
			}
			if (!foundMention) {
				break;
			}
		}
		if (!removed) {
			return message;
		}
		String cleaned = current.replaceAll("\\s+", " ").trim();
		// Collapse spaces left before punctuation ("Hey , how are you?" -> "Hey, how are you?").
		cleaned = cleaned.replaceAll("\\s+([,.;:!?])", "$1");
		// Collapse duplicate or dangling separators around where the name sat.
		cleaned = cleaned.replaceAll("([,;:])(?:\\s*[,;:])+", "$1");
		cleaned = cleaned.replaceAll("[,;:]\\s*([.!?])", "$1");
		cleaned = cleaned.replaceFirst("^[,.;:!?\\s]+", "");
		cleaned = cleaned.replaceAll("[,;:\\s]+$", "");
		return cleaned.trim();
	}

	/**
	 * Strips the {@code <PlayerName> } prefix that Minecraft adds to broadcast
	 * chat, leaving the raw message text.
	 */
	public static String stripBroadcastPrefix(String decorated) {
		if (decorated == null) {
			return "";
		}
		String text = decorated.trim();
		if (text.startsWith("<")) {
			int close = text.indexOf('>');
			if (close > 0) {
				text = text.substring(close + 1).trim();
			}
		}
		return text;
	}

	/** Whether the message is a plain greeting, with no request attached. */
	public static boolean isPlainGreeting(String message) {
		String normalized = normalizePhrase(message);
		return switch (normalized) {
			case "hi", "hiya", "hello", "hey", "yo", "sup", "good morning", "good afternoon", "good evening", "howdy" -> true;
			default -> false;
		};
	}

	/** Whether the message affirms a request (for confirmation flows). */
	public static boolean isAffirmative(String message) {
		String normalized = normalizePhrase(message);
		return switch (normalized) {
			case "yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "start", "begin", "proceed", "do it", "build it", "confirm" -> true;
			default -> false;
		};
	}

	/** Whether the message declines or cancels a request. */
	public static boolean isNegative(String message) {
		String normalized = normalizePhrase(message);
		return switch (normalized) {
			case "no", "nope", "cancel", "stop", "dont", "never mind", "forget it", "abort" -> true;
			default -> false;
		};
	}

	private static boolean isDirectMention(String message, int start, int end) {
		String before = message.substring(0, start);
		String after = message.substring(end);
		if (POSSESSIVE_SUFFIX.matcher(after).find()) {
			return false;
		}

		String beforeTrimmed = before.stripTrailing();
		if (!beforeTrimmed.isEmpty()) {
			Matcher prevWord = TRAILING_WORD.matcher(beforeTrimmed);
			if (prevWord.find()) {
				String preceding = prevWord.group(1).toLowerCase(Locale.ROOT);
				if (NON_VOCATIVE_PREDECESSORS.contains(preceding)) {
					return false;
				}
			}
		}

		if (beforeTrimmed.isEmpty()) {
			return true;
		}
		char lastBefore = beforeTrimmed.charAt(beforeTrimmed.length() - 1);
		if (",.?;:!".indexOf(lastBefore) >= 0) {
			return true;
		}

		String lowerBefore = beforeTrimmed.toLowerCase(Locale.ROOT);
		if (lowerBefore.endsWith("thank you") || lowerBefore.endsWith("excuse me")) {
			return true;
		}
		Matcher leadIn = TRAILING_WORD.matcher(lowerBefore);
		if (leadIn.find() && ADDRESS_LEAD_INS.contains(leadIn.group(1))) {
			return true;
		}

		String afterTrimmed = after.stripLeading();
		if (TRAILING_PUNCT_ONLY.matcher(afterTrimmed).matches()) {
			return true;
		}
		return VOCATIVE_FOLLOW_PUNCT.matcher(afterTrimmed).find();
	}

	private static String normalizePhrase(String message) {
		if (message == null) {
			return "";
		}
		return message.toLowerCase(Locale.ROOT)
			.replace("'", "")
			.replace("\u2019", "")
			.replaceAll("[^a-z\\s]", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	/** Builds a case-insensitive whole-word pattern for a name. */
	private static Pattern wordPattern(String falsityName) {
		if (falsityName == null || falsityName.isBlank()) {
			// Never match everything: a missing name means "nobody".
			return Pattern.compile("(?!x)x");
		}
		String cleaned = falsityName.trim().replaceAll("[\\s.,!?;:\"']", "");
		if (cleaned.isEmpty()) {
			return Pattern.compile("(?!x)x");
		}
		// Falcity is a common phonetic misspelling seen in live chat. Keep the
		// alias deliberately narrow: fuzzy matching arbitrary words would make
		// Falsity interrupt unrelated multiplayer conversations.
		String names = cleaned.equalsIgnoreCase("Falsity")
			? "(?:" + Pattern.quote(cleaned) + "|Falcity)"
			: Pattern.quote(cleaned);
		return Pattern.compile(
			"(?<![\\p{L}\\p{N}_])" + names + "(?![\\p{L}\\p{N}_])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
		);
	}
}
