package com.falsity.world;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises questions about the world that the server can answer by looking.
 *
 * <p>"Where is the nearest village?", "what biome is this?", "any diamonds
 * nearby?" are not things a language model can know. They are recognised here,
 * with no network call, and answered from the game itself. Anything this class
 * does not recognise stays ordinary conversation, and the model is told it has
 * no verified location to quote.
 *
 * <p>Recognition is conservative: a structure or ore name alone is not a
 * question. "I love villages" is talk; "is there a village nearby" is a query.
 */
public final class WorldQueryRecognizer {
	private static final String ASKING =
		"(?:where|nearest|closest|find|locate|look for|search for|searching for|how far|which way|direction to|directions to|"
		+ "can you see|do you see|see any|scan|sense|detect|looking for|point me|lead me|take me|guide me|way to|show me)";

	private static final String PROXIMITY =
		"(?:nearby|near here|near me|near us|near you|near (?:a|an|any|the)|around here|around us|around me|around|close by|"
		+ "close to (?:me|us|here|a|an|any|the)|down here|in here|underground|below us|under us|beneath us|in this area|in the area|"
		+ "in this cave|here)";

	private static final String WONDERING = "(?:is there|are there|any|do you know (?:of|where|if|any|whether))";

	private static final Pattern STRONG_FRAME = Pattern.compile("\\b" + ASKING + "\\b");
	private static final Pattern PROXIMITY_FRAME = Pattern.compile("\\b" + PROXIMITY + "\\b");
	private static final Pattern WEAK_FRAME = Pattern.compile("\\b" + WONDERING + "\\b");

	private static final Pattern POSITION = Pattern.compile(
		"\\bwhere (?:am i|are we|are you|is this|is here|the heck am i|on earth am i|exactly am i|exactly are we)\\b"
		+ "|\\b(?:what(?:'s| is| are)|whats|tell me|give me|show me|say|share|know) (?:my |our |your |the |these |this |me (?:my |our |the )?)?"
		+ "(?:current |exact )?(?:coordinates|coords|cords|coordinate|position|location|xyz)\\b"
		+ "|^(?:my |our |your |the |current )?(?:coordinates|coords|cords|position|location|xyz)$"
		+ "|\\bwhere(?:'s| is) (?:my|our) (?:position|location)\\b"
		+ "|\\bwhat (?:coordinates|coords) (?:am i|are we)\\b");

	/** "What a lovely biome" admires it; it does not ask which one it is. */
	private static final Pattern BIOME = Pattern.compile(
		"\\b(?:what(?! an? )|which|whats|tell me|name)\\b.*\\bbiome\\b|\\bbiome (?:am i|are we|is this|is it|are you)\\b|^(?:this |the |current )?biome$");

	private static final Pattern DIMENSION = Pattern.compile(
		"\\b(?:what|which|whats)\\b.*\\bdimension\\b|\\bdimension (?:am i|are we|is this|is it)\\b"
		+ "|\\b(?:am i|are we|is this) (?:in )?the (?:nether|end|overworld)\\b|^(?:this |the |current )?dimension$");

	/**
	 * "Time of day" is only a question when something asks for it ("tell me the
	 * time of day"); "I like this time of day" is a remark.
	 */
	private static final Pattern TIME = Pattern.compile(
		"\\bwhat time\\b|\\bwhat(?:'s| is) the time\\b|\\bwhats the time\\b|\\btime is it\\b"
		+ "|\\b(?:what(?:'s| is)?|whats|which|tell me|say|know|check) (?:the )?time of day\\b|^(?:the )?time of day$"
		+ "|\\bis it (?:still |already |almost |nearly )?(?:day|night|daytime|nighttime|night time|day time|morning|evening|dark|noon|midnight|dawn|dusk|sunset|sunrise|late|early)\\b"
		+ "|\\bhow long (?:until|till|til|before|is it until) (?:it'?s |it is |it gets )?(?:night|day|morning|dawn|dusk|sunrise|sunset|dark|nightfall|daylight|daytime|nighttime)\\b"
		+ "|\\bwhen (?:is|does|will) (?:it (?:be|get) )?(?:night|morning|day|sunrise|sunset|nightfall|dark|dawn|dusk)\\b"
		+ "|\\bcan (?:i|we) sleep\\b");

