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

	@Test
	void groqDefaultsAreOfflineSafe() {
		FalsityConfig config = new FalsityConfig();

		assertTrue(config.groq.enabled);
		assertEquals("", config.groq.apiKey, "No real API key may ship in defaults");
		assertEquals("openai/gpt-oss-20b", config.groq.model);
		assertTrue(config.groq.timeoutSeconds > 0);
		assertTrue(config.groq.maxTokens > 0);
		assertTrue(config.groq.temperature >= 0.0 && config.groq.temperature <= 2.0);
	}

	@Test
	void groqRoundTripPreservesValues() {
		FalsityConfig original = new FalsityConfig();
		original.groq.enabled = false;
		original.groq.model = "llama-3.3-70b-versatile";
		original.groq.timeoutSeconds = 30;
		original.groq.maxTokens = 200;
		original.groq.temperature = 0.3;

		FalsityConfig restored = FalsityConfig.fromJson(original.toJson());

		assertFalse(restored.groq.enabled);
		assertEquals("llama-3.3-70b-versatile", restored.groq.model);
		assertEquals(30, restored.groq.timeoutSeconds);
		assertEquals(200, restored.groq.maxTokens);
		assertEquals(0.3, restored.groq.temperature);
	}

	@Test
	void groqSanitiseClampsNonsense() {
		FalsityConfig config = new FalsityConfig();
		config.groq.timeoutSeconds = 9999;
		config.groq.maxTokens = -5;
		config.groq.temperature = 99.0;
		config.groq.model = "  ";

		config.sanitise();

		assertEquals(60, config.groq.timeoutSeconds);
		assertEquals(16, config.groq.maxTokens);
		assertEquals(2.0, config.groq.temperature);
		assertEquals("openai/gpt-oss-20b", config.groq.model);
	}

	@Test
	void conversationAndMemoryDefaultsAreBounded() {
		FalsityConfig config = new FalsityConfig();

		assertTrue(config.conversation.enabled);
		assertTrue(config.conversation.cooldownSeconds >= 0);
		assertTrue(config.conversation.maxRequestsPerMinute >= 1);
		assertTrue(config.memory.maxStoredFacts > 0);
		assertTrue(config.memory.maxRecentInteractions > 0);
	}

	@Test
	void horrorIsCappedAtStrangeInThisMilestone() {
		FalsityConfig config = new FalsityConfig();
		config.horror.maxStage = 4;

		config.sanitise();

		assertEquals(1, config.horror.maxStage, "This milestone must never allow more than Strange");
	}

	@Test
	void personalityDefaultsAreInRange() {
		FalsityConfig config = new FalsityConfig();

		assertTrue(config.personality.curiosity >= 0 && config.personality.curiosity <= 100);
		assertTrue(config.personality.affection >= 0 && config.personality.affection <= 100);
		assertTrue(config.personality.hostility >= 0 && config.personality.hostility <= 100);
	}
}
