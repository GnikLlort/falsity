package com.falsity.memory;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks out the things a player says that are worth keeping for good: their
 * name, what they like, what they are building, where they live, what they are
 * afraid of, what belongs to them, and anything they ask Falsity to remember.
 *
 * <p>Extraction is local and deterministic: no API is involved, so what is
 * remembered is always something the player really typed, in their own words.
 * Only first-person statements are kept. Questions, orders and long messages
 * are left alone, because a memory full of trivia pushes the important things
 * out.
 */
public final class FactExtractor {
	/** Longest message kept as a fact, in characters. */
	public static final int MAX_FACT_MESSAGE_CHARS = 80;

	/** Longest name kept, in characters. */
	public static final int MAX_NAME_CHARS = 32;

	/** "call me later" is not an introduction. */
	private static final Set<String> NOT_NAMES = Set.of(
		"later", "back", "when", "if", "maybe", "crazy", "a", "an", "the", "your", "yours", "that", "this", "it",
		"whatever", "anything", "something", "tomorrow", "tonight", "now", "again", "please", "then", "over", "up");

	private static final Pattern CALL_ME = Pattern.compile("^(?:you can |just |please )?call me ([\\p{L}\\p{N}_]{2," + MAX_NAME_CHARS + "})[.!]*$",
		Pattern.CASE_INSENSITIVE);

	/** Things a player says about themselves that stay true for a while. */
	private static final Pattern ABOUT_ME = Pattern.compile(
		"^(?:"
		+ "i(?: am|'m| ?m) (?:building|making|working on|digging|looking for|collecting|living (?:in|at|near|by|on|under)|"
		+ "afraid of|scared of|terrified of|allergic to|from) "
		+ "|i live (?:in|at|near|by|on|under) "
		+ "|i fear "
		+ ").+",
		Pattern.CASE_INSENSITIVE);

	/** Statements about what belongs to the player: "my base is by the river", "my dog is called Rex". */
	private static final Pattern MINE = Pattern.compile(
		"^my (?:new |old |first |second |little |big |best |real )?"
		+ "(?:base|house|home|farm|mine|castle|tower|village|bed|shop|ship|boat|island|portal|"
		+ "pet|dog|cat|horse|wolf|parrot|fox|axolotl|donkey|llama|"
		+ "sword|pickaxe|axe|bow|shield|armor|armour|elytra|trident|"
		+ "friend|brother|sister|mom|mum|mother|dad|father|son|daughter|"
		+ "birthday|skin|world|server)s? (?:is|are|was|were) .+",
		Pattern.CASE_INSENSITIVE);

	private FactExtractor() {
	}

	/**
	 * Records at most one fact from a message, if it holds one.
	 *
	 * @param memory         where the fact is kept
	 * @param playerMessage  what the player said
	 * @param maxStoredFacts the cap on stored facts; the oldest is dropped beyond it
	 * @return the fact that was recognised, or an empty string
	 */
	public static String extract(PlayerMemory memory, String playerMessage, int maxStoredFacts) {
		String fact = factIn(playerMessage);
		if (memory != null && !fact.isEmpty()) {
			memory.recordFact(fact, maxStoredFacts);
		}
		return fact;
	}

	/** The fact a message holds, worded for storage, or an empty string. Pure. */
	public static String factIn(String playerMessage) {
		if (playerMessage == null) {
			return "";
		}
		String message = playerMessage.trim().replaceAll("\\s+", " ");
		if (message.isEmpty() || message.endsWith("?")) {
			return "";
		}
		String lower = message.toLowerCase(Locale.ROOT);

		if (lower.startsWith("my name is ")) {
			String name = message.substring("my name is ".length()).trim();
			return !name.isEmpty() && name.length() <= MAX_NAME_CHARS ? "Player's name is " + name : "";
		}
		Matcher callMe = CALL_ME.matcher(message);
		if (callMe.matches()) {
			String name = callMe.group(1);
			return NOT_NAMES.contains(name.toLowerCase(Locale.ROOT)) ? "" : "Player's name is " + name;
		}
		if (lower.startsWith("i like ") || lower.startsWith("i love ") || lower.startsWith("i hate ")) {
			return message.length() <= MAX_FACT_MESSAGE_CHARS ? "Player said: " + message : "";
		}
		if (lower.startsWith("my favourite ") || lower.startsWith("my favorite ")) {
			// "my favourite block is diamond" -> a durable preference.
			String detail = message.substring(lower.startsWith("my favourite ") ? "my favourite ".length() : "my favorite ".length()).trim();
			return !detail.isEmpty() && detail.length() <= MAX_FACT_MESSAGE_CHARS ? "Player's favourite is " + detail : "";
		}
		if (lower.startsWith("remember ")) {
			String toRemember = message.substring("remember ".length()).trim();
			return !toRemember.isEmpty() && toRemember.length() <= MAX_FACT_MESSAGE_CHARS
				? "Player asked Falsity to remember: " + toRemember : "";
		}
		if (message.length() <= MAX_FACT_MESSAGE_CHARS && (ABOUT_ME.matcher(message).matches() || MINE.matcher(message).matches())) {
			return "Player said: " + message;
		}
		return "";
	}
}
