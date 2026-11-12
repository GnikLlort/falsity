package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.world.WorldQuery.Kind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which messages are questions about the world, and which are only talk that mentions it. */
class WorldQueryRecognizerTest {
	private static String asked(String message) {
		return WorldQueryRecognizer.recognize(message).map(WorldQuery::toActionQuery).orElse("");
	}

	private static void assertAll(Map<String, String> expected) {
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			assertEquals(entry.getValue(), asked(entry.getKey()), "\"" + entry.getKey() + "\"");
		}
	}

	private static void assertNone(List<String> messages) {
		for (String message : messages) {
			assertEquals("", asked(message), "\"" + message + "\" is not a question about the world");
		}
	}

	@Test
	void questionsAboutHereAndNow() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("Where am I?", "position");
		expected.put("where are we", "position");
		expected.put("what are my coordinates", "position");
		expected.put("tell me the coordinates", "position");
		expected.put("coords", "position");
		expected.put("What's your position?", "position");
		expected.put("what biome is this", "biome");
		expected.put("which biome are we in?", "biome");
		expected.put("what dimension are we in", "dimension");
		expected.put("are we in the nether?", "dimension");
		expected.put("what time is it", "time");
		expected.put("is it night", "time");
		expected.put("is it dark", "time");
		expected.put("how long until morning", "time");
		expected.put("can we sleep?", "time");
		expected.put("is it raining", "weather");
		expected.put("how's the weather", "weather");
		expected.put("is it going to rain", "weather");
		expected.put("what am I standing on", "block below");
		expected.put("what's under me?", "block below");
		expected.put("where is spawn", "spawn");
		expected.put("how far is spawn", "spawn");
		assertAll(expected);
	}

	@Test
	void structureSearches() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("Where is the nearest village?", "structure:village");
		expected.put("is there a village nearby", "structure:village");
		expected.put("any villages", "structure:village");
		expected.put("find a stronghold", "structure:stronghold");
		expected.put("how far is the nearest desert temple", "structure:desert_pyramid");
		expected.put("is there a nether fortress around", "structure:fortress");
		expected.put("take me to a village", "structure:village");
		expected.put("which way to the village", "structure:village");
		expected.put("I'm looking for a village", "structure:village");
		expected.put("where's the end portal", "structure:stronghold");
		expected.put("locate a mansion", "structure:mansion");
		expected.put("nearest village", "structure:village");
		expected.put("are we near a village", "structure:village");
		expected.put("do you know where a village is", "structure:village");
		assertAll(expected);
	}

	@Test
	void oreAndCaveSearches() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("any diamonds nearby?", "ore:diamond");
		expected.put("where can I find iron", "ore:iron");
		expected.put("where are the diamonds", "ore:diamond");
		expected.put("can you see any diamonds", "ore:diamond");
		expected.put("are there diamonds under us", "ore:diamond");
		expected.put("any iron ore nearby", "ore:iron");
		expected.put("is there any netherite around here", "ore:ancient_debris");
		expected.put("scan for ores", "ores");
		expected.put("what ores are nearby", "ores");
		expected.put("any ore here?", "ores");
		expected.put("are there any caves around here", "cave");
		expected.put("is there a cave near here", "cave");
		expected.put("find a cave", "cave");
		expected.put("do you see a cave", "cave");
		expected.put("what do you see", "surroundings");
		expected.put("look around", "surroundings");
		expected.put("describe the area", "surroundings");
		assertAll(expected);
	}

	@Test
	void talkThatOnlyMentionsAPlaceIsNotASearch() {
		assertNone(List.of(
			"I love villages",
			"I saw a village once",
			"we found diamonds yesterday",
			"that village was nice",
			"villagers are weird",
			"what is a village",
			"do you know what a village is",
			"tell me about villages",
			"this cave is huge",
			"is this a cave",
			"nice cave",
			"I like diamonds",
			"do you have any diamonds",
			"village",
			"diamonds!",
			"how are you today",
			""
		));
	}

	@Test
	void statementsAreNotSearches() {
		assertNone(List.of(
			"there are diamonds here",
			"here are some diamonds for you",
			"I have diamonds here",
			"the village is nearby",
			"we are near a village",
			"there's a village nearby",
			"I know of a village",
			"I know where the village is",
			"this is where I found iron",
			"this cave has diamonds",
			"I bet there are diamonds down here",
			"nice cave here"
		));
	}

	@Test
	void aQuestionMarkTurnsAStatementIntoAQuestion() {
		assertEquals("", asked("there's a village nearby"));
		assertEquals("structure:village", asked("there's a village nearby?"));
		assertEquals("ore:diamond", asked("diamonds around here?"));
		assertEquals("cave", asked("caves around here?"));
	}

	@Test
	void theQuestionFlagStandsInForAQuestionMarkThatWasAlreadyRemoved() {
		assertTrue(WorldQueryRecognizer.recognizeSearch("the village is nearby", false).isEmpty());
		assertEquals("structure:village",
			WorldQueryRecognizer.recognizeSearch("the village is nearby", true).map(WorldQuery::toActionQuery).orElse(""));
		assertEquals("structure:village",
			WorldQueryRecognizer.recognizeSearch("is the village nearby", false).map(WorldQuery::toActionQuery).orElse(""),
			"a sentence that starts like a question is one even without the mark");
	}

	@Test
	void aBareSubjectAndPlaceIsAQuestion() {
		assertEquals("ore:diamond", asked("diamonds nearby"));
		assertEquals("structure:village", asked("a village near here"));
		assertEquals("cave", asked("cave nearby"));
		assertEquals("ores", asked("ores nearby"));
	}

	@Test
	void somethingThatBelongsToSomeoneIsNotSearchedFor() {
		assertNone(List.of(
			"where is my iron pickaxe",
			"I'm looking for my iron sword",
			"where did I put my diamonds",
			"where is my secret diamond stash",
			"where is our village",
			"is your village nearby",
			"where is my house",
			"where is steve"
		));
	}

	@Test
	void aMadeThingIsNotAnOreToSearchFor() {
		assertNone(List.of(
			"where is the iron door",
			"is there an iron golem nearby",
			"where is the nearest redstone torch",
			"find a diamond sword"
		));
		assertEquals("ore:iron", asked("my iron pickaxe broke, where can I find iron"));
	}

	@Test
	void aCaveThatOnlySaysWhereToLookIsNotTheSubject() {
		assertEquals("ore:gold", asked("is there gold in this cave"));
		assertEquals("", asked("what's in this cave"));
	}

	@Test
	void aStoryFollowedByAQuestionIsStillAQuestion() {
		assertEquals("structure:village", asked("I just got here, where's the nearest village?"));
		assertEquals("structure:village", asked("I found a cave, is there a village nearby"));
		assertEquals("ore:iron", asked("I like diamonds but where can I find iron"));
		assertEquals("cave", asked("we went mining so are there any caves nearby"));
		assertEquals("", asked("I found diamonds, any more nearby?"), "the question names nothing to look for");
	}

	@Test
	void simpleQuestionsAreRecognisedOnTheirOwn() {
		assertEquals(Kind.POSITION, WorldQueryRecognizer.recognizeSimple("where am i").orElseThrow().kind());
		assertEquals(Kind.TIME, WorldQueryRecognizer.recognizeSimple("I was wondering what time it is").orElseThrow().kind());
		assertTrue(WorldQueryRecognizer.recognizeSimple("where is the nearest village").isEmpty(), "a search is not a simple question");
		assertTrue(WorldQueryRecognizer.recognizeSimple("").isEmpty());
		assertTrue(WorldQueryRecognizer.recognizeSimple(null).isEmpty());
		assertTrue(WorldQueryRecognizer.recognizeSearch(null).isEmpty());
		assertTrue(WorldQueryRecognizer.recognize(null).isEmpty());
	}

	@Test
	void everyQuestionSurvivesTheTripThroughAnAction() {
		for (Kind kind : Kind.values()) {
			if (kind == Kind.STRUCTURE || kind == Kind.ORE) {
				continue;
			}
			WorldQuery read = WorldQueryRecognizer.fromActionQuery(WorldQuery.of(kind).toActionQuery()).orElseThrow();
			assertEquals(kind, read.kind(), WorldQuery.of(kind).toActionQuery());
		}
		for (StructureCatalog.Entry entry : StructureCatalog.all()) {
			String text = new WorldQuery(Kind.STRUCTURE, entry.key(), "").toActionQuery();
			WorldQuery read = WorldQueryRecognizer.fromActionQuery(text).orElseThrow();
			assertEquals(Kind.STRUCTURE, read.kind(), text);
			assertEquals(entry.key(), read.subject(), text);
		}
		for (OreCatalog.Entry entry : OreCatalog.all()) {
			String text = new WorldQuery(Kind.ORE, entry.key(), "").toActionQuery();
			WorldQuery read = WorldQueryRecognizer.fromActionQuery(text).orElseThrow();
			assertEquals(Kind.ORE, read.kind(), text);
			assertEquals(entry.key(), read.subject(), text);
		}
		WorldQuery anyOre = WorldQueryRecognizer.fromActionQuery(new WorldQuery(Kind.ORE, "", "").toActionQuery()).orElseThrow();
		assertEquals(Kind.ORE, anyOre.kind());
		assertEquals("", anyOre.subject());
	}

	@Test
	void aModelsOwnWordingIsUnderstoodToo() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("nearest village", "structure:village");
		expected.put("village", "structure:village");
		expected.put("Ocean Monument", "structure:monument");
		expected.put("diamonds nearby", "ore:diamond");
		expected.put("diamond", "ore:diamond");
		expected.put("nearest cave", "cave");
		expected.put("where am i", "position");
		expected.put("coordinates", "position");
		expected.put("what biome", "biome");
		expected.put("time", "time");
		expected.put("rain", "weather");
		expected.put("world spawn", "spawn");
		expected.put("around", "surroundings");
		expected.put("ground", "block below");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			Optional<WorldQuery> read = WorldQueryRecognizer.fromActionQuery(entry.getKey());
			assertEquals(entry.getValue(), read.map(WorldQuery::toActionQuery).orElse(""), "\"" + entry.getKey() + "\"");
		}
	}

	@Test
	void aQueryThatNamesNothingKnownIsRefused() {
		assertTrue(WorldQueryRecognizer.fromActionQuery("").isEmpty());
		assertTrue(WorldQueryRecognizer.fromActionQuery(null).isEmpty());
		assertTrue(WorldQueryRecognizer.fromActionQuery("structure:castle").isEmpty());
		assertTrue(WorldQueryRecognizer.fromActionQuery("ore:unobtainium").isEmpty());
		assertTrue(WorldQueryRecognizer.fromActionQuery("the meaning of life").isEmpty());
		assertTrue(WorldQueryRecognizer.fromActionQuery("my iron pickaxe").isEmpty());
	}

	@Test
	void knowsWhenAPlayerAsksWhereSomethingIs() {
		assertTrue(WorldQueryRecognizer.asksForLocation("where is my house"));
		assertTrue(WorldQueryRecognizer.asksForLocation("can you find my dog"));
		assertTrue(WorldQueryRecognizer.asksForLocation("what are the coords of the base"));
		assertFalse(WorldQueryRecognizer.asksForLocation("how are you"));
		assertFalse(WorldQueryRecognizer.asksForLocation(""));
		assertFalse(WorldQueryRecognizer.asksForLocation(null));
	}

	@Test
	void knowsAPlaceFromAThing() {
		assertTrue(WorldQueryRecognizer.namesPlace("a village"));
		assertTrue(WorldQueryRecognizer.namesPlace("the nearest stronghold"));
		assertTrue(WorldQueryRecognizer.namesPlace("a cave"));
		assertFalse(WorldQueryRecognizer.namesPlace("some diamonds"));
		assertFalse(WorldQueryRecognizer.namesPlace("an elytra"));
		assertFalse(WorldQueryRecognizer.namesPlace(""));
		assertFalse(WorldQueryRecognizer.namesPlace(null));
	}

	@Test
	void theSpokenTextIsKeptForTheReply() {
		WorldQuery query = WorldQueryRecognizer.recognize("Where is the NEAREST village?").orElseThrow();
		assertEquals(Kind.STRUCTURE, query.kind());
		assertEquals("village", query.subject());
		assertEquals("where is the nearest village", query.spoken());
	}
}
