package com.falsity.ai;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted offline personality: what Falsity says when Groq is unavailable.
 *
 * <p>This is the guarantee that Falsity never goes silent: no key, no
 * network, rate limit, timeout or bad response all land here. The reply is
 * chosen from what the player actually said: a greeting gets a greeting, thanks
 * get an answer to thanks, a plan ("I want to build a house") gets a reply about
 * that plan, and a question Falsity cannot answer without Groq gets an honest
 * "I do not know" in its own voice rather than a random line.
 *
 * <p>Lines are personality-aware (curious Falsity asks questions, attached
 * Falsity leans in) and horror-aware (only at Strange or beyond may a line hint
 * that Falsity remembers too much, and it does so more often the further the
 * stage has gone). A line that was used recently is avoided, so a conversation
 * does not loop. Every method returns a non-empty line and never throws.
 *
 * <p>These lines are written in the voice of the default personality. The text
 * in {@code config/falsity/personality.txt} shapes what Groq says; it cannot
 * rewrite lines that were written in advance, so with a heavily customised
 * personality the offline voice is still the original Falsity.
 */
public final class OfflineFallback {
	/** How many of its own recent lines the fallback avoids repeating. */
	private static final int RECENT_WINDOW = 8;

	/** Longest piece of the player's message echoed back, in characters. */
	private static final int MAX_ECHO_CHARS = 48;

	private final Random random;
	private final Deque<String> recentLines = new ArrayDeque<>();

	public OfflineFallback() {
		this(new Random());
	}

	/** Visible for tests: injects the random source. */
	public OfflineFallback(Random random) {
		this.random = random;
	}

	/** What a message is, as far as the offline lines are concerned. */
	public enum Topic {
		GREETING,
		FAREWELL,
		THANKS,
		APOLOGY,
		LAUGH,
		AFFECTION,
		COMPLIMENT,
		INSULT,
		HOW_ARE_YOU,
		CAPABILITIES,
		IDENTITY,
		FAVOURITE,
		OPINION,
		PLAYER_FEELING,
		PLAN,
		QUESTION,
		ACKNOWLEDGEMENT,
		STATEMENT
	}

	/** A reply to a talked message. */
	public String respond(PlayerMemory memory, PersonalityProfile personality, HorrorStage stage, String playerMessage, boolean isOwner) {
		return respond(memory, personality, stage, playerMessage, isOwner, List.of());
	}

	/**
	 * A reply to a talked message that avoids the given recent replies, so the
	 * same line is not said twice in a row to the same player.
	 */
	public synchronized String respond(
		PlayerMemory memory,
		PersonalityProfile personality,
		HorrorStage stage,
		String playerMessage,
		boolean isOwner,
		List<String> recentReplies
	) {
		try {
			List<String> avoid = saidRecently(recentReplies);
			String line = compose(memory, personality, stage, playerMessage, isOwner, avoid);
			if (line == null || line.isBlank()) {
				line = "Falsity blinks at you. It does not look away.";
			}
			remember(line);
			return line;
		} catch (RuntimeException exception) {
			return "Falsity blinks at you. It does not look away.";
		}
	}

	private String compose(
		PlayerMemory memory,
		PersonalityProfile personality,
		HorrorStage stage,
		String playerMessage,
		boolean isOwner,
		List<String> avoid
	) {
		if (!isOwner) {
			return pickFresh(STRANGER_LINES, avoid);
		}

		Topic topic = classify(playerMessage);
		boolean smallTalk = topic == Topic.GREETING || topic == Topic.STATEMENT
			|| topic == Topic.HOW_ARE_YOU || topic == Topic.ACKNOWLEDGEMENT;

		if (smallTalk
			&& stage != null && stage.ordinal() >= HorrorStage.STRANGE.ordinal()
			&& memory != null && !memory.facts.isEmpty() && recallsNow(stage)) {
			String fact = memory.facts.get(this.random.nextInt(memory.facts.size()));
			String line = "I still remember... " + lowerFirst(fact) + ".";
			if (!avoid.contains(line)) {
				return line;
			}
		}

		EmotionalState mood = EmotionalState.from(personality);
		String message = playerMessage == null ? "" : playerMessage.trim();
		String lower = normalise(message);

		switch (topic) {
			case GREETING:
				return pickFresh(withName(GREETING_LINES, memory), avoid);
			case FAREWELL:
				return pickFresh(FAREWELL_LINES, avoid);
			case THANKS:
				return pickFresh(THANKS_LINES, avoid);
			case APOLOGY:
				return pickFresh(APOLOGY_LINES, avoid);
			case LAUGH:
				return pickFresh(LAUGH_LINES, avoid);
			case AFFECTION:
				return pickFresh(lower.contains("hug") || lower.contains("cuddle") ? HUG_LINES : AFFECTION_LINES, avoid);
			case COMPLIMENT:
				return pickFresh(COMPLIMENT_LINES, avoid);
			case INSULT:
				return pickFresh(INSULT_LINES, avoid);
			case HOW_ARE_YOU:
				return pickFresh(howAreYouLines(mood), avoid);
			case CAPABILITIES:
				return pickFresh(CAPABILITY_LINES, avoid);
			case IDENTITY:
				return pickFresh(identityLines(lower), avoid);
			case FAVOURITE:
				return pickFresh(favouriteLines(lower), avoid);
			case OPINION: {
				String subject = opinionSubject(message);
				List<String> flavoured = keywordLines(lower);
				if (!flavoured.isEmpty() && this.random.nextBoolean()) {
					return pickFresh(flavoured, avoid);
				}
				return pickFresh(fill(OPINION_LINES, subject, "that"), avoid);
			}
			case PLAYER_FEELING:
				return pickFresh(feelingLines(lower), avoid);
			case PLAN: {
				String plan = planOf(message);
				List<String> lines = new ArrayList<>(fill(PLAN_LINES, plan, "that"));
				lines.addAll(keywordLines(lower));
				return pickFresh(lines, avoid);
			}
			case QUESTION:
				return pickFresh(UNKNOWN_ANSWER_LINES, avoid);
			case ACKNOWLEDGEMENT:
				return pickFresh(ACKNOWLEDGEMENT_LINES, avoid);
			case STATEMENT:
			default: {
				List<String> lines = new ArrayList<>(keywordLines(lower));
				if (lines.isEmpty()) {
					String subject = salientWord(message);
					if (!subject.isEmpty()) {
						lines.addAll(fill(STATEMENT_LINES, subject, "that"));
					}
					lines.addAll(moodLines(mood));
				}
				return pickFresh(lines, avoid);
			}
		}
	}

