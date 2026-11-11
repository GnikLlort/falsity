package com.falsity.world;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops a model's reply from stating coordinates or distances the server did
 * not verify.
 *
 * <p>A language model will happily answer "the village is at 120, 64, -30" from
 * nothing. The prompt tells it not to, but a rule in a prompt is a request, not
 * a guarantee. This check is the guarantee: a reply that quotes a position, a
 * distance or a compass bearing is only let through when every number in that
 * claim appears in the facts the server supplied for the same message.
 * Otherwise the reply is rejected and Falsity says it does not know.
 */
public final class FactGuard {
	/** "x=120", "y: 64", "z -30". Not "x32", which is how people write "32 of them". */
	private static final Pattern LABELLED = Pattern.compile(
		"\\b[xyz](?:\\s*[:=]\\s*|\\s+)(-?\\d{1,7})\\b", Pattern.CASE_INSENSITIVE);

	/** "120, 64, -30", "(120, -30)". */
	private static final Pattern TUPLE = Pattern.compile(
		"(?<![\\w,])(?<!\\d\\.)(-?\\d{1,7})\\s*,\\s*(-?\\d{1,7})(?:\\s*,\\s*(-?\\d{1,7}))?(?!\\w|\\.\\d)");

	/** "1,000" and "12,500,000": one number written with separators, not a pair of coordinates. */
	private static final Pattern THOUSANDS = Pattern.compile("(?<![\\w,])(?<!\\d\\.)\\d{1,3}(?:,\\d{3})+(?!\\w|,\\d|\\.\\d)");

	/** "120 / 64 / -30". Two numbers with a slash ("24/7", "50/50", "10/10") are not a position. */
	private static final Pattern SLASHED = Pattern.compile(
		"(?<![\\w/])(?<!\\d\\.)(-?\\d{1,7})\\s*/\\s*(-?\\d{1,7})\\s*/\\s*(-?\\d{1,7})(?!\\w|/|\\.\\d)");

	/**
	 * "200 blocks away", "about 3 chunks north", "12 blocks down". "Up" and
	 * "down" only count right after the distance, since "hurry up" and "calm
	 * down" turn up in ordinary sentences.
	 */
	private static final Pattern DISTANCE = Pattern.compile(
		"(-?\\d{1,7})\\s*(?:blocks?|chunks?|meters?|metres?|m)\\b"
		+ "(?=\\s+(?:straight\\s+|further\\s+)?(?:up|down)\\b"
		+ "|[^.!?]{0,40}\\b(?:away|north|south|east|west|ahead|behind|from here|from us|from you|below|above|deep|underground)\\b)",
		Pattern.CASE_INSENSITIVE);

	/** "at 120 64 -30", "coordinates 120 64 -30". */
	private static final Pattern SPACED = Pattern.compile(
		"\\b(?:at|to|coordinates?|coords|position|located|location)\\s*:?\\s*(-?\\d{1,7})\\s+(-?\\d{1,7})(?:\\s+(-?\\d{1,7}))?\\b",
		Pattern.CASE_INSENSITIVE);

	/** "Y level 12", "height 64", "layer -59". Not "level 30", which is experience. */
	private static final Pattern LEVEL = Pattern.compile(
		"\\b(?:y[- ]?level|height|layer|altitude)\\s*(?:of\\s*)?(-?\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern NUMBER = Pattern.compile("-?\\d+");

	private FactGuard() {
	}

	/**
	 * Whether the reply states a position or distance that is not backed by
	 * {@code verifiedFacts}. Pass an empty string when nothing was verified.
	 */
	public static boolean hasUnverifiedLocation(String reply, String verifiedFacts) {
		if (reply == null || reply.isBlank()) {
			return false;
		}
		Set<String> verified = numbersIn(verifiedFacts);
		// "1,000 years" is one number; without its separators it cannot be read as a pair.
		String text = joinThousands(reply);
		return claims(LABELLED, text, verified)
			|| tupleClaims(text, verified)
			|| claims(SLASHED, text, verified)
			|| claims(DISTANCE, text, verified)
			|| claims(SPACED, text, verified)
			|| claims(LEVEL, text, verified);
	}

	/** Writes "1,000" as "1000". A pair in brackets, "(120,640)", is left alone: that is a position. */
	private static String joinThousands(String text) {
		Matcher matcher = THOUSANDS.matcher(text);
		StringBuilder builder = new StringBuilder();
		while (matcher.find()) {
			boolean bracketed = matcher.start() > 0 && text.charAt(matcher.start() - 1) == '('
				&& matcher.end() < text.length() && text.charAt(matcher.end()) == ')';
			matcher.appendReplacement(builder, bracketed ? matcher.group() : matcher.group().replace(",", ""));
		}
		matcher.appendTail(builder);
		return builder.toString();
	}

	/**
	 * Like {@link #claims}, for number lists. Counting aloud ("1, 2, 3" or "3,
	 * 2, 1") is not a position and is let through.
	 */
	private static boolean tupleClaims(String reply, Set<String> verified) {
		Matcher matcher = TUPLE.matcher(reply);
		while (matcher.find()) {
			if (isCounting(matcher)) {
				continue;
			}
			for (int group = 1; group <= matcher.groupCount(); group++) {
				String number = matcher.group(group);
				if (number != null && !verified.contains(canonical(number))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isCounting(Matcher tuple) {
		int previous = 0;
		int step = 0;
		for (int group = 1; group <= tuple.groupCount(); group++) {
			String number = tuple.group(group);
			if (number == null) {
				continue;
			}
			int value = Integer.parseInt(number);
			if (value < 0 || value > 20) {
				return false;
			}
			if (group > 1) {
				int difference = value - previous;
				if (Math.abs(difference) != 1 || (step != 0 && difference != step)) {
					return false;
				}
				step = difference;
			}
			previous = value;
		}
		return true;
	}

	/** What Falsity says instead of an unverified location. */
	public static String honestReply() {
		return "I'm not sure where that is, and I won't guess. Ask me to look for a village, a structure, ores or a cave, or ask where we are, and I'll check.";
	}

	private static boolean claims(Pattern pattern, String reply, Set<String> verified) {
		Matcher matcher = pattern.matcher(reply);
		while (matcher.find()) {
			for (int group = 1; group <= matcher.groupCount(); group++) {
				String number = matcher.group(group);
				if (number != null && !verified.contains(canonical(number))) {
					return true;
				}
			}
		}
		return false;
	}

	private static Set<String> numbersIn(String text) {
		Set<String> numbers = new HashSet<>();
		if (text == null) {
			return numbers;
		}
		Matcher matcher = NUMBER.matcher(joinThousands(text));
		while (matcher.find()) {
			String number = canonical(matcher.group());
			numbers.add(number);
			// A fact written "z=-30" backs a reply that says "30 blocks".
			numbers.add(number.startsWith("-") ? number.substring(1) : number);
		}
		return numbers;
	}

	private static String canonical(String number) {
		boolean negative = number.startsWith("-");
		String digits = negative ? number.substring(1) : number;
		digits = digits.replaceFirst("^0+(?=\\d)", "");
		return negative && !digits.equals("0") ? "-" + digits : digits;
	}
}
