package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The action allowlist and the pure request/result data types. */
class ActionCoreTest {
	@Test
	void allowlistDeclaresArguments() {
		assertTrue(ActionType.GIVE_ITEM.allowsArg("item"));
		assertTrue(ActionType.GIVE_ITEM.allowsArg("count"));
		assertFalse(ActionType.GIVE_ITEM.allowsArg("x"), "undeclared key is rejected");
		assertTrue(ActionType.LOOK_AT.allowsArg("x"));
		assertTrue(ActionType.BUILD.allowsArg("structure"));
		assertFalse(ActionType.SIT.allowsArg("structure"));
	}

	@Test
	void allowsArgsChecksEveryKey() {
		assertTrue(ActionType.GIVE_ITEM.allowsArgs(Map.of("item", "diamond", "count", 1)));
		assertFalse(ActionType.GIVE_ITEM.allowsArgs(Map.of("item", "diamond", "count", 1, "explode", true)));
		assertTrue(ActionType.NONE.allowsArgs(Map.of()));
		assertTrue(ActionType.NONE.allowsArgs(null));
	}

	@Test
	void noneIsNotARealAction() {
		assertFalse(ActionType.NONE.isReal());
		assertTrue(ActionType.FOLLOW.isReal());
		assertTrue(ActionType.BUILD.isReal());
	}

	@Test
	void requestCoercesArguments() {
		ActionRequest request = ActionRequest.of(ActionType.GIVE_ITEM, Map.of("item", "diamond", "count", 16));
		assertEquals("diamond", request.arg("item"));
		assertEquals(16, request.argInt("count", 1));
		assertEquals(16.0, request.argDouble("count", 1.0));
		assertFalse(request.has("missing"));
		assertTrue(request.has("item"));
	}

	@Test
	void requestFallsBackForMissingOrMalformed() {
		ActionRequest request = ActionRequest.of(ActionType.WALK_TO, Map.of("x", "not-a-number"));
		assertEquals(5, request.argInt("x", 5));
		assertEquals(5.0, request.argDouble("x", 5.0));
		assertEquals("fallback", request.arg("missing", "fallback"));
	}

	@Test
	void nullRequestBecomesNone() {
		ActionRequest request = new ActionRequest(null, null);
		assertEquals(ActionType.NONE, request.type());
		assertTrue(request.args().isEmpty());
	}

	@Test
	void resultOutcomesAreDisjoint() {
		assertTrue(ActionResult.ok("done").success());
		assertFalse(ActionResult.ok("done").pending());

		assertTrue(ActionResult.pending("yes?").pending());
		assertFalse(ActionResult.pending("yes?").success());

		ActionResult failure = ActionResult.failure("nope");
		assertFalse(failure.success());
		assertFalse(failure.pending());
	}

	@Test
	void informativeFlag() {
		assertTrue(ActionResult.ok("done").isInformative());
		assertTrue(ActionResult.failure("nope").isInformative());
		assertTrue(ActionResult.pending("yes?").isInformative());
		assertFalse(new ActionResult(false, false, null).isInformative());
		assertFalse(new ActionResult(false, false, "").isInformative());
	}
}
