package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.items.ItemRequestParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The line between talking to Falsity and telling it to do something. A
 * message is an order only when it is phrased as one; everything else must
 * reach the conversation untouched.
 */
class IntentConversationTest {
	private static String typeOf(String message) {
		return IntentRecognizer.recognize(message).map(request -> request.type().name()).orElse("");
	}

	private static void assertConversation(List<String> messages) {
		for (String message : messages) {
			Optional<ActionRequest> request = IntentRecognizer.recognize(message);
			assertTrue(request.isEmpty(),
				"\"" + message + "\" is conversation, but was read as " + request.map(found -> found.type().name()).orElse(""));
		}
	}

	private static void assertTypes(Map<String, ActionType> expected) {
		for (Map.Entry<String, ActionType> entry : expected.entrySet()) {
			assertEquals(entry.getValue().name(), typeOf(entry.getKey()), "\"" + entry.getKey() + "\"");
		}
	}

	private static String query(String message) {
		ActionRequest request = IntentRecognizer.recognize(message).orElse(null);
		if (request == null || request.type() != ActionType.WORLD_QUERY) {
			return request == null ? "" : request.type().name();
		}
		return request.arg("query");
	}

	@Test
	void plansAndOpinionsAreNotOrders() {
		assertConversation(List.of(
			"I want to build a house",
			"I'm going to build a castle tomorrow",
			"let's build something",
			"why do you follow me?",
			"do you like to fight?",
			"can you fight?",
			"that was a good fight",
			"I hate creepers",
			"I was following a river",
			"do you sit a lot?",
			"you make me happy",
			"I need to sleep",
			"I need you",
			"I want you to be happy",
			"tell me a story",
			"what is your name",
			"the sunset looks nice",
			"can you talk?"
		));
	}

	@Test
	void anOrderStartsTheSentence() {
		Map<String, ActionType> expected = new LinkedHashMap<>();
		expected.put("follow me", ActionType.FOLLOW);
		expected.put("Falsity, follow me please", ActionType.FOLLOW);
		expected.put("can you follow me", ActionType.FOLLOW);
		expected.put("ok now follow me", ActionType.FOLLOW);
		expected.put("stay with me", ActionType.FOLLOW);
		expected.put("heel", ActionType.FOLLOW);
		expected.put("this way", ActionType.FOLLOW);
		expected.put("come here", ActionType.COME);
		expected.put("hey falsity can you come over here", ActionType.COME);
		expected.put("please come back", ActionType.COME);
		expected.put("come", ActionType.COME);
		expected.put("sit", ActionType.SIT);
		expected.put("go sit down", ActionType.SIT);
		expected.put("you should sit down", ActionType.SIT);
		expected.put("stand up", ActionType.STAND);
		expected.put("stay", ActionType.STAY);
		expected.put("stay here", ActionType.STOP_FOLLOW);
		expected.put("wait here", ActionType.STOP_FOLLOW);
		expected.put("stop", ActionType.STOP_FOLLOW);
		expected.put("stop following me", ActionType.STOP_FOLLOW);
		expected.put("stop fighting", ActionType.STOP_FIGHT);
		expected.put("cancel the build", ActionType.STOP_BUILD);
		expected.put("protect me", ActionType.PROTECT);
		expected.put("I need you to protect me", ActionType.PROTECT);
		expected.put("attack that zombie", ActionType.FIGHT);
		expected.put("kill it", ActionType.FIGHT);
		assertTypes(expected);
	}

	@Test
	void anOrderInALaterSentenceIsStillAnOrder() {
		assertEquals("FOLLOW", typeOf("That was close. Follow me!"));
		assertEquals("structure:village", query("I love this place. Where is the nearest village?"));
		assertEquals("", typeOf("That was close. I hate creepers."));
	}

