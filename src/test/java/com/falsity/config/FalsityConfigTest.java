package com.falsity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

/** The configuration must be forgiving of hand edits and never produce nonsense. */
class FalsityConfigTest {
	@Test
	void defaultsAreSensible() {
		FalsityConfig config = new FalsityConfig();

		assertFalse(config.debugLogging);
		assertTrue(config.companion.followOwner);
		assertTrue(config.companion.followStartDistance > 0.0);
		assertTrue(config.companion.teleportDistance > config.companion.followStartDistance);
	}

	@Test
	void missingFieldsKeepTheirDefaults() {
		FalsityConfig config = FalsityConfig.fromJson("{\"debugLogging\": true}");
		FalsityConfig defaults = new FalsityConfig();

		assertTrue(config.debugLogging);
		assertEquals(defaults.companion.followStartDistance, config.companion.followStartDistance);
		assertEquals(defaults.companion.teleportDistance, config.companion.teleportDistance);
	}

	@Test
	void emptyJsonIsRejectedRatherThanSilentlyReplaced() {
		assertThrows(JsonSyntaxException.class, () -> FalsityConfig.fromJson("null"));
		assertThrows(JsonSyntaxException.class, () -> FalsityConfig.fromJson(""));
	}

	@Test
	void roundTripPreservesValues() {
		FalsityConfig original = new FalsityConfig();
		original.debugLogging = true;
		original.companion.followOwner = false;
		original.companion.followStartDistance = 9.5;
		original.companion.teleportDistance = 40.0;
		original.companion.followSpeed = 1.4;

		FalsityConfig restored = FalsityConfig.fromJson(original.toJson());

		assertTrue(restored.debugLogging);
		assertFalse(restored.companion.followOwner);
		assertEquals(9.5, restored.companion.followStartDistance);
		assertEquals(40.0, restored.companion.teleportDistance);
		assertEquals(1.4, restored.companion.followSpeed);
	}

	@Test
	void sanitiseClampsValuesIntoUsableRanges() {
		FalsityConfig config = new FalsityConfig();
		config.companion.followStartDistance = -5.0;
		config.companion.teleportDistance = 1.0;
		config.companion.followSpeed = 500.0;

		config.sanitise();

		assertEquals(2.0, config.companion.followStartDistance);
		assertTrue(config.companion.teleportDistance > config.companion.followStartDistance);
		assertEquals(2.0, config.companion.followSpeed);
	}

	@Test
	void sanitiseSurvivesNaNAndHugeValues() {
		FalsityConfig config = new FalsityConfig();
		config.companion.followStartDistance = Double.NaN;
		config.companion.teleportDistance = Double.MAX_VALUE;
		config.companion.followSpeed = Double.NaN;

		config.sanitise();

		assertEquals(2.0, config.companion.followStartDistance);
		assertEquals(128.0, config.companion.teleportDistance);
		assertEquals(0.1, config.companion.followSpeed);
	}
}
