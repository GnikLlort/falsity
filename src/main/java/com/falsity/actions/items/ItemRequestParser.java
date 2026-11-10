package com.falsity.actions.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits what a player asked for into separate items with quantities.
 *
 * <p>"a crafting table and 64 sticks" is two things, not one item called
 * "crafting table and 64 sticks". This class only reads the words: it knows
 * about lists ("a, b and c"), digits, number words, "a couple of", "a dozen"
 * and "a stack of", and nothing about which items exist. Whether a name is a
 * real item is decided afterwards by {@link ItemResolver}.
 *
 * <p>Pure and deterministic, so it runs the same with or without Groq.
 */
public final class ItemRequestParser {
	/** How a quantity is counted. */
	public enum Unit {
		/** Individual items. */
		ITEMS,
		/** Full stacks; the size depends on the item. */
		STACKS,
		/** Half stacks. */
		HALF_STACKS
	}

	/**
	 * One requested thing.
	 *
	 * @param name        the player's words for it, without quantity or courtesy
	 * @param count       how many (of {@code unit}); at least 1
	 * @param unit        whether {@code count} is items or stacks
	 * @param countStated whether the player actually gave a quantity
	 */
	public record Entry(String name, int count, Unit unit, boolean countStated) {
		public Entry {
			name = name == null ? "" : name.trim();
			unit = unit == null ? Unit.ITEMS : unit;
		}

		public static Entry of(String name, int count) {
			return new Entry(name, count, Unit.ITEMS, true);
		}

