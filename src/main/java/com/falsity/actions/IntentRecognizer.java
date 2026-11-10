package com.falsity.actions;

import com.falsity.actions.items.ItemRequestParser;
import com.falsity.world.WorldQuery;
import com.falsity.world.WorldQueryRecognizer;
import java.util.ArrayList;
import java.util.HashMap;
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
 * "Falsity, follow me" or "give me 32 diamonds" behave even offline.
 *
 * <p>A message is only an order when it is phrased as one. Each sentence is
 * read on its own, greetings and politeness ("hey", "please", "can you") are
 * set aside, and what is left must <em>begin</em> with a command: "follow me",
 * "can you sit down", "I need you to protect me". A command word that merely
 * appears somewhere in a sentence is not an order, so "why do you follow me?",
 * "do you like to fight?" and "I hate creepers" stay conversation, and so does
 * a plan the player states for themselves ("I want to build a house").
 *
 * <p>Recognition is intentionally conservative: if a message does not match a
 * known request it returns empty and the conversation proceeds as dialogue.
 */
public final class IntentRecognizer {
	/** Greetings, the name and filler in front of what is actually being said. */
	private static final Pattern LEAD_IN = Pattern.compile(
		"^(?:(?:hey+|hi+|hello|yo|ok|okay|so|well|now|and|then|also|oh|um+|uh+|alright|all right|right|falsity|please|pls|plz|"
		+ "quick|quickly|kindly|buddy|friend|little one|listen|psst|yes|yeah|no)\\b[,!:.]*\\s+)+",
		Pattern.CASE_INSENSITIVE);

	/** "Can you ...", asked as a question. A bare verb after these may just be curiosity. */
	private static final Pattern QUESTION_FRAME = Pattern.compile(
		"^(?:can you|could you|would you|will you|can u|could u|would u|will u|would you mind|are you able to|do you want to|wanna)"
		+ "(?:\\s+(?:please|pls|plz|just|kindly|now|quickly|maybe|possibly|go and|go))*\\s+",
		Pattern.CASE_INSENSITIVE);