	private static final Pattern WEATHER = Pattern.compile(
		"\\bis it (?:still |going to |gonna )?(?:rain|raining|rainy|snowing|snow|storming|stormy|thundering|sunny|clear)\\b"
		+ "|\\b(?:what(?:'s| is)|whats|how(?:'s| is)|hows) the weather\\b"
		// "what will the weather be like" asks; "I love weather like this" does not.
		+ "|\\bweather (?:going to be |gonna be |be )?like\\b(?! (?:this|that|these|those|it)\\b)"
		+ "|\\bis there a (?:storm|thunderstorm)\\b|^(?:the )?weather$");

	private static final Pattern BLOCK_BELOW = Pattern.compile(
		"\\bwhat (?:block |kind of block |type of block )?(?:am i|are we|are you) (?:standing|walking|sitting) on\\b"
		+ "|\\b(?:what's|what is|whats) (?:under|below|beneath|underneath) (?:me|us|you|my feet|our feet|your feet)\\b"
		+ "|\\bwhat block (?:is this|is that|am i on|are we on|is under|is below)\\b");

	private static final Pattern SPAWN = Pattern.compile(
		"\\b(?:where(?:'s| is)|wheres|which way is|which way to|how far is|how far to|direction to|way to|find|locate) (?:the )?(?:world )?spawn(?: point|point)?\\b");

	/**
	 * "Look around" is a request when it is said to Falsity. Said of oneself
	 * ("let me look around", "I'll look around first", "we should look
	 * around") it is a plan, and plans are conversation.
	 */
	private static final Pattern SURROUNDINGS = Pattern.compile(
		"\\bwhat (?:do|can) you see\\b"
		+ "|(?<!\\b(?:i|we|me|us|to|i'll|we'll|ill|gonna|wanna|should|will|shall|might|can|could|would|must) )(?<!\\blet's )(?<!\\blets )\\blook around\\b"
		+ "|\\bwhat(?:'s| is) (?:around|out there)\\b|\\bdescribe (?:this place|the area|where we are|our surroundings|the surroundings)\\b"
		+ "|\\bwhat(?:'s| is) this place\\b|\\bwhat is around (?:us|me|here)\\b");

	private static final Pattern CAVE_WORD = Pattern.compile("\\b(?:cave|caves|cavern|caverns|ravine|ravines)\\b");
	private static final Pattern ORE_WORD = Pattern.compile("\\b(?:ore|ores|minerals)\\b");

	/** First-person statements about the past are stories, not questions. */
	private static final Pattern STORY = Pattern.compile(
		"^(?:i|we) (?:found|saw|went|was|were|visited|built|made|had|did|died|lost|killed|love|like|hate|remember|came|got|lived|have been|just)\\b");

	/**
	 * Openings that state something rather than ask: "there are diamonds
	 * here", "here are some diamonds for you", "I know where the village is".
	 * A question mark turns them back into questions.
	 */
	private static final Pattern STATEMENT = Pattern.compile(
		"^(?:there'?s|there (?:is|are|was|were)|here'?s|here (?:is|are)|it'?s|it (?:is|was)|that'?s|that (?:is|was)|this is|these are|those are"
		+ "|(?:i|we) (?:know|have(?! to\\b)|think|guess|bet|heard|mined|own|keep)|i'?ve|we'?ve)\\b");

	/** Where one thought ends and the next begins inside a sentence. */
	private static final Pattern CLAUSE_BREAK = Pattern.compile("\\s*[,;:.!?]+\\s*|\\s+(?:but|so)\\s+", Pattern.CASE_INSENSITIVE);

	/** How a question starts when nobody bothers with the question mark: "is the village nearby", "what ores are around". */
	private static final Pattern INTERROGATIVE = Pattern.compile(
		"^(?:what|which|where|how|is|are|any|do|does|did|can|could|will|would|have|has)\\b");

	/** Words that add nothing to "diamonds nearby" or "a village near here". */
	private static final Pattern FILLER = Pattern.compile("\\b(?:a|an|the|some|any|more|other)\\b");

	/**
	 * "My iron", "our village", "your elytra": the thing belongs to someone, so
	 * the player is not asking where the world generated one.
	 */
	private static final Pattern OWNED = Pattern.compile("(?:^|\\s)(?:my|our|your|his|her|their)(?:\\s+[a-z]+){0,2}$");

