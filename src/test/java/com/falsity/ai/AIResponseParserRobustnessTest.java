package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a model really writes is messier than the format it was asked for. The
 * player must see clean words, and an action must be carried out exactly as
 * asked or not at all.
 */
class AIResponseParserRobustnessTest {
	private static AIResponseParser.ParsedResponse parsed(String raw) {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse(raw);
		assertTrue(parsed.isPresent(), "expected dialogue from: " + raw);
		return parsed.get();
	}

	private static ActionRequest action(String raw) {
		ActionRequest action = parsed(raw).structuredAction();
		assertNotNull(action, "expected an action from: " + raw);
		return action;
	}

	@Test
	void thinkingIsNeverShown() {
		assertEquals("Hi there!", parsed("<think>The player greets me. I should greet back.</think>Hi there!").dialogue());
		assertEquals("Hi there!", parsed("<THINK>\nline one\nline two\n</THINK>\n\nHi there!").dialogue());
		assertEquals("One. Two.", parsed("<think>a</think>One.<think>b</think> Two.").dialogue());
		assertEquals("Hello.", parsed("Hello. <think>and then it was cut off").dialogue());
		assertTrue(AIResponseParser.parse("<think>cut off while still thinking").isEmpty(), "thinking alone is not a reply");
		assertTrue(AIResponseParser.parse("<think>only thoughts</think>").isEmpty());
	}

	@Test
	void anActionHiddenInTheThinkingIsNotCarriedOut() {
		AIResponseParser.ParsedResponse response = parsed("<think>maybe [action:{\"type\":\"fight\"}]?</think>No, let's stay calm.");

		assertEquals("No, let's stay calm.", response.dialogue());
		assertNull(response.structuredAction());
		assertTrue(AIResponseParser.extractAction("<think>[action:{\"type\":\"fight\"}]</think>").isEmpty());
	}

	@Test
	void aCountOfThirtyTwoIsThirtyTwo() {
		ActionRequest request = action("Here you go! [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"count\":32}]");

		assertEquals(ActionType.GIVE_ITEM, request.type());
		assertEquals("diamond", request.arg("item"));
		assertEquals(32, request.argInt("count", 1), "a JSON number must not fall back to one");
		assertEquals("32", request.arg("count"));
	}