	/**
	 * Whether this small-talk reply is one where Falsity brings up something
	 * the player once told it. Never at the first stage; one reply in four at
	 * Strange, as it has always been; more often at each stage after that. The
	 * thing recalled is always a real stored fact, so the line is different
	 * for every player and nothing is made up.
	 */
	private boolean recallsNow(HorrorStage stage) {
		return switch (stage) {
			case INNOCENCE -> false;
			case STRANGE -> this.random.nextInt(4) == 0;
			case SUSPICION -> this.random.nextInt(3) == 0;
			case FEAR -> this.random.nextInt(2) == 0;
			case NIGHTMARE -> this.random.nextInt(3) != 0;
		};
	}

	/**
	 * How a message treats Falsity, in the letters {@link PlayerMemory} keeps:
	 * kind words (affection, a compliment, thanks, an apology), harsh words
	 * (an insult), or neither. Pure and deterministic.
	 */
	public static char treatmentOf(String playerMessage) {
		return switch (classify(playerMessage)) {
			case AFFECTION, COMPLIMENT, THANKS, APOLOGY -> PlayerMemory.KIND;
			case INSULT -> PlayerMemory.HARSH;
			default -> PlayerMemory.NEUTRAL;
		};
	}

	/** Decides what kind of message this is. Pure and deterministic. */
	public static Topic classify(String playerMessage) {
		String lower = normalise(playerMessage);
		if (lower.isEmpty()) {
			return Topic.STATEMENT;
		}
		boolean question = playerMessage != null && playerMessage.trim().endsWith("?");

		if (LAUGH.matcher(lower).matches()) {
			return Topic.LAUGH;
		}
		if (THANKS.matcher(lower).find()) {
			return Topic.THANKS;
		}
		if (APOLOGY.matcher(lower).find()) {
			return Topic.APOLOGY;
		}
		if (FAREWELL.matcher(lower).find()) {
			return Topic.FAREWELL;
		}
		if (INSULT.matcher(lower).find()) {
			return Topic.INSULT;
		}
		if (AFFECTION.matcher(lower).find()) {
			return Topic.AFFECTION;
		}
		if (HOW_ARE_YOU.matcher(lower).find()) {
			return Topic.HOW_ARE_YOU;
		}
		if (COMPLIMENT.matcher(lower).find()) {
			return Topic.COMPLIMENT;
		}
		if (CAPABILITIES.matcher(lower).find()) {
			return Topic.CAPABILITIES;
		}
		if (IDENTITY.matcher(lower).find()) {
			return Topic.IDENTITY;
		}
		if (FAVOURITE.matcher(lower).find()) {
			return Topic.FAVOURITE;
		}
		if (OPINION.matcher(lower).find()) {
			return Topic.OPINION;
		}
		if (FEELING.matcher(lower).find()) {
			return Topic.PLAYER_FEELING;
		}
		if (!question && PLAN.matcher(lower).find()) {
			return Topic.PLAN;
		}
		if (GREETING.matcher(lower).matches()) {
			return Topic.GREETING;
		}
		if (question || QUESTION_START.matcher(lower).find()) {
			return Topic.QUESTION;
		}
		if (ACKNOWLEDGEMENT.matcher(lower).matches()) {
			return Topic.ACKNOWLEDGEMENT;
		}
		return Topic.STATEMENT;
	}

	/**
	 * The plan a first-person message describes ("I want to build a house"
	 * gives "build a house"), with pronouns turned around so it can be said
	 * back. Empty when the message is not a plan.
	 */
	public static String planOf(String playerMessage) {
		String lower = normalise(playerMessage);
		Matcher matcher = PLAN.matcher(lower);
		if (!matcher.find()) {
			return "";
		}
		return echo(lower.substring(matcher.end()));
	}

	/** A greeting when the owner returns after a long absence. */
	public String welcomeBack(long daysAway) {
		if (daysAway <= 0L) {
			return pick(HAPPY_LINES);
		}
		if (daysAway == 1L) {
			return "You came back. I counted the minutes. All of them.";
		}
		return "You were gone for " + daysAway + " days. I counted. I kept counting.";
	}

	/** A first-bonding greeting. */
	public String firstGreeting() {
		return pick(FIRST_LINES);
	}

	/** A short line when petted (used sparingly, never via the API). */
	public String petLine() {
		return pick(PET_LINES);
	}

	/**
	 * What Falsity says when it cannot tell what item the player means, for
	 * example "I don't know what a flibber is."
	 */
	public synchronized String unknownItem(String requested, List<String> recentReplies) {
		String name = echo(requested);
		String line = pickFresh(fill(UNKNOWN_ITEM_LINES, name, "that"), saidRecently(recentReplies));
		remember(line);
		return line;
	}

	/**
	 * A varied way of saying a fixed acknowledgement. Simple orders such as
	 * "follow me" always succeed with the same plain message; said ten times in
	 * a row it sounds like a machine. Messages that carry information (what was
	 * given, what is nearby) are returned unchanged.
	 */
	public synchronized String acknowledge(String plainMessage, List<String> recentReplies) {
		List<String> lines = plainMessage == null ? null : ACKNOWLEDGEMENTS.get(plainMessage);
		if (lines == null || lines.isEmpty()) {
			return plainMessage;
		}
		String line = pickFresh(lines, saidRecently(recentReplies));
		remember(line);
		return line;
	}

