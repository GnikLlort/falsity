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
	/** "x=120", "y: 64", "z -30". */
	private static final Pattern LABELLED = Pattern.compile("\\b[xyz]\\s*[:=]?\\s*(-?\\d{1,7})\\b", Pattern.CASE_INSENSITIVE);

	/** "120, 64, -30", "120 / 64 / -30", "(120, -30)". */
	private static final Pattern TUPLE = Pattern.compile(
		"(?<![\\w.])(-?\\d{1,7})\\s*[,/]\\s*(-?\\d{1,7})(?:\\s*[,/]\\s*(-?\\d{1,7}))?(?![\\w.])");

	/** "200 blocks", "about 3 chunks away", "50 meters north". */
	private static final Pattern DISTANCE = Pattern.compile(
		"(-?\\d{1,7})\\s*(?:blocks?|chunks?|meters?|metres?|m)\\b(?=[^.!?]{0,40}\\b(?:away|north|south|east|west|ahead|behind|from here|from us|from you|down|up|below|above|deep)\\b)",
		Pattern.CASE_INSENSITIVE);

	/** "at 120 64 -30", "coordinates 120 64 -30". */
	private static final Pattern SPACED = Pattern.compile(
		"\\b(?:at|to|coordinates?|coords|position|located|location)\\s*:?\\s*(-?\\d{1,7})\\s+(-?\\d{1,7})(?:\\s+(-?\\d{1,7}))?\\b",
		Pattern.CASE_INSENSITIVE);

	/** "Y level 12", "height 64", "layer -59". */
	private static final Pattern LEVEL = Pattern.compile(
		"\\b(?:y[- ]?level|level|height|layer|altitude)\\s*(?:of\\s*)?(-?\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);

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
		return claims(LABELLED, reply, verified)
			|| claims(TUPLE, reply, verified)
			|| claims(DISTANCE, reply, verified)
			|| claims(SPACED, reply, verified)
			|| claims(LEVEL, reply, verified);
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
		Matcher matcher = NUMBER.matcher(text);
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