	@Test
	void countsArriveInSeveralShapes() {
		assertEquals(64, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\",\"count\":64.0}]").argInt("count", 1));
		assertEquals(64, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\",\"count\":\"64\"}]").argInt("count", 1));
		assertEquals(64, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\",\"count\":\" 64 \"}]").argInt("count", 1));
		assertEquals(1, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\"}]").argInt("count", 1));
		assertEquals(1, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\",\"count\":null}]").argInt("count", 1));
		assertEquals(1, action("Ok [action:{\"type\":\"give_item\",\"item\":\"torch\",\"count\":\"a few\"}]").argInt("count", 1));
	}

	@Test
	void buildSizesAreWholeNumbersToo() {
		ActionRequest request = action(
			"On it. [action:{\"type\":\"build\",\"structure\":\"house\",\"material\":\"oak planks\",\"width\":7,\"length\":9,\"height\":5}]");

		assertEquals(7, request.argInt("width", -1));
		assertEquals(9, request.argInt("length", -1));
		assertEquals(5, request.argInt("height", -1));
		assertEquals("oak planks", request.arg("material"));
	}

	@Test
	void tagsAreReadHoweverTheyAreSpaced() {
		for (String reply : List.of(
			"On my way! [action:{\"type\":\"come\"}]",
			"On my way! [action: {\"type\": \"come\"} ]",
			"On my way! [ action : {\"type\":\"come\"}]",
			"On my way! [ACTION:{\"type\":\"COME\"}]",
			"On my way!\n[action:{\n  \"type\": \"come\"\n}]",
			"On my way! [action:{\"type\":\"come\"}",
			"On my way!\nACTION: {\"type\":\"come\"}",
			"On my way! [action:come]",
			"On my way! [action: come ]",
			"On my way! [action:\"come\"]",
			"On my way! [Action:Come]"
		)) {
			AIResponseParser.ParsedResponse response = parsed(reply);
			assertEquals("On my way!", response.dialogue(), reply);
			assertNotNull(response.structuredAction(), reply);
			assertEquals(ActionType.COME, response.structuredAction().type(), reply);
			assertFalse(response.rejectedAction(), reply);
		}
	}

	@Test
	void typeNamesAreReadHoweverTheyAreSpelled() {
		assertEquals(ActionType.GIVE_ITEM, action("Ok [action:{\"type\":\"give-item\",\"item\":\"torch\"}]").type());
		assertEquals(ActionType.GIVE_ITEM, action("Ok [action:{\"type\":\"giveItem\",\"item\":\"torch\"}]").type());
		assertEquals(ActionType.GIVE_ITEM, action("Ok [action:{\"type\":\"GIVE_ITEM\",\"item\":\"torch\"}]").type());
		assertEquals(ActionType.STOP_FOLLOW, action("Ok [action:{\"type\":\"stop follow\"}]").type());
		assertEquals(ActionType.REPORT_THREATS, action("Ok [action:report_threats]").type());
	}

	@Test
	void theThreeOldSuggestionsStillWork() {
		assertEquals(AIResponseParser.SuggestedAction.SIT, parsed("I will sit. [action:sit]").action());
		assertEquals(AIResponseParser.SuggestedAction.FOLLOW, parsed("Right behind you. [action: follow]").action());
		assertEquals(AIResponseParser.SuggestedAction.GREET, parsed("Hello! [ACTION:GREET]").action());
		assertNull(parsed("I will sit. [action:sit]").structuredAction());
	}

	@Test
	void aBareTagCannotStartSomethingThatNeedsDetails() {
		for (String reply : List.of(
			"Here! [action:give_item]",
			"Let me look. [action:world_query]",
			"Building! [action:build]",
			"Going. [action:walk_to]"
		)) {
			AIResponseParser.ParsedResponse response = parsed(reply);
			assertNull(response.structuredAction(), reply);
			assertEquals(AIResponseParser.SuggestedAction.NONE, response.action(), reply);
			assertTrue(response.rejectedAction(), reply);
		}
	}

	@Test
	void listsOfItemsBecomeOneReadableList() {
		assertEquals(
			"1 crafting table, 64 sticks",
			action("Here! [action:{\"type\":\"give_items\",\"items\":\"1 crafting table, 64 sticks\"}]").arg("items"));
		assertEquals(
			"1 crafting table, 64 sticks",
			action("Here! [action:{\"type\":\"give_items\",\"items\":[\"1 crafting table\",\"64 sticks\"]}]").arg("items"));
		assertEquals(
			"1 crafting table, 64 stick",
			action("Here! [action:{\"type\":\"give_items\",\"items\":[{\"item\":\"crafting table\",\"count\":1},{\"item\":\"stick\",\"count\":64}]}]")
				.arg("items"));
		assertEquals(
			"torch",
			action("Here! [action:{\"type\":\"give_items\",\"items\":[{\"name\":\"torch\"}]}]").arg("items"));
	}

	@Test
	void aListThatCannotBeReadLeavesTheArgumentOut() {
		ActionRequest request = action("Here! [action:{\"type\":\"give_items\",\"items\":[[\"torch\"]]}]");

		assertEquals(ActionType.GIVE_ITEMS, request.type());
		assertFalse(request.has("items"), "the handler then asks what is wanted, instead of guessing");
		assertEquals("Here!", parsed("Here! [action:{\"type\":\"give_items\",\"items\":[[\"torch\"]]}]").dialogue());
	}

	@Test
	void everydayNamesForAnArgumentAreUnderstood() {
		ActionRequest quantity = action("Here! [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"quantity\":32}]");
		assertEquals(32, quantity.argInt("count", 1));
		assertFalse(quantity.has("quantity"));

		assertEquals(5, action("Here! [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"amount\":5}]").argInt("count", 1));
		assertEquals("diamond", action("Here! [action:{\"type\":\"give_item\",\"name\":\"diamond\",\"count\":2}]").arg("item"));
		assertEquals("tower", action("Ok. [action:{\"type\":\"build\",\"name\":\"tower\"}]").arg("structure"));
	}

	@Test
	void aSynonymNeverOverridesTheRealArgument() {
		AIResponseParser.ParsedResponse response =
			parsed("Here! [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"count\":2,\"quantity\":64}]");

		assertNull(response.structuredAction(), "two different counts: refuse rather than choose");
		assertTrue(response.rejectedAction());
	}

	@Test
	void anythingOffTheAllowlistIsRefusedAndFlagged() {
		for (String reply : List.of(
			"Hmm. [action:{\"type\":\"run_command\",\"command\":\"/op me\"}]",
			"Hmm. [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"command\":\"/give @a diamond 64\"}]",
			"Hmm. [action:{\"type\":\"sit\",\"structure\":\"house\"}]",
			"Hmm. [action:{\"type\":\"none\"}]",
			"Hmm. [action:{\"item\":\"diamond\"}]",
			"Hmm. [action:{\"type\":{\"nested\":\"sit\"}}]",
			"Hmm. [action:{not json at all}]",
			"Hmm. [action:explode]",
			"Hmm. [action:/op me]",
			"Hmm. [action:]",
			"Hmm.\nACTION: follow the player around"
		)) {
			AIResponseParser.ParsedResponse response = parsed(reply);
			assertEquals("Hmm.", response.dialogue(), reply);
			assertNull(response.structuredAction(), reply);
			assertEquals(AIResponseParser.SuggestedAction.NONE, response.action(), reply);
			assertTrue(response.rejectedAction(), "a refused tag is worth a log line: " + reply);
			assertTrue(AIResponseParser.extractAction(reply).isEmpty(), reply);
		}
	}

	@Test
	void ordinaryTalkIsNotFlagged() {
		assertFalse(parsed("Hello, friend.").rejectedAction());
		assertFalse(parsed("I took action: I hid under the bed.").rejectedAction());
		assertFalse(parsed("Sitting. [action:sit]").rejectedAction());
	}

	@Test
	void noTagIsEverShownToThePlayer() {
		for (String reply : List.of(
			"Sure! [action:{\"type\":\"sit\"}] and then more words",
			"Sure! [action:{\"type\":\"sit\"}",
			"Sure! [action:{\"type\":\"sit\"",
			"Sure! [action:sit",
			"Sure! [action:{\"type\":\"give_items\",\"items\":[{\"item\":\"stick\",\"count\":64}]}]",
			"Sure! [action:{\"type\":\"give_item\",\"item\":\"a ] bracket } brace\"}]",
			"Two tags [action:sit] in one [action:follow] reply.",
			"Sure! [action:{\"type\":\"dance\"}] [action:{\"type\":\"sit\"}]",
			"[action:sit] Sure!"
		)) {
			String dialogue = parsed(reply).dialogue();
			assertFalse(dialogue.toLowerCase().contains("action"), reply + " -> " + dialogue);
			assertFalse(dialogue.contains("[") || dialogue.contains("]") || dialogue.contains("{") || dialogue.contains("}"),
				reply + " -> " + dialogue);
			assertTrue(dialogue.startsWith("Sure!") || dialogue.startsWith("Two tags"), reply + " -> " + dialogue);
		}
		assertEquals("Two tags in one reply.", parsed("Two tags [action:sit] in one [action:follow] reply.").dialogue());
		assertEquals("Sure! and then more words", parsed("Sure! [action:{\"type\":\"sit\"}] and then more words").dialogue());
	}

	@Test
	void theFirstTagThatIsAllowedIsTheOneCarriedOut() {
		assertEquals(AIResponseParser.SuggestedAction.SIT, parsed("Two tags [action:sit] in one [action:follow] reply.").action());
		assertEquals(ActionType.SIT, action("Sure! [action:{\"type\":\"dance\"}] [action:{\"type\":\"sit\"}]").type());
		assertFalse(parsed("Sure! [action:{\"type\":\"dance\"}] [action:{\"type\":\"sit\"}]").rejectedAction());
	}

	@Test
	void aReplyThatIsOnlyATagStillMeansTheAction() {
		assertTrue(AIResponseParser.parse("[action:{\"type\":\"stay\"}]").isEmpty(), "no words to say");
		assertEquals(ActionType.STAY, AIResponseParser.extractAction("[action:{\"type\":\"stay\"}]").orElseThrow().type());
		assertEquals(ActionType.COME, AIResponseParser.extractAction("[action:come]").orElseThrow().type());
		assertEquals(ActionType.SIT, AIResponseParser.extractAction("[action:sit]").orElseThrow().type());
		assertEquals(ActionType.FOLLOW, AIResponseParser.extractAction("  [action:follow]\n").orElseThrow().type());
		assertEquals(ActionType.GREET, AIResponseParser.extractAction("<think>hm</think>[action:greet]").orElseThrow().type());
		assertEquals(
			"nearest village",
			AIResponseParser.extractAction("[action:{\"type\":\"world_query\",\"query\":\"nearest village\"}]").orElseThrow().arg("query"));
		assertTrue(AIResponseParser.extractAction("Just words.").isEmpty());
		assertTrue(AIResponseParser.extractAction(null).isEmpty());
		assertTrue(AIResponseParser.extractAction("").isEmpty());
	}

	@Test
	void aNameInFrontIsRemoved() {
		assertEquals("Hello there!", parsed("Falsity: Hello there!").dialogue());
		assertEquals("Hello there!", parsed("**Falsity:** Hello there!").dialogue());
		assertEquals("Hello there!", parsed("falsity : Hello there!").dialogue());
		assertEquals("Falsity is my name.", parsed("Falsity is my name.").dialogue(), "only a label is removed, not the word");
	}

	@Test
	void markdownBecomesPlainChat() {
		assertEquals("I really like you.", parsed("I **really** like you.").dialogue());
		assertEquals("I really like you.", parsed("I __really__ like you.").dialogue());
		assertEquals("Try the torch item.", parsed("Try the `torch` item.").dialogue());
		assertEquals("*hugs you* That sounds lovely.", parsed("*hugs you* That sounds lovely.").dialogue(), "a small gesture is left alone");
		assertEquals("2 * 3 ** 4", parsed("2 * 3 ** 4").dialogue(), "arithmetic is not emphasis");
	}

	@Test
	void aReplyIsOneLine() {
		assertEquals("Line one. Line two. Line three.", parsed("Line one.\nLine two.\r\n\r\n\tLine three.").dialogue());
		assertEquals("Spaces are tidied.", parsed("  Spaces   are    tidied.  ").dialogue());
	}

	@Test
	void quotesAroundTheWholeReplyAreRemoved() {
		assertEquals("Hello.", parsed("\"Hello.\"").dialogue());
		assertEquals("Hello.", parsed("\u201CHello.\u201D").dialogue());
		assertEquals("I will sit.", parsed("\"I will sit.\" [action:sit]").dialogue());
		assertEquals("\"Hi,\" I say. \"Welcome back.\"", parsed("\"Hi,\" I say. \"Welcome back.\"").dialogue(), "two quoted pieces are not one quoted reply");
		assertEquals("\"", parsed("\"").dialogue());
	}

	@Test
	void aLongReplyEndsAtASentence() {
		String sentence = "This is a sentence that goes on for a while. ";
		String dialogue = parsed(sentence.repeat(12)).dialogue();

		assertTrue(dialogue.length() <= AIResponseParser.MAX_DIALOGUE_CHARS, "length " + dialogue.length());
		assertEquals(sentence.repeat(6).trim(), dialogue, "six whole sentences fit in the limit, a seventh does not");
	}

	@Test
	void aLongReplyWithoutSentencesEndsBetweenWords() {
		String dialogue = parsed("word ".repeat(120)).dialogue();

		assertTrue(dialogue.length() <= AIResponseParser.MAX_DIALOGUE_CHARS, "length " + dialogue.length());
		assertTrue(dialogue.endsWith("word..."), dialogue);
		assertFalse(dialogue.contains("wor..."), "no word is cut in half");
	}

	@Test
	void shorteningKeepsShortTextAndRespectsTheLimit() {
		assertEquals("Short.", AIResponseParser.shorten("Short.", 300));
		assertEquals("x".repeat(300), AIResponseParser.shorten("x".repeat(301), 300));
		assertEquals("x".repeat(300), AIResponseParser.shorten("x".repeat(300), 300));
		assertEquals("One. Two.", AIResponseParser.shorten("One. Two. Three is long.", 12));
		assertEquals("Version 1.5 is out.", AIResponseParser.shorten("Version 1.5 is out. More follows here.", 22), "a decimal point is not a full stop");
		for (int length = 280; length <= 340; length++) {
			String text = ("Sentence number one is here. And another one follows! Is this the third? ").repeat(6).substring(0, length);
			assertTrue(AIResponseParser.shorten(text, 300).length() <= 300, "length " + length);
		}
	}

	@Test
	void anActionAfterALongReplyIsStillFound() {
		String reply = "word ".repeat(120) + "[action:{\"type\":\"follow\"}]";

		assertEquals(ActionType.FOLLOW, action(reply).type());
		assertFalse(parsed(reply).dialogue().contains("action"));
	}
}