	/** "I need you to ...", "you should ...", "let's ...": an order with a softer start. */
	private static final Pattern ORDER_FRAME = Pattern.compile(
		"^(?:i need you to|i want you to|i'?d like you to|i would like you to|i'?d love you to|you should|you need to|you have to|you must|"
		+ "you can|you may|you better|let'?s|let us|time to|it'?s time to|try to|try and|go ahead and|go and|go|come on and|"
		+ "do me a favou?r and|just|now|why don'?t you|how about you|i order you to|i command you to)"
		+ "(?:\\s+(?:please|pls|plz|just|kindly|now|quickly|go and|go))*\\s+",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern STOP_BUILD = Pattern.compile(
		"^(?:stop|cancel|abort|call off|quit|halt|end|forget)\\b[^.!?]*\\b(?:build|building|construction|structure|project)\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern STOP_FIGHT = Pattern.compile(
		"^(?:(?:stop|call off|quit|halt|cease|no more)\\b[^.!?]*\\b(?:fight|fighting|attack|attacking|combat|offense|killing|hitting)\\b"
		+ "|stand down\\b|calm down\\b|leave (?:it|them|him|her) alone\\b|don'?t (?:attack|fight|hurt)\\b|do not (?:attack|fight|hurt)\\b)",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern STOP_FOLLOW = Pattern.compile(
		"^(?:stop following|stop coming|stay (?:here|put|where you are|there|behind|back)|wait (?:here|there|for me|up)|stay behind"
		+ "|don'?t follow|do not follow|stop (?:right )?there|stop$)",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern STAY = Pattern.compile(
		"^(?:stay|wait|hold still|stand still|don'?t move|do not move|hold position|hold your position|freeze|stop moving|stay still)\\b"
		+ "(?!\\s+(?:with|close|near|by my|beside|next to))",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern BUILD = Pattern.compile(
		"^(?:build|construct|make|create|put up|erect|assemble)\\b(?!\\s+sure\\b)", Pattern.CASE_INSENSITIVE);

	private static final Pattern FIGHT = Pattern.compile(
		"^(?:fight|attack|kill|slay|destroy|hit|smack|take (?:out|down|care of)|deal with|get rid of"
		+ "|help me (?:fight|attack|kill|with (?:these|those|this|that|the|them))|defend me from|get (?:them|him|her|it|that|those|these)\\b|sic)\\b",
		Pattern.CASE_INSENSITIVE);

	/** A fight order with nothing after the verb: "fight", "attack!". */
	private static final Pattern BARE_FIGHT = Pattern.compile("^(?:fight|attack|kill|slay|destroy|hit)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern PROTECT = Pattern.compile(
		"^(?:protect|defend|guard|keep (?:me|us) safe|watch my back|cover me|watch over (?:me|us)|keep watch|stand guard"
		+ "|look after me|keep an eye out|keep a lookout|be my bodyguard)\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern FOLLOW = Pattern.compile(
		"^(?:follow|come with|walk with|tag along|stick with|stay with|stay close|stay near|stay by my side|stay beside me|keep up"
		+ "|come along|let'?s go|with me|heel|keep close|stick close|this way|after me)\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern COME = Pattern.compile(
		"^(?:come here|come to me|come over|get here|get over here|come back|come to|approach me|come closer|come on over|c'?mere"
		+ "|return to me|get back here|come find me|over here|to me|come$|here$)",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern SIT = Pattern.compile(
		"^(?:sit|settle down|park yourself|take a seat|have a seat|rest here|take a break|take a rest)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern STAND = Pattern.compile(
		"^(?:stand up|get up|stand|rise|on your feet|wake up|up$)\\b", Pattern.CASE_INSENSITIVE);

	private static final String MOBS =
		"(?:monsters?|mobs?|hostiles?|enemies|enemy|threats?|danger|zombies?|creepers?|skeletons?|spiders?|witch(?:es)?|endermen|enderman"
		+ "|phantoms?|pillagers?|drowned|bad guys|bad things|anything (?:dangerous|hostile|scary|bad)|something (?:dangerous|hostile|scary|bad))";

	private static final Pattern REPORT_THREATS = Pattern.compile(
		"\\bwhat(?:'s|s| is)? (?:is )?(?:nearby|around|near (?:me|us|here)|out there|close by|lurking|coming|after (?:me|us)|chasing (?:me|us))\\b"
		+ "|\\b(?:any|are there(?: any)?|is there(?: any| a| an)?|do you (?:see|hear|sense|smell)(?: any| a| an)?|see any|hear any|how many)\\s+" + MOBS + "\\b"
		+ "|\\b(?:is it|are we|am i|is this place|is this area|is that|is the coast|is the area) (?:safe|clear)\\b|\\bsafe (?:here|to (?:go|sleep|rest|stay|come out))\\b"
		+ "|^(?:report|scan|check|look|watch|search) (?:out )?(?:for )?(?:" + MOBS + "|the area|the perimeter|nearby)\\b"
		+ "|^(?:report|status report|threat report|scan the area|check the area|check around|threats|scan)$"
		+ "|\\bwhat (?:is|was) that (?:noise|sound)\\b|\\bdid you hear that\\b|\\b(?:are we|am i) in danger\\b"
		+ "|\\b" + MOBS + " (?:nearby|around here|around us|near us|near me|close by)\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern ITEM_REQUEST = Pattern.compile(
		"^(?:give me|bring me|get me|fetch me|hand me|pass me|drop me|deliver me|find me|grab me|toss me|throw me|lend me|spawn me"
		+ "|craft me|make me|gimme|give us|get us|bring us|i need|i want|i'?d like|i would like|i could use|i require|i'?ll take|i will take"
		+ "|can i have|may i have|could i have|can i get|could i get|may i get|can we have|could we have)"
		+ "\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);

	/** After "I need" or "I want", these openings are not items: "to ...", "you to ...". */
	private static final Pattern NOT_AN_ITEM = Pattern.compile("^(?:to|you|your|u|him|her|them|someone|somebody|us)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?;])\\s+");
	private static final Pattern TRAILING_NOISE = Pattern.compile("[\\s.,!?;:]+$");

	// Ordered longest/most-specific first so e.g. "storage room" beats "room".
	private static final List<String> STRUCTURES = List.of(
		"storage room", "storage chest", "foundation", "staircase",
		"stairway", "platform", "cottage", "storage", "bridge",
		"castle", "column", "pillar", "stairs", "tower",
		"cabin", "chest", "fence", "floor", "house",
		"stair", "farm", "gate", "pond", "roof",
		"room", "wall", "well", "hut"
	);

	// Ordered longest/most-specific first so "wooden" beats "wood" and "cobblestone"/"sandstone" beat "stone".
	private static final List<String> MATERIALS = List.of(
		"dark oak", "cobblestone", "sandstone", "prismarine",
		"wooden", "planks", "bricks", "andesite", "granite", "diorite",
		"spruce", "birch", "jungle", "acacia", "mangrove",
		"cobble", "plank", "brick", "logs", "stone", "wood", "log",
		"oak", "wool", "glass"
	);

	private static final List<WordCandidate> STRUCTURE_PATTERNS = compileCandidates(STRUCTURES);
	private static final List<WordCandidate> MATERIAL_PATTERNS = compileCandidates(MATERIALS);

	private record WordCandidate(String keyword, Pattern pattern) {
	}

	private IntentRecognizer() {
	}

	/** The structured action this message implies, or empty when it is just talk. */
	public static Optional<ActionRequest> recognize(String message) {
		if (message == null || message.isBlank()) {
			return Optional.empty();
		}
		String text = message.trim().replaceAll("\\s+", " ");

		for (String sentence : SENTENCE_BREAK.split(text)) {
			Optional<ActionRequest> request = recognizeSentence(sentence);
			if (request.isPresent()) {
				return request;
			}
		}
		return Optional.empty();
	}

	/**
	 * Whether the message asks for items in a way that only makes sense as a
	 * wish ("I need", "I want", "can I have"), as opposed to an order ("give
	 * me"). A wish for something that is not an item is conversation.
	 */
	public static boolean isItemRequest(String message) {
		return recognize(message)
			.map(request -> request.type() == ActionType.GIVE_ITEM || request.type() == ActionType.GIVE_ITEMS)
			.orElse(false);
	}

	private static Optional<ActionRequest> recognizeSentence(String rawSentence) {
		String sentence = TRAILING_NOISE.matcher(rawSentence.trim()).replaceFirst("").trim();
		// "Hey,  can you" arrives as "Hey, can you"; a space before punctuation is noise too.
		sentence = sentence.replaceAll("\\s+([,.!?;:])", "$1");
		if (sentence.isEmpty()) {
			return Optional.empty();
		}

		// What is said once greetings and the name are set aside.
		String said = stripAll(LEAD_IN, sentence);
		if (said.isEmpty()) {
			return Optional.empty();
		}

		// The order itself, once "can you" / "I need you to" are set aside.
		boolean asked = false;
		String order = said;
		while (true) {
			Matcher question = QUESTION_FRAME.matcher(order);
			if (question.find()) {
				asked = true;
				order = stripAll(LEAD_IN, order.substring(question.end()).trim());
				continue;
			}
			Matcher frame = ORDER_FRAME.matcher(order);
			if (frame.find() && !FOLLOW.matcher(order).find()) {
				order = stripAll(LEAD_IN, order.substring(frame.end()).trim());
				continue;
			}
			break;
		}
		if (order.isEmpty()) {
			return Optional.empty();
		}

		if (STOP_BUILD.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_BUILD));
		}
		if (STOP_FIGHT.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_FIGHT));
		}
		if (STOP_FOLLOW.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.STOP_FOLLOW));
		}

		// "Where am I", "what biome is this": answered from the game, never guessed.
		Optional<WorldQuery> here = WorldQueryRecognizer.recognizeSimple(said);
		if (here.isPresent()) {
			return Optional.of(worldQuery(here.get()));
		}

		Optional<ActionRequest> build = recognizeBuild(order);
		if (build.isPresent()) {
			return build;
		}

		Optional<ActionRequest> item = recognizeItem(order);
		if (item.isPresent()) {
			return item;
		}

		if (FIGHT.matcher(order).find()) {
			// "Can you fight?" is a question about Falsity, not an order to attack.
			if (!(asked && BARE_FIGHT.matcher(order).matches())) {
				return Optional.of(ActionRequest.of(ActionType.FIGHT));
			}
			return Optional.empty();
		}
		if (PROTECT.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.PROTECT));
		}
		if (FOLLOW.matcher(order).find() || FOLLOW.matcher(said).find()) {
			return Optional.of(ActionRequest.of(ActionType.FOLLOW));
		}
		if (COME.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.COME));
		}
		if (STAY.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.STAY));
		}
		if (STAND.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.STAND));
		}
		if (SIT.matcher(order).find()) {
			return Optional.of(ActionRequest.of(ActionType.SIT));
		}

		if (REPORT_THREATS.matcher(said).find()) {
			return Optional.of(ActionRequest.of(ActionType.REPORT_THREATS));
		}

		// "Where is the nearest village", "any diamonds nearby".
		Optional<WorldQuery> search = WorldQueryRecognizer.recognizeSearch(said);
		if (search.isPresent()) {
			return Optional.of(worldQuery(search.get()));
		}

		return Optional.empty();
	}

	private static ActionRequest worldQuery(WorldQuery query) {
		return ActionRequest.of(ActionType.WORLD_QUERY, Map.of("query", query.toActionQuery()));
	}

	private static String stripAll(Pattern pattern, String text) {
		String current = text;
		while (true) {
			Matcher matcher = pattern.matcher(current);
			if (!matcher.find() || matcher.end() == 0) {
				return current.trim();
			}
			current = current.substring(matcher.end()).trim();
		}
	}

	private static Optional<ActionRequest> recognizeItem(String text) {
		Matcher matcher = ITEM_REQUEST.matcher(text);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String remainder = TRAILING_NOISE.matcher(matcher.group(1).trim()).replaceFirst("").trim();
		if (remainder.isEmpty() || NOT_AN_ITEM.matcher(remainder).find()) {
			return Optional.empty();
		}

		List<ItemRequestParser.Entry> entries = ItemRequestParser.parse(remainder);
		if (entries.isEmpty()) {
			return Optional.empty();
		}
		for (ItemRequestParser.Entry entry : entries) {
			if (entry.count() < 1 || entry.count() > ItemRequestParser.MAX_COUNT) {
				return Optional.empty();
			}
		}

		if (entries.size() == 1 && entries.get(0).unit() == ItemRequestParser.Unit.ITEMS) {
			ItemRequestParser.Entry only = entries.get(0);
			return Optional.of(ActionRequest.of(
				ActionType.GIVE_ITEM,
				Map.of("item", only.name(), "count", only.count())
			));
		}

		// Several things, or a quantity in stacks: one request, validated as a whole.
		return Optional.of(ActionRequest.of(
			ActionType.GIVE_ITEMS,
			Map.of("items", ItemRequestParser.format(entries))
		));
	}

	private static Optional<ActionRequest> recognizeBuild(String text) {
		Matcher matcher = BUILD.matcher(text);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String phrase = text.toLowerCase(Locale.ROOT);

		String structure = firstWordMatch(phrase, STRUCTURE_PATTERNS);
		if (structure == null) {
			// "build me something here" is too vague to act on.
			return Optional.empty();
		}

		String material = firstWordMatch(phrase, MATERIAL_PATTERNS);

		Map<String, Object> args = new HashMap<>();
		args.put("structure", structure);
		if (material != null) {
			args.put("material", material);
		}

		return Optional.of(ActionRequest.of(ActionType.BUILD, args));
	}

	private static List<WordCandidate> compileCandidates(List<String> keywords) {
		List<WordCandidate> compiled = new ArrayList<>(keywords.size());
		for (String keyword : keywords) {
			compiled.add(new WordCandidate(
				keyword,
				Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
			));
		}
		return List.copyOf(compiled);
	}

	private static String firstWordMatch(String phrase, List<WordCandidate> candidates) {
		for (WordCandidate candidate : candidates) {
			if (candidate.pattern().matcher(phrase).find()) {
				return candidate.keyword();
			}
		}
		return null;
	}
}
