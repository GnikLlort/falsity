package com.falsity.ai;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import java.util.List;
import java.util.Random;

/**
 * Scripted offline personality: what Falsity says when Groq is unavailable.
 *
 * <p>This is the guarantee that Falsity never goes silent: no key, no
 * network, rate limit, timeout or bad response all land here. Lines are
 * personality-aware (curious Falsity asks questions, attached Falsity leans
 * in) and horror-aware (only at Strange or beyond may a line hint that
 * Falsity remembers too much). Every method returns a non-empty line and
 * never throws.
 */
public final class OfflineFallback {
	private final Random random;

	public OfflineFallback() {
		this(new Random());
	}

	/** Visible for tests: injects the random source. */
	public OfflineFallback(Random random) {
		this.random = random;
	}

	/** A reply to a talked message. */
	public String respond(PlayerMemory memory, PersonalityProfile personality, HorrorStage stage, String playerMessage, boolean isOwner) {
		try {
			if (!isOwner) {
				return pick(STRANGER_LINES);
			}

			EmotionalState mood = EmotionalState.from(personality);
			if (stage != null && stage.ordinal() >= HorrorStage.STRANGE.ordinal() && memory != null && !memory.facts.isEmpty() && this.random.nextInt(4) == 0) {
				String fact = memory.facts.get(this.random.nextInt(memory.facts.size()));
				return "I still remember... " + lowerFirst(fact) + ".";
			}

			return switch (mood) {
				case CURIOUS -> pick(CURIOUS_LINES);
				case PLAYFUL -> pick(PLAYFUL_LINES);
				case ATTACHED -> pick(ATTACHED_LINES);
				case LONELY -> pick(LONELY_LINES);
				case WARY -> pick(WARY_LINES);
				case FRIGHTENED -> pick(FRIGHTENED_LINES);
				case HAPPY -> pick(HAPPY_LINES);
				case QUIET -> pick(QUIET_LINES);
			};
		} catch (RuntimeException exception) {
			return "Falsity blinks at you. It does not look away.";
		}
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

	private String pick(List<String> lines) {
		return lines.get(this.random.nextInt(lines.size()));
	}

	private static String lowerFirst(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	private static final List<String> FIRST_LINES = List.of(
		"Falsity blinks up at you. It does not look away.",
		"Oh. You can see me. Good. I was hoping someone would.",
		"Hello. I am Falsity. I think. You are the first to ask."
	);

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
		"Falsity tilts its head at you, then turns back to its owner.",
		"...",
		"Falsity watches you. It seems content to simply watch."
	);

	private static final List<String> STRANGER_LINES = List.of(
		"Falsity tilts its head at you, then turns back to its owner.",
		"Falsity stares at you. It does not seem to trust you yet.",
		"Falsity hides behind its owner."
	);

	private static final List<String> PET_LINES = List.of(
		"Falsity leans into your hand.",
		"Falsity purrs. It sounds like a kettle, but a happy kettle.",
		"Falsity closes its eyes for exactly one second. That was trust."
	);
}
