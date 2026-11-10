package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Local, offline intent recognition: the requests that work with no API key. */
class IntentRecognizerTest {
	@Test
	void categoryRequestsAndCourtesyAreSeparatedFromItemName() {
		assertEquals("food", IntentRecognizer.recognize("Can you give me some food please?").orElseThrow().arg("item"));
		assertEquals("full set of armour", IntentRecognizer.recognize("I need a full set of armour").orElseThrow().arg("item"));
		assertEquals("tools", IntentRecognizer.recognize("Give me tools").orElseThrow().arg("item"));
		assertEquals(64, IntentRecognizer.recognize("Give me 64 torches").orElseThrow().argInt("count", 1));
		assertEquals("diamond pickaxe", IntentRecognizer.recognize("I need a diamond pickaxe").orElseThrow().arg("item"));
	}

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

	@Test
	void punctuatedAndNaturalPhrasingsRecognised() {
		ActionRequest diamonds = IntentRecognizer.recognize("Give me 32 diamonds.").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, diamonds.type());
		assertEquals("diamonds", diamonds.arg("item"));
		assertEquals(32, diamonds.argInt("count", 1));

		ActionRequest sword = IntentRecognizer.recognize("Can you give me a sword?").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, sword.type());
		assertEquals("sword", sword.arg("item"));
		assertEquals(1, sword.argInt("count", 1));

		ActionRequest emeralds = IntentRecognizer.recognize("I need the emeralds.").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, emeralds.type());
		assertEquals("emeralds", emeralds.arg("item"));
		assertEquals(1, emeralds.argInt("count", 1));

		ActionRequest woodenHouse = IntentRecognizer.recognize("Build me a wooden house.").orElseThrow();
		assertEquals(ActionType.BUILD, woodenHouse.type());
		assertEquals("house", woodenHouse.arg("structure"));
		assertEquals("wooden", woodenHouse.arg("material"));

		ActionRequest castle = IntentRecognizer.recognize("Build a castle.").orElseThrow();
		assertEquals(ActionType.BUILD, castle.type());
		assertEquals("castle", castle.arg("structure"));
		assertTrue(!castle.has("material"));
	}

	@Test
	void specificMaterialsAndRepeatedSpacesHandled() {
		ActionRequest cobbleWall = IntentRecognizer.recognize("Please  build  a  cobblestone  wall!").orElseThrow();
		assertEquals(ActionType.BUILD, cobbleWall.type());
		assertEquals("wall", cobbleWall.arg("structure"));
		assertEquals("cobblestone", cobbleWall.arg("material"));

		ActionRequest darkOakCabin = IntentRecognizer.recognize("Hey,  can you  give me  12  of the  oak planks ?").orElseThrow();
		assertEquals(ActionType.GIVE_ITEM, darkOakCabin.type());
		assertEquals("oak planks", darkOakCabin.arg("item"));
		assertEquals(12, darkOakCabin.argInt("count", 1));
	}

	@Test
	void vagueRequestsDoNotTriggerActions() {
		assertTrue(IntentRecognizer.recognize("Give me something.").isEmpty());
		assertTrue(IntentRecognizer.recognize("Can you give me anything?").isEmpty());
		assertTrue(IntentRecognizer.recognize("I need help.").isEmpty());
		assertTrue(IntentRecognizer.recognize("Give me a").isEmpty());
		assertTrue(IntentRecognizer.recognize("Give me 5").isEmpty());
		assertTrue(IntentRecognizer.recognize("Build something nice.").isEmpty());
		assertTrue(IntentRecognizer.recognize("Make a thing.").isEmpty());
		assertTrue(IntentRecognizer.recognize("Can you help me?").isEmpty());
		assertTrue(IntentRecognizer.recognize("Make sure you shut the door.").isEmpty());
	}
}
