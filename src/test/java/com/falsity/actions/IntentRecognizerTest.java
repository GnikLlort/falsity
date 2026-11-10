package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Local, offline intent recognition: the requests that work with no API key. */
class IntentRecognizerTest {
	@Test
	void itemRequestWithCount() {
		ActionRequest request = IntentRecognizer.recognize("give me 32 diamonds").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, request.type());
		assertEquals("diamonds", request.arg("item"));
		assertEquals(32, request.argInt("count", 1));
	}

	@Test
	void itemRequestWithoutCountDefaultsToOne() {
		ActionRequest request = IntentRecognizer.recognize("can you give me a sword").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, request.type());
		assertEquals("sword", request.arg("item"));
		assertEquals(1, request.argInt("count", 1));
	}

	@Test
	void itemRequestArticleStripped() {
		ActionRequest request = IntentRecognizer.recognize("i need the emeralds").orElseThrow();
		assertEquals("emeralds", request.arg("item"));
	}

	@Test
	void buildRequestWithStructureAndMaterial() {
		ActionRequest request = IntentRecognizer.recognize("build me a wooden house").orElseThrow();
		assertEquals(ActionType.BUILD, request.type());
		assertEquals("house", request.arg("structure"));
		assertEquals("wooden", request.arg("material"));
	}

	@Test
	void buildRequestWithoutMaterial() {
		ActionRequest request = IntentRecognizer.recognize("build a castle").orElseThrow();
		assertEquals(ActionType.BUILD, request.type());
		assertEquals("castle", request.arg("structure"));
	}

	@Test
	void vagueBuildIsRejected() {
		assertTrue(IntentRecognizer.recognize("build me something here").isEmpty(), "too vague to act on");
	}

	@Test
	void followComeSitStandRecognised() {
		assertEquals(ActionType.FOLLOW, IntentRecognizer.recognize("follow me").orElseThrow().type());
		assertEquals(ActionType.COME, IntentRecognizer.recognize("come here").orElseThrow().type());
		assertEquals(ActionType.SIT, IntentRecognizer.recognize("sit down").orElseThrow().type());
		assertEquals(ActionType.STAND, IntentRecognizer.recognize("stand up").orElseThrow().type());
	}

	@Test
	void combatAndThreatsRecognised() {
		assertEquals(ActionType.FIGHT, IntentRecognizer.recognize("fight these zombies").orElseThrow().type());
		assertEquals(ActionType.PROTECT, IntentRecognizer.recognize("protect me").orElseThrow().type());
		assertEquals(ActionType.STOP_FIGHT, IntentRecognizer.recognize("stop fighting").orElseThrow().type());
		assertEquals(ActionType.REPORT_THREATS, IntentRecognizer.recognize("what's nearby").orElseThrow().type());
	}

	@Test
	void stopFollowAndStopBuildRecognised() {
		assertEquals(ActionType.STOP_FOLLOW, IntentRecognizer.recognize("stop following me").orElseThrow().type());
		assertEquals(ActionType.STOP_BUILD, IntentRecognizer.recognize("cancel the build").orElseThrow().type());
	}

	@Test
	void ordinaryChatterIsNotAnAction() {
		assertTrue(IntentRecognizer.recognize("how are you doing today").isEmpty());
		assertTrue(IntentRecognizer.recognize("").isEmpty());
		assertTrue(IntentRecognizer.recognize(null).isEmpty());
	}
}
