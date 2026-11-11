package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.OfflineFallback.Topic;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Without Groq, Falsity must still answer what was said and must not repeat
 * itself. These are the two things players noticed when it did neither.
 */
class OfflineFallbackConversationTest {
	private static String reply(OfflineFallback fallback, String message, List<String> recent) {
		return fallback.respond(
			new PlayerMemory("uuid", "Alex", 0L), new PersonalityProfile(), HorrorStage.INNOCENCE, message, true, recent);
	}

	@Test
	void recognisesWhatKindOfThingWasSaid() {
		Map<String, Topic> expected = new LinkedHashMap<>();
		expected.put("hello", Topic.GREETING);
		expected.put("Hey there!", Topic.GREETING);
		expected.put("good morning", Topic.GREETING);
		expected.put("bye", Topic.FAREWELL);
		expected.put("ok good night", Topic.FAREWELL);
		expected.put("thanks!", Topic.THANKS);
		expected.put("thank you so much", Topic.THANKS);
		expected.put("sorry about that", Topic.APOLOGY);
		expected.put("lol", Topic.LAUGH);
		expected.put("hahaha", Topic.LAUGH);
		expected.put("I love you", Topic.AFFECTION);
		expected.put("can I have a hug?", Topic.AFFECTION);
		expected.put("good job", Topic.COMPLIMENT);
		expected.put("you're so cute", Topic.COMPLIMENT);
		expected.put("you're stupid", Topic.INSULT);
		expected.put("how are you?", Topic.HOW_ARE_YOU);
		expected.put("what's up", Topic.HOW_ARE_YOU);
		expected.put("what can you do?", Topic.CAPABILITIES);
		expected.put("who are you?", Topic.IDENTITY);
		expected.put("what's your favourite colour?", Topic.FAVOURITE);
		expected.put("do you like sheep?", Topic.OPINION);
		expected.put("I'm so tired", Topic.PLAYER_FEELING);
		expected.put("I want to build a house", Topic.PLAN);
		expected.put("let's go mining", Topic.PLAN);
		expected.put("why is the sky blue?", Topic.QUESTION);
		expected.put("where did the cat go", Topic.QUESTION);
		expected.put("ok", Topic.ACKNOWLEDGEMENT);
		expected.put("the sunset looks nice", Topic.STATEMENT);
		expected.put("", Topic.STATEMENT);

		for (Map.Entry<String, Topic> entry : expected.entrySet()) {
			assertEquals(entry.getValue(), OfflineFallback.classify(entry.getKey()), "'" + entry.getKey() + "'");
		}
		assertEquals(Topic.STATEMENT, OfflineFallback.classify(null));
	}

	@Test
	void aPlanIsHeardAsAPlan() {
		assertEquals("build a house", OfflineFallback.planOf("I want to build a house"));
		assertEquals("find your dog", OfflineFallback.planOf("I need to find my dog"));
		assertEquals("go mining", OfflineFallback.planOf("Let's go mining!"));
		assertEquals("", OfflineFallback.planOf("hello"));
		assertEquals("", OfflineFallback.planOf(null));
	}

	@Test
	void talkingAboutBuildingAHouseIsAnsweredAboutTheHouse() {
		OfflineFallback fallback = new OfflineFallback(new Random(3));
		List<String> recent = new ArrayList<>();

		for (int i = 0; i < 12; i++) {
			String line = reply(fallback, "I want to build a house", recent);
			String lower = line.toLowerCase(Locale.ROOT);
			assertTrue(
				lower.contains("build a house") || lower.contains("house") || lower.contains("corner") || lower.contains("door"),
				"the reply should be about the plan, was: " + line);
			recent.add(0, line);
		}
	}

	@Test
	void feelingsGetAnAnswerThatFits() {
		OfflineFallback fallback = new OfflineFallback(new Random(5));

		assertTrue(reply(fallback, "I'm hungry", List.of()).toLowerCase(Locale.ROOT).contains("eat"));
		String lost = reply(fallback, "I am lost", List.of());
		assertTrue(lost.contains("where am I") || lost.contains("lost"), lost);
		String tired = reply(fallback, "i'm really tired", List.of());
		assertTrue(tired.contains("rest") || tired.contains("bed"), tired);
	}