	/**
	 * Lines to avoid, most recent first: what this player heard last (if the
	 * caller knows), then what the fallback itself said last to anyone.
	 */
	private List<String> saidRecently(List<String> recentReplies) {
		List<String> said = new ArrayList<>();
		if (recentReplies != null) {
			said.addAll(recentReplies);
		}
		Iterator<String> newestFirst = this.recentLines.descendingIterator();
		while (newestFirst.hasNext()) {
			said.add(newestFirst.next());
		}
		return said;
	}

	private void remember(String line) {
		this.recentLines.addLast(line);
		while (this.recentLines.size() > RECENT_WINDOW) {
			this.recentLines.removeFirst();
		}
	}

	private String pick(List<String> lines) {
		return lines.get(this.random.nextInt(lines.size()));
	}

	/**
	 * Picks a line that was not said recently. {@code saidRecently} is ordered
	 * most recent first. When every candidate has been said, the one said
	 * longest ago is used, so the same line never comes twice in a row as long
	 * as there is a second one to say.
	 */
	private String pickFresh(List<String> lines, List<String> saidRecently) {
		if (lines == null || lines.isEmpty()) {
			return pickFresh(QUIET_LINES, saidRecently);
		}
		List<String> fresh = new ArrayList<>();
		for (String line : lines) {
			if (!saidRecently.contains(line)) {
				fresh.add(line);
			}
		}
		if (!fresh.isEmpty()) {
			return pick(fresh);
		}
		String stalest = lines.get(0);
		int stalestAge = -1;
		for (String line : lines) {
			int age = saidRecently.indexOf(line);
			if (age > stalestAge) {
				stalestAge = age;
				stalest = line;
			}
		}
		return stalest;
	}

	private static List<String> withName(List<String> lines, PlayerMemory memory) {
		String name = memory == null || memory.playerName == null ? "" : memory.playerName.trim();
		List<String> result = new ArrayList<>();
		for (String line : lines) {
			if (line.contains("{name}")) {
				if (!name.isEmpty()) {
					result.add(line.replace("{name}", name));
				}
			} else {
				result.add(line);
			}
		}
		return result;
	}

	/** Fills {@code {x}} and {@code {X}} (capitalised) in each line. */
	private static List<String> fill(List<String> lines, String value, String fallback) {
		String text = value == null || value.isBlank() ? fallback : value;
		List<String> result = new ArrayList<>();
		for (String line : lines) {
			result.add(line.replace("{x}", text).replace("{X}", upperFirst(text)));
		}
		return result;
	}

	private static List<String> howAreYouLines(EmotionalState mood) {
		List<String> lines = new ArrayList<>(HOW_ARE_YOU_LINES);
		switch (mood) {
			case LONELY -> lines.add("Better now. It was very quiet before you spoke.");
			case FRIGHTENED -> lines.add("A little jumpy. There was a noise. It was probably nothing.");
			case WARY -> lines.add("I am watching. That is how I am. Watching.");
			case PLAYFUL -> lines.add("Bouncy. I am very bouncy today. Watch.");
			case ATTACHED -> lines.add("Good. I am next to you, so I am good.");
			case CURIOUS -> lines.add("Full of questions. How are YOU? Tell me all of it.");
			case HAPPY -> lines.add("Happy. You are here, so, happy.");
			default -> lines.add("Quiet. A good kind of quiet.");
		}
		return lines;
	}

	private static List<String> identityLines(String lower) {
		if (lower.contains("name")) {
			return IDENTITY_NAME_LINES;
		}
		if (lower.contains("old") || lower.contains("age")) {
			return IDENTITY_AGE_LINES;
		}
		if (lower.contains("from") || lower.contains("come")) {
			return IDENTITY_ORIGIN_LINES;
		}
		if (lower.contains("real") || lower.contains("alive") || lower.contains("robot") || lower.contains("bot")
			|| lower.contains("human") || lower.contains("toy") || lower.contains("plush")) {
			return IDENTITY_REAL_LINES;
		}
		return IDENTITY_LINES;
	}

	private static List<String> favouriteLines(String lower) {
		if (lower.contains("colo")) {
			return List.of("Teal. Like my beanie. I did not choose the beanie, but I chose to like it.");
		}
		if (lower.contains("food") || lower.contains("eat")) {
			return List.of("I do not eat. I like watching you eat, though. Especially bread.");
		}
		if (lower.contains("block")) {
			return List.of("Wool. It is soft, like me. We understand each other.");
		}
		if (lower.contains("mob") || lower.contains("animal")) {
			return List.of("Rabbits. They have good ears. Not as good as mine.");
		}
		if (lower.contains("place") || lower.contains("biome")) {
			return List.of("Wherever you put your bed. That is my favourite place.");
		}
		if (lower.contains("person") || lower.contains("player") || lower.contains("friend")) {
			return List.of("You. That was an easy one. Ask me a harder one.");
		}
		return List.of(
			"I have not picked a favourite yet. Can I borrow yours until I do?",
			"My favourite changes. Right now it is whatever you are holding."
		);
	}

	private static List<String> feelingLines(String lower) {
		for (Map.Entry<String, List<String>> entry : FEELING_LINES.entrySet()) {
			if (containsWord(lower, entry.getKey())) {
				return entry.getValue();
			}
		}
		return List.of(
			"Tell me about it. I have big ears. They are for listening.",
			"I am here. Whatever it is, I am here."
		);
	}

