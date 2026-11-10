package com.falsity.actions;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns natural language into a structured {@link ActionRequest} without any
 * network call.
 *
 * <p>Falsity must work with no Groq key at all, so the common requests are
 * recognised locally and deterministically. This is not a security boundary —
 * the server validates and executes whatever it returns — but it is what makes
 * "Falsity, follow me" or "give me 32 diamonds" behave even offline. When Groq
 * is available its structured action takes precedence; this is the fallback.
 *
 * <p>Recognition is intentionally conservative: if a message does not match a
 * known request it returns empty and the conversation proceeds as dialogue.
 */
public final class IntentRecognizer {
	private static final Pattern STOP_BUILD =
		Pattern.compile("\\b(stop|cancel|abort|call off)\\b[^.!?]*\\b(build|building|construction|structure)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern BUILD =
		Pattern.compile("\\b(build|make|construct|create|put up|erect|assemble)\\b[^.!?]*", Pattern.CASE_INSENSITIVE);

	private static final Pattern STOP_FIGHT =
		Pattern.compile("\\b(stop|call off|quit|halt)\\b[^.!?]*\\b(fight|fighting|attacking|combat|attacking|offense)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern FIGHT =
		Pattern.compile("\\b(fight|attack|combat|help me fight|help me attack|defend me from)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern PROTECT =
		Pattern.compile("\\b(protect|defend|guard|keep me safe|watch my back)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern STOP_FOLLOW =
		Pattern.compile("\\b(stop following|stop coming|stay (?:here|put|where you are)|wait here|stay behind)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern FOLLOW =
		Pattern.compile("\\b(follow|come with|walk with|tag along|stick with|stay with)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern COME =
		Pattern.compile("\\b(come here|come to me|come over|get here|come back|come to|approach me)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern SIT =
		Pattern.compile("\\b(sit|settle|park yourself|take a seat|sit down)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern STAND =
		Pattern.compile("\\b(stand up|get up|stand|rise)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern REPORT_THREATS =
		Pattern.compile("\\b(what.{0,20}nearby|what.{0,20}around|report|threat|danger|hostile|enemies|zombies|creepers|skeletons|spiders|witches)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern ITEM_REQUEST =
		Pattern.compile("^(?:(?:can you|could you|would you|please|hey|falsity[,.!]?\\s*)*)"
			+ "(?:give me|bring me|get me|fetch me|hand me|pass me|drop me|deliver me|i need|i want|i'd like|i would like|can i have|may i have|could i have)"
			+ "\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d+)\\s*(?:of\\s+)?(.*)$", Pattern.DOTALL);
	private static final Pattern LEADING_ARTICLE = Pattern.compile("^\\s*(?:a|an|the)\\s+", Pattern.CASE_INSENSITIVE);
	private static final Pattern TRAILING_NOISE = Pattern.compile("[\\s.,!?;:]+$");

	private static final List<String> STRUCTURES = List.of(
		"staircase", "stair", "stairs", "stairway",
		"storage room", "storage", "storage chest", "chest",
		"bridge", "tower", "castle", "house", "hut", "cabin", "cottage",
		"wall", "fence", "farm", "platform", "floor", "foundation",
		"pillar", "column", "roof", "room", "pond", "well", "gate"
	);

	private static final List<String> MATERIALS = List.of(
		"dark oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove",
		"wood", "wooden", "logs", "log", "planks", "plank",
		"stone", "cobblestone", "cobble", "bricks", "brick", "sandstone",
		"diorite", "granite", "andesite", "prismarine", "wool", "glass"
	);

	private IntentRecognizer() {
	}

	/** The structured action this message implies, or empty when it is just talk. */
	public static Optional<ActionRequest> recognize(String message) {
		if (message == null || message.isBlank()) {
			return Optional.empty();
		}
		String text = message.trim();

		if (STOP_BUILD.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_BUILD));
		}
		if (STOP_FIGHT.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_FIGHT));
		}
		if (STOP_FOLLOW.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_FOLLOW));
		}

		Optional<ActionRequest> item = recognizeItem(text);
		if (item.isPresent()) {
			return item;
		}

		Optional<ActionRequest> build = recognizeBuild(text);
		if (build.isPresent()) {
			return build;
		}

		if (FIGHT.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.FIGHT));
		}
		if (PROTECT.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.PROTECT));
		}
		if (FOLLOW.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.FOLLOW));
		}
		if (COME.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.COME));
		}
		if (REPORT_THREATS.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.REPORT_THREATS));
		}
		if (STAND.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.STAND));
		}
		if (SIT.matcher(text).find()) {
			return Optional.of(ActionRequest.of(ActionType.SIT));
		}

		return Optional.empty();
	}

	private static Optional<ActionRequest> recognizeItem(String text) {
		Matcher matcher = ITEM_REQUEST.matcher(text);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String remainder = matcher.group(1).trim();
		if (remainder.isEmpty()) {
			return Optional.empty();
		}

		int count = 1;
		Matcher number = LEADING_NUMBER.matcher(remainder);
		if (number.matches()) {
			count = Integer.parseInt(number.group(1));
			remainder = number.group(2).trim();
		}

		remainder = LEADING_ARTICLE.matcher(remainder).replaceFirst("");
		remainder = TRAILING_NOISE.matcher(remainder).replaceFirst("");
		remainder = remainder.replaceAll("\\s+", " ").trim();
		if (remainder.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(ActionRequest.of(
			ActionType.GIVE_ITEM,
			Map.of("item", remainder, "count", count)
		));
	}

	private static Optional<ActionRequest> recognizeBuild(String text) {
		Matcher matcher = BUILD.matcher(text);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String phrase = text.toLowerCase(Locale.ROOT);

		String structure = null;
		for (String candidate : STRUCTURES) {
			if (phrase.contains(candidate)) {
				structure = candidate;
				break;
			}
		}
		if (structure == null) {
			// "build me something here" is too vague to act on.
			return Optional.empty();
		}

		String material = null;
		for (String candidate : MATERIALS) {
			if (phrase.contains(candidate)) {
				material = candidate;
				break;
			}
		}

		Map<String, Object> args = new java.util.HashMap<>();
		args.put("structure", structure);
		if (material != null) {
			args.put("material", material);
		}

		return Optional.of(ActionRequest.of(ActionType.BUILD, args));
	}
}
