package com.falsity.chat;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether an ordinary chat message is meant for Falsity.
 *
 * <p>Detection is deliberately local and case-insensitive, so it works before
 * any network call and never depends on the AI. A message counts as addressed
 * to Falsity when the name appears as a whole word anywhere in it: that covers
 * "Hey Falsity, how are you?", "Falsity, can you help me?" and
 * "Can you come here, Falsity?" without a pile of special cases.
 *
 * <p>This class never blocks, rewrites or forwards a message: the listener only
 * reads from it. Ordinary chat keeps working exactly as before.
 */
public final class ChatDetector {
	private ChatDetector() {
	}

	/** Whether the message addresses Falsity by name, ignoring case. */
	public static boolean mentions(String message, String falsityName) {
		return wordPattern(falsityName).matcher(message).find();
	}

	/**
	 * Removes the name from a message and cleans up the separators left behind.
	 * Returns the message unchanged when the name is not present.
	 */
	public static String stripMention(String message, String falsityName) {
		if (message == null) {
			return "";
		}
		String withoutName = wordPattern(falsityName).matcher(message).replaceFirst("");
		withoutName = withoutName.replaceAll("\\s+", " ").trim();
		// "Hey Falsity, how are you?" -> "Hey, how are you?" -> "Hey, how are you?"
		// "Falsity, come here." -> ", come here." -> "come here."
		withoutName = withoutName.replaceFirst("^[,.;:!?\\s]+", "");
		return withoutName.trim();
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
		if (message == null) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s!?.]", "").trim();
		return switch (lower) {
			case "hi", "hiya", "hello", "hey", "yo", "sup", "good morning", "good afternoon", "good evening", "howdy" -> true;
			default -> false;
		};
	}

	/** Whether the message affirms a request (for confirmation flows). */
	public static boolean isAffirmative(String message) {
		if (message == null) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s!.]", " ").replaceAll("\\s+", " ").trim();
		return switch (lower) {
			case "yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "start", "begin", "proceed", "do it", "build it", "confirm" -> true;
			default -> false;
		};
	}

	/** Whether the message declines or cancels a request. */
	public static boolean isNegative(String message) {
		if (message == null) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s!.]", " ").replaceAll("\\s+", " ").trim();
		return switch (lower) {
			case "no", "nope", "cancel", "stop", "dont", "don't", "never mind", "forget it", "abort" -> true;
			default -> false;
		};
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
		return Pattern.compile("\\b" + Pattern.quote(cleaned) + "\\b", Pattern.CASE_INSENSITIVE);
	}
}