		/** The number of items this stands for, given the item's stack size. */
		public int quantity(int maxStackSize) {
			int stack = Math.max(1, maxStackSize);
			long total = switch (this.unit) {
				case ITEMS -> this.count;
				case STACKS -> (long) this.count * stack;
				case HALF_STACKS -> Math.max(1L, (long) this.count * stack / 2L);
			};
			return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, total));
		}
	}

	/** Largest quantity the parser will read; anything above is not a sane request. */
	public static final int MAX_COUNT = 100_000;

	private static final String PROTECTED_AND = "\u0001";

	private static final Pattern SEPARATOR = Pattern.compile(
		"\\s*(?:[,;&+]|\\band also\\b|\\bas well as\\b|\\balong with\\b|\\band then\\b|\\bplus\\b|\\band\\b)\\s*",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern TRAILING_NOISE = Pattern.compile("[\\s.,!?;:]+$");
	private static final Pattern LEADING_NOISE = Pattern.compile("^[\\s.,!?;:]+");

	private static final Pattern TRAILING_COURTESY = Pattern.compile(
		"(?:[\\s,]+(?:please|pls|plz|plox|thanks|thank you|thx|ty|now|right now|quickly|quick|asap|if you can|if possible|if you could|"
		+ "if you have (?:any|some|one|it|them)|if you don't mind|for me|to me|too|as well|ok|okay|would be (?:nice|great)|real quick))+$",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern LEADING_FILLER = Pattern.compile(
		"^(?:(?:also|then|maybe|like|about|around|roughly|approximately|at least|exactly|just|only|another|uh|um|oh)\\s+)+",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern DIGITS_FIRST = Pattern.compile("^(\\d{1,9})\\s*(?:x\\s+|\u00d7\\s*|of\\s+)?(.*)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DIGITS_LAST = Pattern.compile("^(.*?)\\s+(?:x|\u00d7|\\*)\\s*(\\d{1,9})$", Pattern.CASE_INSENSITIVE);
	private static final Pattern STACKS = Pattern.compile("^(?:full\\s+)?stacks?\\s+(?:of\\s+)?(.*)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern HALF_STACK = Pattern.compile("^half\\s+(?:of\\s+)?(?:a\\s+)?stack\\s+(?:of\\s+)?(.*)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern ARTICLE = Pattern.compile("^(?:a|an|the|some|any|my|more|extra)\\s+", Pattern.CASE_INSENSITIVE);
	private static final Pattern OF_THE = Pattern.compile("^(?:of\\s+)?(?:the|those|these|them|that|this)\\s+", Pattern.CASE_INSENSITIVE);
	private static final Pattern MORE = Pattern.compile("^(?:more|extra|additional|other)\\s+", Pattern.CASE_INSENSITIVE);

	private static final Pattern VAGUE = Pattern.compile(
		"^(?:a|an|the|some|something|anything|stuff|things|thing|it|that|this|them|those|these|nothing|help|more|one|ones|everything|all|"
		+ "something else|anything else|whatever|some stuff|some things|of)$",
		Pattern.CASE_INSENSITIVE);

	private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
		Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
		Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
		Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
		Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
		Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
		Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
		Map.entry("ninety", 90), Map.entry("hundred", 100)
	);

	private ItemRequestParser() {
	}

	/**
	 * Parses the part of a request that names the items, such as
	 * "a crafting table and 64 sticks". Returns one entry per item, in the
	 * order they were said. Entries too vague to name an item ("something")
	 * are left out; an empty list means nothing usable was asked for.
	 */
	public static List<Entry> parse(String text) {
		List<Entry> entries = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return entries;
		}

		String cleaned = text.replaceAll("\\s+", " ").trim();
		cleaned = TRAILING_NOISE.matcher(cleaned).replaceFirst("");
		cleaned = stripCourtesy(cleaned);
		// "flint and steel" is one item, not two.
		cleaned = cleaned.replaceAll("(?i)\\bflint\\s+(?:and|&|n)\\s+steel\\b", "flint" + PROTECTED_AND + "steel");

		for (String part : SEPARATOR.split(cleaned)) {
			Entry entry = parsePart(part.replace(PROTECTED_AND, " and "));
			if (entry != null) {
				entries.add(entry);
			}
		}
		return entries;
	}

	/** Whether the text names nothing concrete ("something", "it", "a"). */
	public static boolean isVague(String name) {
		return name == null || name.isBlank() || VAGUE.matcher(name.trim()).matches();
	}

	/**
	 * Writes entries back as text that {@link #parse} reads to the same
	 * entries. Used to carry a multi-item request in one action argument.
	 */
	public static String format(List<Entry> entries) {
		StringBuilder builder = new StringBuilder();
		if (entries == null) {
			return "";
		}
		for (Entry entry : entries) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			switch (entry.unit()) {
				case STACKS -> builder.append(entry.count()).append(entry.count() == 1 ? " stack of " : " stacks of ");
				case HALF_STACKS -> builder.append("half a stack of ");
				default -> builder.append(entry.count()).append(' ');
			}
			builder.append(entry.name());
		}
		return builder.toString();
	}

	private static Entry parsePart(String rawPart) {
		String part = rawPart == null ? "" : rawPart.trim();
		part = LEADING_NOISE.matcher(part).replaceFirst("");
		part = TRAILING_NOISE.matcher(part).replaceFirst("");
		part = stripCourtesy(part);
		part = LEADING_FILLER.matcher(part).replaceFirst("").trim();
		if (part.isEmpty()) {
			return null;
		}

		int count = 1;
		boolean stated = false;
		Unit unit = Unit.ITEMS;

		Matcher halfStack = HALF_STACK.matcher(part);
		if (halfStack.matches()) {
			unit = Unit.HALF_STACKS;
			stated = true;
			part = halfStack.group(1).trim();
		} else {
			Matcher digits = DIGITS_FIRST.matcher(part);
			Matcher digitsLast = DIGITS_LAST.matcher(part);
			if (digits.matches()) {
				count = parseCount(digits.group(1));
				stated = true;
				part = digits.group(2).trim();
			} else if (digitsLast.matches() && !digitsLast.group(1).isBlank()) {
				count = parseCount(digitsLast.group(2));
				stated = true;
				part = digitsLast.group(1).trim();
			} else {
				Quantity words = leadingQuantity(part);
				if (words != null) {
					count = words.count();
					stated = words.stated();
					part = words.rest();
				}
			}
			if (count < 1) {
				return null;
			}

			part = OF_THE.matcher(part).replaceFirst("");
			Matcher stacks = STACKS.matcher(part);
			if (stacks.matches() && !stacks.group(1).isBlank()) {
				unit = Unit.STACKS;
				stated = true;
				part = stacks.group(1).trim();
			}
		}

		part = OF_THE.matcher(part).replaceFirst("");
		part = MORE.matcher(part).replaceFirst("");
		part = ARTICLE.matcher(part).replaceFirst("");
		part = stripCourtesy(part);
		part = TRAILING_NOISE.matcher(part).replaceFirst("");
		part = part.replaceAll("\\s+", " ").trim();
		if (part.isEmpty() || isVague(part)) {
			return null;
		}
		return new Entry(part, count, unit, stated);
	}

	private record Quantity(int count, boolean stated, String rest) {
	}

	/** Reads a leading quantity written in words, or returns null. */
	private static Quantity leadingQuantity(String part) {
		String lower = part.toLowerCase(Locale.ROOT);

		String[][] phrases = {
			{"half a dozen ", "6"}, {"half dozen ", "6"}, {"a dozen ", "12"}, {"one dozen ", "12"}, {"two dozen ", "24"}, {"dozen ", "12"},
			{"a couple of ", "2"}, {"a couple ", "2"}, {"couple of ", "2"}, {"couple ", "2"},
			{"a few ", "3"}, {"few ", "3"}, {"several ", "5"}, {"a handful of ", "5"}, {"a bunch of ", "8"},
			{"a single ", "1"}, {"single ", "1"}, {"one more ", "1"}, {"a hundred ", "100"}, {"one hundred ", "100"},
		};
		for (String[] phrase : phrases) {
			if (lower.startsWith(phrase[0])) {
				String rest = part.substring(phrase[0].length()).trim();
				rest = rest.replaceFirst("(?i)^of\\s+", "");
				return new Quantity(Integer.parseInt(phrase[1]), true, rest);
			}
		}

		String[] words = lower.split("[\\s-]+");
		if (words.length < 2) {
			return null;
		}
		Integer first = NUMBER_WORDS.get(words[0]);
		if (first == null) {
			if ((words[0].equals("a") || words[0].equals("an")) && words.length >= 2) {
				// "a stack of torches" and plain "a sword" both mean one.
				return new Quantity(1, false, part.substring(words[0].length()).trim());
			}
			return null;
		}

		int value = first;
		int consumed = 1;
		if (first >= 20 && first < 100 && words.length >= 3) {
			Integer second = NUMBER_WORDS.get(words[1]);
			if (second != null && second < 10) {
				value = first + second;
				consumed = 2;
			}
		}
		String rest = dropWords(part, consumed);
		rest = rest.replaceFirst("(?i)^of\\s+", "");
		if (rest.isEmpty()) {
			return null;
		}
		return new Quantity(value, true, rest);
	}

	/** Removes the first {@code count} words (separated by spaces or hyphens). */
	private static String dropWords(String part, int count) {
		int index = 0;
		int dropped = 0;
		while (index < part.length() && dropped < count) {
			while (index < part.length() && part.charAt(index) != ' ' && part.charAt(index) != '-') {
				index++;
			}
			while (index < part.length() && (part.charAt(index) == ' ' || part.charAt(index) == '-')) {
				index++;
			}
			dropped++;
		}
		return part.substring(index).trim();
	}

	private static int parseCount(String digits) {
		try {
			long value = Long.parseLong(digits);
			return value > MAX_COUNT ? MAX_COUNT + 1 : (int) value;
		} catch (NumberFormatException overflow) {
			return MAX_COUNT + 1;
		}
	}

	private static String stripCourtesy(String text) {
		String previous;
		String current = text;
		do {
			previous = current;
			current = TRAILING_COURTESY.matcher(current).replaceFirst("");
			current = TRAILING_NOISE.matcher(current).replaceFirst("");
		} while (!current.equals(previous));
		return current;
	}
}