	@Test
	void thingsNobodyCanHandOverAreConversation() {
		assertConversation(List.of(
			"I need a hug",
			"can I have a hug?",
			"give me a moment",
			"give me a second",
			"give me a second to think",
			"give me a break",
			"I need a break from mining",
			"give me a hand",
			"give me a hand with this",
			"I need some time",
			"I need a minute",
			"can I have a word",
			"I need a friend",
			"give me a kiss",
			"give me some space",
			"I want a hug and a kiss",
			"I need love and attention",
			"I need some air",
			"I want the truth",
			"I want revenge",
			"I need a plan",
			"give me strength",
			"give me a reason",
			"give me five",
			"I want it all",
			"I want that",
			"give me everything",
			"give me all your diamonds",
			"give me your sword",
			"get me out of here",
			"bring me home",
			"I want to go home",
			"I could use some help",
			"find me"
		));
	}

	@Test
	void realThingsAreStillItemRequests() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("I need a bed", "bed");
		expected.put("give me a clock", "clock");
		expected.put("I need a name tag", "name tag");
		expected.put("can I have some bread", "bread");
		expected.put("I need water", "water");
		expected.put("I need a map", "map");
		expected.put("give me a sign", "sign");
		expected.put("hand me a bucket of water", "bucket of water");
		expected.put("give me more torches", "torches");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			ActionRequest request = IntentRecognizer.recognize(entry.getKey()).orElseThrow();
			assertEquals(ActionType.GIVE_ITEM, request.type(), entry.getKey());
			assertEquals(entry.getValue(), request.arg("item"), entry.getKey());
		}
	}

	@Test
	void anUnknownNameIsPassedOnRatherThanGuessed() {
		ActionRequest request = IntentRecognizer.recognize("give me a flibber").orElseThrow();

		assertEquals(ActionType.GIVE_ITEM, request.type());
		assertEquals("flibber", request.arg("item"), "what the name means is decided against the real item list, not here");
	}

	@Test
	void countsAreReadWithTheItem() {
		ActionRequest torches = IntentRecognizer.recognize("give me 64 torches").orElseThrow();
		assertEquals("torches", torches.arg("item"));
		assertEquals(64, torches.argInt("count", 1));

		ActionRequest suffix = IntentRecognizer.recognize("give me torches x32").orElseThrow();
		assertEquals("torches", suffix.arg("item"));
		assertEquals(32, suffix.argInt("count", 1));

		ActionRequest huge = IntentRecognizer.recognize("give me 100000 diamonds").orElseThrow();
		assertEquals(100000, huge.argInt("count", 1), "the limits refuse this later; the wish itself is understood");

		assertTrue(IntentRecognizer.recognize("give me 99999999999 diamonds").isEmpty(), "a count beyond any stack is not a request");
	}

	@Test
	void severalItemsBecomeOneRequest() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("give me a crafting table and 64 sticks", "1 crafting table, 64 sticks");
		expected.put("I need a diamond pickaxe, 3 iron ingots and a stack of torches", "1 diamond pickaxe, 3 iron ingots, 1 stack of torches");
		expected.put("gimme 2 stacks of cobblestone", "2 stacks of cobblestone");
		expected.put("give me 16 ender pearls and flint and steel", "16 ender pearls, 1 flint and steel");
		expected.put("I'd like half a stack of arrows", "half a stack of arrows");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			ActionRequest request = IntentRecognizer.recognize(entry.getKey()).orElseThrow();
			assertEquals(ActionType.GIVE_ITEMS, request.type(), entry.getKey());
			assertEquals(entry.getValue(), request.arg("items"), entry.getKey());
		}
	}

	@Test
	void theItemListReadsBackExactly() {
		ActionRequest request = IntentRecognizer.recognize("give me a crafting table and 64 sticks").orElseThrow();

		List<ItemRequestParser.Entry> entries = ItemRequestParser.parse(request.arg("items"));

		assertEquals(2, entries.size());
		assertEquals("crafting table", entries.get(0).name());
		assertEquals(1, entries.get(0).count());
		assertEquals("sticks", entries.get(1).name());
		assertEquals(64, entries.get(1).count());
	}

	@Test
	void makeMeNeedsSomethingToMake() {
		assertConversation(List.of("make me happy", "make me laugh", "make me proud", "make me yours", "make me an offer", "make me a promise"));

		ActionRequest sandwich = IntentRecognizer.recognize("make me a sandwich").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, sandwich.type());
		assertEquals("sandwich", sandwich.arg("item"));

		ActionRequest torches = IntentRecognizer.recognize("make me 3 torches").orElseThrow();
		assertEquals("torches", torches.arg("item"));
		assertEquals(3, torches.argInt("count", 1));

		assertEquals("BUILD", typeOf("make me a house"), "a house is built, not handed over");
	}

	@Test
	void findingAPlaceIsASearchAndFindingAThingIsAGift() {
		assertEquals("structure:village", query("find me a village"));
		assertEquals("structure:stronghold", query("find me the nearest stronghold"));
		assertEquals("cave", query("find me a cave"));

		ActionRequest diamonds = IntentRecognizer.recognize("find me some diamonds").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, diamonds.type());
		assertEquals("diamonds", diamonds.arg("item"));

		ActionRequest elytra = IntentRecognizer.recognize("find me an elytra").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, elytra.type());
		assertEquals("elytra", elytra.arg("item"));
	}

	@Test
	void questionsAboutTheWorldAreAnsweredByTheGame() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("where am I?", "position");
		expected.put("Falsity, what are my coordinates?", "position");
		expected.put("what biome is this", "biome");
		expected.put("what dimension are we in", "dimension");
		expected.put("what time is it", "time");
		expected.put("is it raining", "weather");
		expected.put("what am I standing on", "block below");
		expected.put("where is spawn", "spawn");
		expected.put("look around", "surroundings");
		expected.put("where is the nearest village?", "structure:village");
		expected.put("can you find a stronghold", "structure:stronghold");
		expected.put("is there a nether fortress around", "structure:fortress");
		expected.put("any diamonds nearby?", "ore:diamond");
		expected.put("where can I find iron", "ore:iron");
		expected.put("scan for ores", "ores");
		expected.put("are there any caves around here", "cave");
		expected.put("I need to find a village", "structure:village");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			assertEquals(entry.getValue(), query(entry.getKey()), "\"" + entry.getKey() + "\"");
		}
	}

	@Test
	void aRemarkAboutAPlaceIsNotAQuestionAboutIt() {
		assertConversation(List.of(
			"I love villages",
			"we found diamonds yesterday",
			"I saw a village once",
			"the village is nearby",
			"there are diamonds here",
			"here are some diamonds for you",
			"I have diamonds here",
			"where is my iron pickaxe",
			"I'm looking for my iron sword",
			"where is my house",
			"can you find my dog",
			"where is steve",
			"what do you think about caves?"
		));
		assertEquals("structure:village", query("the village is nearby?"), "the question mark makes it a question");
	}

	@Test
	void questionsAboutDangerAreThreatReports() {
		Map<String, ActionType> expected = new LinkedHashMap<>();
		expected.put("what's nearby", ActionType.REPORT_THREATS);
		expected.put("is it safe", ActionType.REPORT_THREATS);
		expected.put("any monsters around", ActionType.REPORT_THREATS);
		expected.put("are there creepers nearby", ActionType.REPORT_THREATS);
		expected.put("what was that noise", ActionType.REPORT_THREATS);
		assertTypes(expected);
	}

	@Test
	void buildOrdersNameWhatToBuild() {
		ActionRequest house = IntentRecognizer.recognize("build me a small wooden house").orElseThrow();
		assertEquals(ActionType.BUILD, house.type());
		assertEquals("house", house.arg("structure"));
		assertEquals("wooden", house.arg("material"));

		ActionRequest tower = IntentRecognizer.recognize("can you build a tower").orElseThrow();
		assertEquals("tower", tower.arg("structure"));
		assertFalse(tower.has("material"));
	}

	@Test
	void knowsAnItemRequestFromOtherOrders() {
		assertTrue(IntentRecognizer.isItemRequest("give me 3 torches"));
		assertTrue(IntentRecognizer.isItemRequest("I need a bed and a clock"));
		assertFalse(IntentRecognizer.isItemRequest("I need a hug"));
		assertFalse(IntentRecognizer.isItemRequest("follow me"));
		assertFalse(IntentRecognizer.isItemRequest("where am I"));
		assertFalse(IntentRecognizer.isItemRequest(null));
	}
}
