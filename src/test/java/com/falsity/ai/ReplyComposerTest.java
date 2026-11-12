package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.world.FactGuard;
import org.junit.jupiter.api.Test;

/** What the player hears when the model's words and the server's result could disagree. */
class ReplyComposerTest {
	@Test
	void whatWasActuallyGivenBeatsWhatTheModelSaid() {
		String reply = ReplyComposer.compose(
			"Here are your 64 diamonds!", ActionType.GIVE_ITEM, ActionResult.ok("Here you go: 10 of the 64 diamonds. Your inventory is full."));

		assertEquals("Here you go: 10 of the 64 diamonds. Your inventory is full.", reply);
	}

	@Test
	void aRefusalIsNeverDressedUpAsSuccess() {
		String reply = ReplyComposer.compose(
			"Of course! Enjoy your bedrock.", ActionType.GIVE_ITEMS, ActionResult.failure("I can't hand out bedrock."));

		assertEquals("I can't hand out bedrock.", reply);
		assertEquals("Only my owner can ask me to follow.",
			ReplyComposer.compose("On my way!", ActionType.FOLLOW, ActionResult.failure("Only my owner can ask me to follow.")));
	}

	@Test
	void aQuestionThatNeedsConfirmingIsAsked() {
		String reply = ReplyComposer.compose(
			"Done!", ActionType.GIVE_ITEMS, ActionResult.pending("That is 192 items. Shall I? Say yes or no."));

		assertEquals("That is 192 items. Shall I? Say yes or no.", reply);
	}

	@Test
	void aSimpleOrderKeepsTheModelsOwnWords() {
		String reply = ReplyComposer.compose("Right behind you!", ActionType.FOLLOW, ActionResult.ok("Following you."));

		assertEquals("Right behind you!", reply);
	}

	@Test
	void factsFromTheWorldAreReportedAsFound() {
		String facts = "The nearest village is about 212 blocks north-east, around x=140, z=-160.";

		assertEquals(facts, ReplyComposer.compose("It's just over that hill!", ActionType.WORLD_QUERY, ActionResult.ok(facts)));
		assertTrue(ReplyComposer.isFactual(ActionType.GIVE_ITEM));
		assertTrue(ReplyComposer.isFactual(ActionType.GIVE_ITEMS));
		assertTrue(ReplyComposer.isFactual(ActionType.REPORT_THREATS));
		assertTrue(ReplyComposer.isFactual(ActionType.BUILD));
		assertFalse(ReplyComposer.isFactual(ActionType.FOLLOW));
		assertFalse(ReplyComposer.isFactual(ActionType.SIT));
	}

	@Test
	void withoutAnActionTheDialogueStands() {
		assertEquals("Hello!", ReplyComposer.compose("Hello!", null, null));
		assertEquals("", ReplyComposer.compose("  ", null, null));
		assertEquals("", ReplyComposer.compose(null, null, null));
		assertEquals("Hello!", ReplyComposer.compose("Hello!", ActionType.SIT, ActionResult.ok("")));
	}

	@Test
	void anActionWithNoDialogueSpeaksForItself() {
		assertEquals("Sitting.", ReplyComposer.compose(null, ActionType.SIT, ActionResult.ok("Sitting.")));
		assertEquals("Sitting.", ReplyComposer.compose("   ", ActionType.SIT, ActionResult.ok("Sitting.")));
	}

	@Test
	void anInventedLocationIsReplacedByAnHonestAnswer() {
		String invented = "The village is at 120, 64, -30. Follow me!";

		assertEquals(FactGuard.honestReply(), ReplyComposer.guardLocation(invented, ""));
		assertEquals(FactGuard.honestReply(), ReplyComposer.guardLocation(invented, null));
		assertEquals(FactGuard.honestReply(), ReplyComposer.guardLocation(invented, "You are at x=5, y=70, z=9."));
	}

	@Test
	void aLocationTheServerVerifiedIsLetThrough() {
		String facts = "Nearest village: x=120, y=64, z=-30, about 200 blocks north.";
		String reply = "The village is at 120, 64, -30, about 200 blocks north of here.";

		assertEquals(reply, ReplyComposer.guardLocation(reply, facts));
	}

	@Test
	void ordinaryTalkIsNotTouched() {
		for (String line : new String[] {
			"I like you too!", "I have counted 3 sheep today.", "We have talked 12 times now.",
			"Here are 64 torches.", "It is day 5 in this world.", "Let's go north.", "", null}) {
			assertEquals(line, ReplyComposer.guardLocation(line, ""));
		}
	}
}