	private WorldQueryRecognizer() {
	}

	/** The world question this message asks, or empty when it is not one. */
	public static Optional<WorldQuery> recognize(String message) {
		Optional<WorldQuery> simple = recognizeSimple(message);
		if (simple.isPresent()) {
			return simple;
		}
		return recognizeSearch(message);
	}

	/**
	 * Only the questions about the here and now (position, biome, dimension,
	 * time, weather, the block underfoot, spawn). These have wording specific
	 * enough to be checked before anything else.
	 */
	public static Optional<WorldQuery> recognizeSimple(String message) {
		String text = normalise(message);
		if (text.isEmpty()) {
			return Optional.empty();
		}
		if (POSITION.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.POSITION, "", text));
		}
		if (BIOME.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.BIOME, "", text));
		}
		if (DIMENSION.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.DIMENSION, "", text));
		}
		if (BLOCK_BELOW.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.BLOCK_BELOW, "", text));
		}
		if (SPAWN.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.SPAWN, "", text));
		}
		if (TIME.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.TIME, "", text));
		}
		if (WEATHER.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.WEATHER, "", text));
		}
		return Optional.empty();
	}

	/** Searches: structures, ores, caves and a general look around. */
	public static Optional<WorldQuery> recognizeSearch(String message) {
		return recognizeSearch(message, message != null && message.trim().endsWith("?"));
	}

	/**
	 * Searches, for a sentence whose closing punctuation has already been
	 * removed. {@code question} says whether it ended in a question mark.
	 *
	 * <p>A place or ore name is only a search when the sentence asks for one:
	 * "where is the nearest village", "any diamonds nearby", "find a cave".
	 * A place word next to "nearby" or "here" is a search only as a question
	 * ("village nearby?", "is the village near here") or with nothing else
	 * said ("diamonds nearby").
	 * Statements such as "there are diamonds here" stay conversation.
	 */
	public static Optional<WorldQuery> recognizeSearch(String message, boolean question) {
		if (message == null) {
			return Optional.empty();
		}
		// "I just got here, where is the nearest village?": the story ends at
		// the comma, so each part of the sentence is read on its own.
		for (String part : CLAUSE_BREAK.split(message)) {
			String clause = normalise(part);
			if (clause.isEmpty() || STORY.matcher(clause).find()) {
				continue;
			}
			if (!question && STATEMENT.matcher(clause).find()) {
				continue;
			}
			Optional<WorldQuery> found = searchClause(clause, question || INTERROGATIVE.matcher(clause).find());
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	private static Optional<WorldQuery> searchClause(String text, boolean question) {
		boolean strong = STRONG_FRAME.matcher(text).find();
		boolean near = PROXIMITY_FRAME.matcher(text).find();
		boolean weak = WEAK_FRAME.matcher(text).find();

		Optional<StructureCatalog.Match> structure = StructureCatalog.match(text);
		if (structure.isPresent() && !OWNED.matcher(structure.get().before()).find()
			&& (strong || weak || (near && (question || isBare(text, structure.get().alias()))))) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.STRUCTURE, structure.get().entry().key(), text));
		}

		// "Is there gold in this cave" is about gold: a cave word that only
		// says where to look is not what is being looked for.
		Matcher cave = CAVE_WORD.matcher(PROXIMITY_FRAME.matcher(text).replaceAll(" "));
		if (cave.find() && (strong || weak || (near && (question || isBare(text, cave.group()))))) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.CAVE, "", text));
		}

		// Ores need more than "any": "do you have any diamonds" is not a search.
		Optional<OreCatalog.Match> ore = OreCatalog.match(text);
		if (ore.isPresent() && !OWNED.matcher(ore.get().before()).find()
			&& (strong || (near && (weak || question || isBare(text, ore.get().alias()))))) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.ORE, ore.get().entry().key(), text));
		}
		Matcher oreWord = ORE_WORD.matcher(text);
		if (oreWord.find() && (strong || weak || (near && (question || isBare(text, oreWord.group()))))) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.ORE, "", text));
		}

		if (SURROUNDINGS.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.SURROUNDINGS, "", text));
		}
		return Optional.empty();
	}

	/** Whether the text is nothing but the subject and where to look: "diamonds nearby", "a village near here". */
	private static boolean isBare(String text, String subject) {
		String rest = " " + PROXIMITY_FRAME.matcher(text).replaceAll(" ") + " ";
		rest = rest.replace(" " + subject + " ", " ");
		return FILLER.matcher(rest).replaceAll(" ").isBlank();
	}

	/**
	 * Reads the short query a model puts in a {@code world_query} action, such
	 * as "nearest village", "where am i" or "diamonds nearby". The model only
	 * chooses what to ask; the answer always comes from the server.
	 */
	public static Optional<WorldQuery> fromActionQuery(String query) {
		String text = normalise(query);
		if (text.isEmpty()) {
			return Optional.empty();
		}
		String raw = query.trim().toLowerCase(Locale.ROOT);
		// An explicit "structure:" or "ore:" names exactly one thing. If that
		// thing is unknown the query is refused rather than turned into a
		// search for something else.
		if (raw.startsWith("structure:")) {
			String name = raw.substring("structure:".length());
			Optional<StructureCatalog.Entry> entry = StructureCatalog.byKey(name);
			if (entry.isEmpty()) {
				entry = StructureCatalog.find(name);
			}
			return entry.map(found -> new WorldQuery(WorldQuery.Kind.STRUCTURE, found.key(), text));
		}
		if (raw.startsWith("ore:")) {
			String name = raw.substring("ore:".length());
			Optional<OreCatalog.Entry> entry = OreCatalog.byKey(name);
			if (entry.isEmpty()) {
				entry = OreCatalog.find(name);
			}
			return entry.map(found -> new WorldQuery(WorldQuery.Kind.ORE, found.key(), text));
		}
		Optional<WorldQuery> simple = recognizeSimple(text);
		if (simple.isPresent()) {
			return simple;
		}
		switch (text) {
			case "position", "coordinates", "coords", "location", "where" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.POSITION, "", text));
			}
			case "biome" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.BIOME, "", text));
			}
			case "dimension" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.DIMENSION, "", text));
			}
			case "time", "day", "night" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.TIME, "", text));
			}
			case "weather", "rain" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.WEATHER, "", text));
			}
			case "spawn", "world spawn" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.SPAWN, "", text));
			}
			case "surroundings", "look around", "around" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.SURROUNDINGS, "", text));
			}
			case "block below", "block", "ground" -> {
				return Optional.of(new WorldQuery(WorldQuery.Kind.BLOCK_BELOW, "", text));
			}
			default -> {
				// Fall through to the searches below.
			}
		}

		// The model has already decided this is a search, so no question frame is needed.
		Optional<StructureCatalog.Entry> structure = StructureCatalog.find(text);
		if (structure.isPresent()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.STRUCTURE, structure.get().key(), text));
		}
		if (CAVE_WORD.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.CAVE, "", text));
		}
		Optional<OreCatalog.Entry> ore = OreCatalog.find(text);
		if (ore.isPresent()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.ORE, ore.get().key(), text));
		}
		if (ORE_WORD.matcher(text).find()) {
			return Optional.of(new WorldQuery(WorldQuery.Kind.ORE, "", text));
		}
		return recognizeSearch(text);
	}

	/**
	 * Whether the text names a place rather than a thing that can be handed
	 * over: a structure or a cave. "Find me a village" is a search, not a gift.
	 */
	public static boolean namesPlace(String text) {
		String normalised = normalise(text);
		return !normalised.isEmpty()
			&& (StructureCatalog.find(normalised).isPresent() || CAVE_WORD.matcher(normalised).find());
	}

	/**
	 * Whether a message asks where something is, even if Falsity has no way to
	 * look it up. Used to remind the model that it has no verified location.
	 */
	public static boolean asksForLocation(String message) {
		String text = normalise(message);
		return !text.isEmpty() && (STRONG_FRAME.matcher(text).find() || text.contains("coordinates") || text.contains("coords"));
	}

	static String normalise(String message) {
		if (message == null) {
			return "";
		}
		return message.toLowerCase(Locale.ROOT)
			.replace('\u2019', '\'')
			.replace('_', ' ')
			.replaceAll("[^a-z0-9' ]", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}
}
