package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Numbers reach an action as whatever the sender had: an int from the local
 * recogniser, a double or text from a model's JSON. "Give me 32" must be 32
 * in every one of those shapes.
 */
class ActionRequestNumbersTest {
	private static ActionRequest count(Object value) {
		Map<String, Object> args = new HashMap<>();
		args.put("item", "diamond");
		args.put("count", value);
		return ActionRequest.of(ActionType.GIVE_ITEM, args);
	}

	@Test
	void wholeNumbersInEveryShape() {
		assertEquals(32, count(32).argInt("count", 1));
		assertEquals(32, count(32L).argInt("count", 1));
		assertEquals(32, count(32.0).argInt("count", 1), "JSON numbers arrive as doubles");
		assertEquals(32, count(32.0f).argInt("count", 1));
		assertEquals(32, count("32").argInt("count", 1));
		assertEquals(32, count(" 32 ").argInt("count", 1));
		assertEquals(32, count("32.0").argInt("count", 1));
		assertEquals(-4, count(-4).argInt("count", 1));
		assertEquals(0, count(0).argInt("count", 1));
	}

	@Test
	void fractionsAreRounded() {
		assertEquals(3, count(2.5).argInt("count", 1));
		assertEquals(2, count(2.4).argInt("count", 1));
		assertEquals(2, count("2.4").argInt("count", 1));
	}

	@Test
	void whatIsNotANumberFallsBack() {
		assertEquals(1, count("a few").argInt("count", 1));
		assertEquals(1, count("").argInt("count", 1));
		assertEquals(1, count("   ").argInt("count", 1));
		assertEquals(1, count(Double.NaN).argInt("count", 1));
		assertEquals(1, count(Double.POSITIVE_INFINITY).argInt("count", 1));
		assertEquals(1, count("NaN").argInt("count", 1));
		assertEquals(1, count("Infinity").argInt("count", 1));
		assertEquals(1, count(true).argInt("count", 1));
		assertEquals(7, ActionRequest.of(ActionType.GIVE_ITEM, Map.of("item", "diamond")).argInt("count", 7));
	}

	@Test
	void hugeNumbersAreCappedNotWrapped() {
		assertEquals(Integer.MAX_VALUE, count(1.0e18).argInt("count", 1));
		assertEquals(Integer.MAX_VALUE, count("99999999999").argInt("count", 1));
		assertEquals(Integer.MIN_VALUE, count(-1.0e18).argInt("count", 1));
	}

	@Test
	void doublesKeepTheirFraction() {
		assertEquals(12.5, count(12.5).argDouble("count", 0.0));
		assertEquals(12.5, count("12.5").argDouble("count", 0.0));
		assertEquals(12.0, count(12).argDouble("count", 0.0));
		assertEquals(4.0, count("nope").argDouble("count", 4.0));
		assertEquals(4.0, ActionRequest.of(ActionType.WALK_TO).argDouble("x", 4.0));
	}

	@Test
	void textArgumentsReadAsWritten() {
		assertEquals("32", count(32).arg("count"));
		assertEquals("diamond", count(32).arg("item"));
		assertEquals("fallback", count(32).arg("missing", "fallback"));
	}
}