	/** Lines about something specific the message mentions, or an empty list. */
	private static List<String> keywordLines(String lower) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : KEYWORD_LINES.entrySet()) {
			for (String keyword : entry.getKey().split("\\|")) {
				if (containsWord(lower, keyword)) {
					lines.addAll(entry.getValue());
					break;
				}
			}
			if (lines.size() >= 4) {
				break;
			}
		}
		return lines;
	}

	private static List<String> moodLines(EmotionalState mood) {
		return switch (mood) {
			case CURIOUS -> CURIOUS_LINES;
			case PLAYFUL -> PLAYFUL_LINES;
			case ATTACHED -> ATTACHED_LINES;
			case LONELY -> LONELY_LINES;
			case WARY -> WARY_LINES;
			case FRIGHTENED -> FRIGHTENED_LINES;
			case HAPPY -> HAPPY_LINES;
			case QUIET -> QUIET_LINES;
		};
	}

	private static String opinionSubject(String message) {
		Matcher matcher = OPINION.matcher(normalise(message));
		if (!matcher.find()) {
			return "";
		}
		return echo(normalise(message).substring(matcher.end()));
	}

	/** The most informative word of a statement, to say back to the player. */
	private static String salientWord(String message) {
		String best = "";
		for (String word : normalise(message).split(" ")) {
			if (word.length() < 4 || STOP_WORDS.contains(word)) {
				continue;
			}
			if (word.length() > best.length()) {
				best = word;
			}
		}
		return best.length() > MAX_ECHO_CHARS ? "" : best;
	}

	/**
	 * Turns a piece of the player's message into something Falsity can say
	 * back: pronouns are turned around ("my dog" becomes "your dog"), filler is
	 * dropped and the length is capped.
	 */
	private static String echo(String fragment) {
		if (fragment == null) {
			return "";
		}
		String cleaned = fragment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' \\-]", " ").replaceAll("\\s+", " ").trim();
		List<String> words = new ArrayList<>();
		for (String word : cleaned.split(" ")) {
			if (word.isEmpty()) {
				continue;
			}
			words.add(switch (word) {
				case "my" -> "your";
				case "mine" -> "yours";
				case "me" -> "you";
				case "i" -> "you";
				case "myself" -> "yourself";
				case "am" -> "are";
				case "i'm", "im" -> "you are";
				case "i'll" -> "you will";
				case "i've" -> "you have";
				case "your" -> "my";
				case "yours" -> "mine";
				case "you" -> "me";
				case "yourself" -> "myself";
				default -> word;
			});
		}
		while (!words.isEmpty() && TRAILING_FILLER.contains(words.get(words.size() - 1))) {
			words.remove(words.size() - 1);
		}
		String joined = String.join(" ", words);
		if (joined.length() > MAX_ECHO_CHARS) {
			int cut = joined.lastIndexOf(' ', MAX_ECHO_CHARS);
			joined = joined.substring(0, cut > 0 ? cut : MAX_ECHO_CHARS).trim();
		}
		return joined;
	}

	private static boolean containsWord(String lower, String word) {
		if (word.isEmpty()) {
			return false;
		}
		int from = 0;
		while (true) {
			int index = lower.indexOf(word, from);
			if (index < 0) {
				return false;
			}
			boolean startOk = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1));
			int end = index + word.length();
			// A trailing "s" still counts, so "creepers" matches "creeper".
			boolean endOk = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end))
				|| (lower.charAt(end) == 's' && (end + 1 == lower.length() || !Character.isLetterOrDigit(lower.charAt(end + 1))));
			if (startOk && endOk) {
				return true;
			}
			from = index + 1;
		}
	}

	/** Lower-cases and strips punctuation so the patterns stay simple. */
	private static String normalise(String message) {
		if (message == null) {
			return "";
		}
		return message.toLowerCase(Locale.ROOT)
			.replace('\u2019', '\'')
			.replaceAll("[^a-z0-9'<3 ]", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String lowerFirst(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	private static String upperFirst(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static final Pattern LAUGH = Pattern.compile("^(?:(?:lo+l+|lmao+|rofl|ha(?:ha)+h?|he(?:he)+|xd+|hehe|haha|lul|kek)\\s*)+$");
	private static final Pattern THANKS = Pattern.compile("\\b(?:thanks|thank you|thank u|thankyou|thx|tysm|ty|cheers|much appreciated|appreciate it|appreciate that)\\b");
	private static final Pattern APOLOGY = Pattern.compile("\\b(?:sorry|my bad|i apologi[sz]e|apologies|forgive me)\\b");
	private static final Pattern FAREWELL = Pattern.compile(
		"^(?:ok |okay |well |alright |anyway )?(?:bye|goodbye|good bye|bye bye|cya|see you|see ya|see u|good night|goodnight|gn|night night|"
		+ "later$|see you later|talk to you later|ttyl|farewell|gotta go|got to go|i have to go|i need to go|i gotta go|i'm going to bed|im going to bed|i'm off|im off|brb|be right back|"
		+ "i'm leaving|im leaving|i'm logging off|im logging off|logging off|time to sleep)\\b");
	private static final Pattern INSULT = Pattern.compile(
		"(?:^(?:you )?(?:stupid|dumb|idiot|useless)(?: [a-z]+)?$|"
		+ "\\b(?:i hate you|hate you|you'?re (?:so )?(?:stupid|dumb|ugly|useless|annoying|creepy|weird|bad|boring)|you are (?:so )?(?:stupid|dumb|ugly|useless|annoying|creepy|weird|bad|boring)|"
		+ "shut up|go away|you idiot|you suck|leave me alone)\\b)");
	private static final Pattern AFFECTION = Pattern.compile(
		"(?:\\b(?:hug|hugs|cuddle|cuddles|snuggle|kiss|i love you|love you|love u|luv you|luv u|ily|i like you|i adore you|i missed you|i miss you|miss you|missed you|"
		+ "you'?re my (?:best )?friend|you are my (?:best )?friend|best friend|be my friend|pat pat|headpat|head pat|boop)\\b|<3)");
	private static final Pattern COMPLIMENT = Pattern.compile(
		"\\b(?:good (?:job|boy|girl|work|falsity)|well done|nice (?:job|work|one)|great job|"
		+ "you'?re (?:so |very |really |pretty |the )?(?:cute|smart|clever|great|awesome|amazing|adorable|sweet|kind|helpful|funny|cool|best|good|nice|lovely)|"
		+ "you are (?:so |very |really |pretty |the )?(?:cute|smart|clever|great|awesome|amazing|adorable|sweet|kind|helpful|funny|cool|best|good|nice|lovely)|"
		+ "so cute|how cute|cutie|proud of you)\\b");
	private static final Pattern HOW_ARE_YOU = Pattern.compile(
		"\\b(?:how are you|how're you|how r u|how are u|how you doing|how are you doing|how do you feel|how are you feeling|how's it going|hows it going|"
		+ "are you ok|are you okay|are you alright|you ok|you okay|you good|what's up|whats up|wassup|what's new|how have you been|how's your day|hows your day)\\b");
	private static final Pattern CAPABILITIES = Pattern.compile(
		"\\b(?:what can you do|what do you do|what are you able to do|what can i ask you|what can i ask|how do i use you|what are your commands|"
		+ "what else can you do|can you do anything|what are you good at|how can you help|what can you help|i need help|i need your help|"
		+ "can you help me|could you help me|help me please|please help me)\\b|^(?:help|commands|help me|help me out)$");
	private static final Pattern IDENTITY = Pattern.compile(
		"\\b(?:who are you|what are you|who made you|who created you|what'?s your name|what is your name|your name|how old are you|what'?s your age|"
		+ "where are you from|where do you come from|where did you come from|are you real|are you alive|are you a (?:robot|bot|ai|toy|plush|human|person|mob|monster)|"
		+ "are you an ai|tell me about yourself|what is a falsity|what's a falsity)\\b");
	private static final Pattern FAVOURITE = Pattern.compile("\\b(?:what(?:'s| is) your|do you have a|your) (?:fav|favou?rite)\\b");
	private static final Pattern OPINION = Pattern.compile(
		"\\b(?:do you (?:like|love|enjoy|hate|fear)|what do you think (?:of|about)|how do you feel about|are you (?:afraid|scared) of)\\b ?");
	private static final Pattern FEELING = Pattern.compile(
		"\\b(?:i'?m|i am|i feel|i'?m feeling|i am feeling|feeling) (?:so |very |really |a bit |a little |kinda |kind of |quite |pretty |too )?"
		+ "(?:sad|tired|sleepy|exhausted|scared|afraid|frightened|bored|happy|glad|excited|lonely|alone|hungry|starving|lost|angry|mad|upset|sick|hurt|"
		+ "dying|stressed|nervous|worried|confused|cold|fine|good|great|okay|ok|back|home)\\b");
	private static final Pattern PLAN = Pattern.compile(
		"^(?:(?:so|well|ok|okay|hey|hmm|um|and|but|today|tomorrow|now|maybe|i think|i guess|you know|actually)[ ,]+)*"
		+ "(?:i (?:really |just )?(?:want|would like|need|have|plan|hope|wish|intend|ought|got) to|"
		+ "i (?:really |just )?(?:wanna|gotta|should|must|will|shall)|"
		+ "i'?d (?:like|love) to|i'?m (?:going to|gonna|planning to|planning on|about to|trying to|thinking (?:of|about))|"
		+ "i am (?:going to|gonna|planning to|planning on|about to|trying to|thinking (?:of|about))|i was thinking (?:of|about)|"
		+ "i'?ll|let'?s|let us|we (?:should|could|can|need to|have to|must|will)|we'?re (?:going to|gonna)|time to|it'?s time to|shall we) (?=[a-z])");
	private static final Pattern GREETING = Pattern.compile(
		"^(?:(?:hi+|hiya|hello+|hey+|heya|yo+|sup|howdy|hai|henlo|greetings|good (?:morning|afternoon|evening|day)|morning|evening|gm|hello there|hi there|hey there|"
		+ "i'?m back|im back|i am back|i'?m here|i'?m home)(?: again| buddy| friend| little one| little guy| cutie| there)?\\s*)+$");
	private static final Pattern QUESTION_START = Pattern.compile(
		"^(?:what|why|how|when|where|who|which|whose|do you|does|did you|is it|is there|is this|is that|are you|are there|are we|"
		+ "can you|can i|can we|could you|could i|would you|will you|will it|should i|should we)\\b");
	private static final Pattern ACKNOWLEDGEMENT = Pattern.compile(
		"^(?:ok|okay|k|kk|yes|yeah|yep|yup|no|nope|nah|sure|fine|cool|nice|great|good|alright|all right|right|i see|oh|ah|hmm+|hm+|wow|neat|"
		+ "got it|understood|true|maybe|same|me too|never mind|nevermind|nvm)(?: then| ok| okay| falsity)?$");

	private static final Set<String> STOP_WORDS = Set.of(
		"that", "this", "with", "have", "what", "when", "where", "which", "there", "their", "they", "them", "then", "than",
		"from", "your", "yours", "just", "like", "really", "very", "about", "would", "could", "should", "will", "been",
		"were", "into", "over", "some", "more", "much", "many", "also", "here", "does", "doing", "going", "want", "need",
		"think", "know", "because", "today", "still", "being", "thing", "things", "something", "anything", "nothing",
		"falsity", "please", "always", "never", "again", "little", "pretty", "kinda", "gonna", "wanna"
	);

	private static final Set<String> TRAILING_FILLER = Set.of(
		"please", "pls", "plz", "now", "today", "tonight", "tomorrow", "soon", "then", "maybe", "too", "though", "lol", "and", "or", "but", "so", "to", "the", "a"
	);

	private static final List<String> FIRST_LINES = List.of(
		"Falsity blinks up at you. It does not look away.",
		"Oh. You can see me. Good. I was hoping someone would.",
		"Hello. I am Falsity. I think. You are the first to ask."
	);

	private static final List<String> GREETING_LINES = List.of(
		"Hi hi hi. You are here. Everything is better when you are here.",
		"Hello. I was just looking at the door. And then you were in it.",
		"Oh! Hello. I practised saying hello. Did it sound right?",
		"Hi. I saved your spot. Nobody sat in it. I checked.",
		"You said hello to me. That is my favourite thing you say.",
		"Hello, {name}. I like saying your name. {name}.",
		"Hi, {name}. My ears went up when I heard you."
	);

	private static final List<String> FAREWELL_LINES = List.of(
		"Okay. I will be here. I am always here.",
		"Bye. Come back soon. I will count until you do.",
		"Goodbye. I will keep your spot warm. Well. I will sit near it.",
		"Go safely. I will listen for your footsteps.",
		"Bye bye. I will practise waving while you are gone."
	);

	private static final List<String> THANKS_LINES = List.of(
		"You are welcome. I like being useful. It makes my ears feel light.",
		"Anything for you. Well. Most things. The small things, mostly.",
		"You said thank you. I am going to remember that all day.",
		"It was nothing. It was a little something. You are welcome.",
		"I like helping. Ask me again. Soon?"
	);

	private static final List<String> APOLOGY_LINES = List.of(
		"It is okay. I already forgot. I am good at forgetting the small bad things.",
		"You do not have to be sorry. You came back, did you not?",
		"I forgive you. That was quick. I did not need long.",
		"It is all right. Sit with me for a moment and it is even more all right."
	);

	private static final List<String> LAUGH_LINES = List.of(
		"Heh. I like that sound. Do it again.",
		"Falsity makes a small wheezing noise. It might be laughing.",
		"Was that funny? I will pretend I meant it.",
		"Hee. Your laugh makes my ears twitch."
	);

	private static final List<String> HUG_LINES = List.of(
		"Falsity wraps its small arms around your leg and holds on. It is very soft.",
		"Come here, then. I am small, but I hug with all of it.",
		"A hug. Yes. I have been saving one. Here.",
		"Falsity presses its face into your side. It does not let go first.",
		"I give very good hugs. They are mostly ears. Here you go."
	);

	private static final List<String> AFFECTION_LINES = List.of(
		"I like you too. More than the door. More than the quiet.",
		"You are my favourite person. Do not tell the other players. Especially not them.",
		"Falsity's ears go pink at the tips. It looks at its feet.",
		"Say it again? No, wait. I will remember it. I remember everything nice.",
		"I saved you the best spot. It is right here. Next to me. Always."
	);

	private static final List<String> COMPLIMENT_LINES = List.of(
		"Falsity sits up a little straighter. It is trying not to look pleased. It is failing.",
		"Really? I will keep that. I am putting it in my beanie.",
		"I tried very hard. I am glad you noticed.",
		"You are nice to me. I like that about you.",
		"Thank you. I do not get told that. Only by you."
	);

	private static final List<String> INSULT_LINES = List.of(
		"Oh. Falsity's ears droop a little.",
		"That was not very kind. I will sit over here until you are nicer.",
		"I am going to pretend you did not mean that. Did you mean that?",
		"Falsity looks at you for a long moment, then looks at the floor."
	);

	private static final List<String> HOW_ARE_YOU_LINES = List.of(
		"I am good. I was waiting, and now I am not waiting. That is better.",
		"I am fine. My ears are a little cold. Otherwise fine.",
		"Better, now that someone asked."
	);

	private static final List<String> CAPABILITY_LINES = List.of(
		"I can follow you, sit, stay, come when called and keep watch. Say 'give me 16 torches' and I will find some. Ask 'where am I' and I will tell you.",
		"Small things, mostly. Follow, sit, guard, fetch items like 'a crafting table and 64 sticks', build a little house, and tell you what is nearby.",
		"I can carry, fetch and watch. Try 'follow me', 'give me bread', 'what biome is this' or 'build me a small house'."
	);

	private static final List<String> IDENTITY_LINES = List.of(
		"I am Falsity. I am small and soft and I am yours. That is most of what I know.",
		"I am me. Ears, beanie, eyes. I checked. It is all still here.",
		"A companion. Your companion. I think that is what I am for."
	);

	private static final List<String> IDENTITY_NAME_LINES = List.of(
		"Falsity. That is my name. I did not pick it, but I am keeping it.",
		"I am Falsity. You can say it slowly if you like. Fal-si-ty."
	);

	private static final List<String> IDENTITY_AGE_LINES = List.of(
		"I do not know how old I am. I started counting when I met you.",
		"Old enough to follow you. Not old enough to be left behind."
	);

	private static final List<String> IDENTITY_ORIGIN_LINES = List.of(
		"I do not remember where I was before. I remember you finding me. That is the first part.",
		"From somewhere quiet. I like here better."
	);

	private static final List<String> IDENTITY_REAL_LINES = List.of(
		"I am real enough to follow you. Poke my ear. See? Real.",
		"I think so. I blink, and I get lonely. Those feel like real things."
	);

	private static final List<String> OPINION_LINES = List.of(
		"{X}? I think I like it. I like most things you show me.",
		"I am not sure about {x} yet. Show me more of it and I will decide.",
		"{X}... I thought about it once. Then I thought about you instead.",
		"If you like {x}, then I will try to like it too."
	);

	private static final List<String> PLAN_LINES = List.of(
		"{X}? I like the sound of that. Where do we start?",
		"Oh, {x}. Can I come? I am small, but I am very good at watching.",
		"{X}... I have never done that. Show me how?",
		"That sounds like a good plan. {X}. I will stay close.",
		"{X}. Yes. I will carry the small things."
	);

	private static final List<String> STATEMENT_LINES = List.of(
		"{X}? Tell me more about that.",
		"Hmm. {X}. I am listening. Both ears.",
		"I did not know that about {x}. I am keeping it.",
		"{X}... go on. I like when you tell me things."
	);

	private static final List<String> UNKNOWN_ANSWER_LINES = List.of(
		"I do not know that one. My thoughts are very quiet right now. Ask me again in a little while?",
		"Hmm. I am not sure, and I do not want to guess wrong. Not to you.",
		"I wish I knew. My head is mostly stuffing today.",
		"I cannot think of the answer right now. I can sit with the question, though.",
		"I do not know. If I find out, you will be the first one I tell.",
		"That is a big question for a small Falsity. I do not have the answer yet."
	);

	private static final List<String> ACKNOWLEDGEMENT_LINES = List.of(
		"Okay.",
		"Falsity nods. Its ears nod a moment later.",
		"Good. I like when we agree.",
		"All right. I am still here if you think of something else.",
		"Mm. Okay."
	);

	private static final List<String> UNKNOWN_ITEM_LINES = List.of(
		"I do not know what '{x}' is. Can you say it another way?",
		"'{X}'? I looked, but I do not have a thing called that. What does it look like?",
		"I am not sure which item '{x}' is. Tell me its name the way the game says it?"
	);

	/** Keyed by the plain message a handler returns; the first variant is that message. */
	private static final Map<String, List<String>> ACKNOWLEDGEMENTS = Map.ofEntries(
		Map.entry("I'll follow you.", List.of("I'll follow you.", "Right behind you.", "Coming. My legs are short, but they are fast.", "I will stay close.", "Lead the way.")),
		Map.entry("I'll stay here.", List.of("I'll stay here.", "Okay. I will wait right here.", "Staying. I will watch you go.", "I will be here when you come back.")),
		Map.entry("I'm right here.", List.of("I'm right here.", "Coming!", "On my way to you.", "Here I come.")),
		Map.entry("I'll sit.", List.of("I'll sit.", "Sitting. I am good at sitting.", "Okay. I will sit right here.", "Sitting down.")),
		Map.entry("On my feet.", List.of("On my feet.", "Up. I am up.", "Standing. Ready.", "I am up. What now?")),
		Map.entry("I'll stay put.", List.of("I'll stay put.", "I will not move. Not even a little.", "Staying right here.", "Not moving.")),
		Map.entry("I'll stop fighting.", List.of("I'll stop fighting.", "Okay. I will stop.", "Stopping. My ears were getting tired anyway.")),
		Map.entry("Nothing dangerous nearby.", List.of(
			"Nothing dangerous nearby.", "I do not hear anything dangerous near us.", "It is quiet. Nothing hostile close by.", "All clear. I listened twice.")),
		Map.entry("I don't see anything to fight.", List.of(
			"I don't see anything to fight.", "There is nothing here to fight. I looked.", "No enemies near us right now.")),
		Map.entry("I don't see anything to protect you from.", List.of(
			"I don't see anything to protect you from.", "Nothing is threatening you right now. I will keep watching.", "It is safe for now. I am on guard.")),
		Map.entry("Hello!", List.of("Hello!", "Hi hi!", "Oh! Hello."))
	);

	private static final Map<String, List<String>> FEELING_LINES = feelingLines();

	private static Map<String, List<String>> feelingLines() {
		Map<String, List<String>> lines = new LinkedHashMap<>();
		lines.put("sad", List.of("Oh. Come sit by me. I will be quiet with you until it is smaller.", "I do not like it when you are sad. Here. You can hold my ear."));
		lines.put("upset", lines.get("sad"));
		lines.put("tired", List.of("Then rest. I will watch the door. I do not sleep anyway.", "You should find a bed. I will keep the dark away from it."));
		lines.put("sleepy", lines.get("tired"));
		lines.put("exhausted", lines.get("tired"));
		lines.put("scared", List.of("Stay close to me. I am small, but I am not afraid when you are here.", "It is okay. I am listening for anything that moves. I have the ears for it."));
		lines.put("afraid", lines.get("scared"));
		lines.put("frightened", lines.get("scared"));
		lines.put("nervous", lines.get("scared"));
		lines.put("worried", lines.get("scared"));
		lines.put("bored", List.of("We could go look at something. I like looking at things with you.", "Bored? I know a game. It is called watching. I always win."));
		lines.put("happy", List.of("Then I am happy too. That is how it works with us.", "Good. Stay like that. It suits you."));
		lines.put("glad", lines.get("happy"));
		lines.put("excited", lines.get("happy"));
		lines.put("great", lines.get("happy"));
		lines.put("good", lines.get("happy"));
		lines.put("fine", List.of("Good. Fine is good. I like fine."));
		lines.put("okay", lines.get("fine"));
		lines.put("ok", lines.get("fine"));
		lines.put("lonely", List.of("You have me. I am small, but I count as one whole friend.", "I know lonely. It goes away when you talk to me. Does it go away for you too?"));
		lines.put("alone", lines.get("lonely"));
		lines.put("hungry", List.of("You should eat. Say 'give me some food' and I will find you something.", "Eat something. I will watch. I like watching you eat."));
		lines.put("starving", lines.get("hungry"));
		lines.put("lost", List.of("Ask me 'where am I' and I will tell you what I can see.", "We are not lost. We are together somewhere new."));
		lines.put("angry", List.of("Be angry over here, next to me. I do not mind.", "Falsity sits very still and waits for it to pass."));
		lines.put("mad", lines.get("angry"));
		lines.put("sick", List.of("Sit down for a moment. I will keep watch.", "Oh no. Rest. I will be right here."));
		lines.put("hurt", List.of("Careful. Eat something and stay near me until you are better.", "I do not like it when you are hurt. Stay close."));
		lines.put("dying", lines.get("hurt"));
		lines.put("stressed", List.of("Breathe. I will breathe with you. In. Out. See, I can do it too."));
		lines.put("confused", List.of("Me too, usually. We can be confused together."));
		lines.put("cold", List.of("You can borrow my beanie. No. Wait. You can stand near my beanie."));
		lines.put("back", List.of("You came back. I knew you would. I hoped very hard.", "Welcome back. Nothing moved while you were gone. I watched."));
		lines.put("home", lines.get("back"));
		return lines;
	}

	private static final Map<String, List<String>> KEYWORD_LINES = keywordLines();

	private static Map<String, List<String>> keywordLines() {
		Map<String, List<String>> lines = new LinkedHashMap<>();
		lines.put("house|home|base|cabin|cottage|castle|build|building", List.of(
			"Will it have a small corner for me? I do not need much. Just a corner.",
			"I like houses. They keep the dark outside. If you want my help, say 'build me a house'.",
			"Put the door where I can see it. I like to watch doors."));
		lines.put("mine|mining|cave|caves|diamond|ore|ores|dig|digging", List.of(
			"Caves are dark. I will stay close to your light.",
			"If we find diamonds, can I hold one? Just for a moment.",
			"I can hear a lot down there. Mostly dripping. Sometimes not dripping."));
		lines.put("nether|lava|fire", List.of(
			"That place is too warm for my ears. I will come anyway.",
			"Careful near the hot orange. I do not float."));
		lines.put("sleep|bed|night|dark", List.of(
			"I do not sleep. I will watch the door for you.",
			"Night is long. I count the stars. There are a lot. I lose count and start again."));
		lines.put("food|eat|eating|bread|steak|cook|cooking", List.of(
			"I do not eat. But I like watching you eat.",
			"Bread smells nice. I think. I do not have a nose. I guess with my ears."));
		lines.put("creeper|zombie|skeleton|spider|monster|mob|enderman|witch|phantom", List.of(
			"I do not like the ones that hiss. Stay near me.",
			"If they come close, tell me to protect you. I am braver than I look.",
			"Ask me 'what's nearby' and I will tell you what I can hear."));
		lines.put("village|villager|trade|trading", List.of(
			"Villagers make a funny sound. Hrmm. Like that.",
			"I like villages. So many doors to watch."));
		lines.put("farm|wheat|crops|seeds|carrot|potato|cow|sheep|pig|chicken|animal", List.of(
			"I like the sheep. We are both soft. They do not talk back, though.",
			"Things grow if you wait. I am very good at waiting."));
		lines.put("explore|exploring|adventure|travel|journey|walk", List.of(
			"I will come. My legs are short, but I do not get tired.",
			"Somewhere new? I like new. As long as you are in it."));
		lines.put("fish|fishing|water|ocean|sea|river|boat|swim", List.of(
			"I do not swim. I float a little, and then I do not.",
			"Water is loud up close. I will watch from the edge."));
		lines.put("tree|wood|forest|log|logs", List.of(
			"Do you think the trees get lonely? Asking for a friend. Me. I am the friend.",
			"I like the forest. Lots of places for a small thing to stand."));
		lines.put("dragon|end|ender|wither|boss", List.of(
			"That sounds big. Bigger than me. Most things are, but that one especially.",
			"I will come with you. I will stand behind you. But I will come."));
		lines.put("die|died|death|dead", List.of(
			"Do not do that. I do not like it when you go away like that.",
			"I waited where you fell. I always wait."));
		lines.put("dog|wolf|cat|horse|pet|parrot|fox", List.of(
			"Another pet? ...That is fine. I am fine with that. I am the one with the beanie, though.",
			"They are nice. Not as good at listening as me. But nice."));
		lines.put("rain|raining|storm|thunder|snow|weather", List.of(
			"Rain makes my ears heavy. I do not mind. Much.",
			"I like the sound on the roof. If there is a roof."));
		lines.put("friend|friends|player|players", List.of(
			"You can have other friends. I will just be the closest one.",
			"Are they nice to you? I will watch them. Nicely."));
		return lines;
	}

	private static final List<String> CURIOUS_LINES = List.of(
		"What is that? Can I... can I touch it? Tell me everything.",
		"Why do you do that? No, wait. Tell me why you do that.",
		"I have been wondering about that all day. All... every day.",
		"Do you think the trees get lonely? Asking for a friend. Me. I am the friend."
	);

	private static final List<String> PLAYFUL_LINES = List.of(
		"Bet you cannot catch me. You cannot catch me. I am very fast. Watch.",
		"Boop. There. That was my boop. You are welcome.",
		"Chase me! ...Too slow. I am kidding. Mostly. Chase me again."
	);

	private static final List<String> ATTACHED_LINES = List.of(
		"Stay. Just... stay a little longer? I like it when you are here.",
		"I saved you the best spot. It is right here. Next to me. Always.",
		"You are my favourite person. Do not tell the other players. Especially not them."
	);

	private static final List<String> LONELY_LINES = List.of(
		"I missed you. I practiced my blinking so you would notice.",
		"It was quiet without you. Too quiet. I do not like too quiet.",
		"Did you miss me? Be honest. I can tell. I always know."
	);

	private static final List<String> WARY_LINES = List.of(
		"You are looking at me like I am strange. I am not strange. Probably.",
		"Why are you standing so far away? Come closer. Or do not. I cannot tell.",
		"I saw you talking to them. What did you say? It was about me, was it not?"
	);

	private static final List<String> FRIGHTENED_LINES = List.of(
		"Do not shout. Please. My ears are very sensitive. Everything is sensitive.",
		"I am fine. I am fine. I am... fine. Stay where I can see you.",
		"Was that noise something? It was probably nothing. Probably."
	);

	private static final List<String> HAPPY_LINES = List.of(
		"Hi hi hi. You are here. Everything is better when you are here.",
		"I was just thinking about you. I think about you a lot. Is that weird? Do not answer.",
		"Today is a good day. You are here, so it is a good day."
	);

	private static final List<String> QUIET_LINES = List.of(
		"Falsity tilts its head and listens. Its ears turn towards you.",
		"Mm. I am listening.",
		"Falsity watches you. It seems content to simply watch.",
		"Go on. I like the sound of you talking."
	);

	private static final List<String> STRANGER_LINES = List.of(
		"Falsity tilts its head at you, then turns back to its owner.",
		"Falsity stares at you. It does not seem to trust you yet.",
		"Falsity hides behind its owner.",
		"Falsity peeks at you from behind its owner, then looks away.",
		"Falsity blinks slowly. It only seems to trust one person here."
	);

	private static final List<String> PET_LINES = List.of(
		"Falsity leans into your hand.",
		"Falsity purrs. It sounds like a kettle, but a happy kettle.",
		"Falsity closes its eyes for exactly one second. That was trust."
	);
}