	@Test
	void theSameMessageNeverGetsTheSameLineTwiceInARow() {
		String[] messages = {
			"hello", "how are you?", "thanks", "I love you", "can I have a hug", "I want to build a house", "why?",
			"ok", "the sunset looks nice", "bye", "lol", "good job", "who are you?", "do you like sheep?", "I'm sad",
			"what can you do?", "asdf qwerty"};

		for (long seed = 1; seed <= 5; seed++) {
			for (String message : messages) {
				OfflineFallback fallback = new OfflineFallback(new Random(seed));
				List<String> recent = new ArrayList<>();
				String previous = null;
				for (int i = 0; i < 40; i++) {
					String line = reply(fallback, message, recent);
					assertFalse(line.isBlank());
					assertNotEquals(previous, line, "'" + message + "' was answered with the same line twice in a row (seed " + seed + ")");
					previous = line;
					recent.add(0, line);
					if (recent.size() > 6) {
						recent.remove(recent.size() - 1);
					}
				}
			}
		}
	}

	@Test
	void itAlsoAvoidsRepeatsWhenTheCallerKeepsNoHistory() {
		OfflineFallback fallback = new OfflineFallback(new Random(11));
		PersonalityProfile personality = new PersonalityProfile();

		String previous = null;
		for (int i = 0; i < 40; i++) {
			String line = fallback.respond(null, personality, HorrorStage.INNOCENCE, "hello", true);
			assertNotEquals(previous, line);
			previous = line;
		}
	}

	@Test
	void differentMessagesGetDifferentReplies() {
		OfflineFallback fallback = new OfflineFallback(new Random(8));
		List<String> recent = new ArrayList<>();
		Set<String> replies = new HashSet<>();
		String[] messages = {
			"hello", "how are you?", "I want to build a house", "thanks", "do you like sheep?", "I'm hungry",
			"what can you do?", "who are you?", "bye", "I found diamonds today"};

		for (String message : messages) {
			String line = reply(fallback, message, recent);
			replies.add(line);
			recent.add(0, line);
		}

		assertEquals(messages.length, replies.size(), "ten different messages, ten different replies: " + replies);
	}

	@Test
	void anUnknownItemIsNamedAndAskedAbout() {
		OfflineFallback fallback = new OfflineFallback(new Random(2));

		String first = fallback.unknownItem("flibber", List.of());
		String second = fallback.unknownItem("flibber", List.of(first));

		assertTrue(first.toLowerCase(Locale.ROOT).contains("flibber"), first);
		assertTrue(second.toLowerCase(Locale.ROOT).contains("flibber"), second);
		assertNotEquals(first, second);
		assertFalse(fallback.unknownItem("", List.of()).isBlank());
		assertFalse(fallback.unknownItem(null, null).isBlank());
	}

	@Test
	void simpleOrdersAreAcknowledgedInMoreThanOneWay() {
		OfflineFallback fallback = new OfflineFallback(new Random(4));
		List<String> recent = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String previous = null;

		for (int i = 0; i < 12; i++) {
			String line = fallback.acknowledge("I'll follow you.", recent);
			assertNotEquals(previous, line, "'follow me' ten times should not sound like a machine");
			previous = line;
			seen.add(line);
			recent.add(0, line);
		}

		assertTrue(seen.size() >= 3, "expected some variety, got " + seen);
		assertTrue(seen.contains("I'll follow you."), "the plain acknowledgement is one of the variants");
	}

	@Test
	void resultsThatCarryFactsAreNeverReworded() {
		OfflineFallback fallback = new OfflineFallback(new Random(4));
		String given = "Here you go: 1 crafting table and 64 sticks.";
		String threats = "There are 2 hostile mobs nearby: zombie, creeper.";

		for (int i = 0; i < 5; i++) {
			assertEquals(given, fallback.acknowledge(given, List.of()));
			assertEquals(threats, fallback.acknowledge(threats, List.of()));
		}
		assertNull(fallback.acknowledge(null, List.of()));
	}

	@Test
	void neverThrowsWhateverItIsGiven() {
		OfflineFallback fallback = new OfflineFallback(new Random(9));

		assertFalse(fallback.respond(null, null, null, null, true, null).isBlank());
		assertFalse(fallback.respond(null, new PersonalityProfile(), HorrorStage.INNOCENCE, "   ", true, List.of()).isBlank());
		assertFalse(fallback.respond(null, new PersonalityProfile(), HorrorStage.INNOCENCE, "?!?!", true, List.of()).isBlank());
		assertFalse(fallback.respond(null, new PersonalityProfile(), HorrorStage.INNOCENCE, "x".repeat(2000), true, List.of()).isBlank());
	}
}
