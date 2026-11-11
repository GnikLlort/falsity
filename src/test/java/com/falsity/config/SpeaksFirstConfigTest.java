package com.falsity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two settings for Falsity speaking first, and the format change that introduces them. */
class SpeaksFirstConfigTest {
	@TempDir
	Path directory;

	@Test
	void speakingFirstIsOnByDefaultAndRare() {
		FalsityConfig config = new FalsityConfig();

		assertTrue(config.chat.speaksFirst);
		assertEquals(10, config.chat.speaksFirstMinutes);
	}

	@Test
	void theIntervalIsKeptWithinSaneBounds() {
		FalsityConfig config = new FalsityConfig();

		config.chat.speaksFirstMinutes = 0;
		config.sanitise();
		assertEquals(2, config.chat.speaksFirstMinutes, "never more often than every two minutes");

		config.chat.speaksFirstMinutes = -30;
		config.sanitise();
		assertEquals(2, config.chat.speaksFirstMinutes);

		config.chat.speaksFirstMinutes = 100_000;
		config.sanitise();
		assertEquals(240, config.chat.speaksFirstMinutes);

		config.chat.speaksFirstMinutes = 45;
		config.sanitise();
		assertEquals(45, config.chat.speaksFirstMinutes, "a sensible value is left alone");
	}

	@Test
	void theSettingsSurviveBeingWrittenAndRead() {
		FalsityConfig config = new FalsityConfig();
		config.chat.speaksFirst = false;
		config.chat.speaksFirstMinutes = 25;

		FalsityConfig read = FalsityConfig.fromJson(config.toJson());

		assertFalse(read.chat.speaksFirst);
		assertEquals(25, read.chat.speaksFirstMinutes);
	}

	@Test
	void aFileWithoutThemGetsTheDefaults() {
		FalsityConfig read = FalsityConfig.fromJson("{ \"configVersion\": 2, \"chat\": { \"enabled\": true, \"contextTimeoutSeconds\": 60 } }");

		assertTrue(read.chat.speaksFirst);
		assertEquals(10, read.chat.speaksFirstMinutes);
		assertEquals(60, read.chat.contextTimeoutSeconds, "what the file did set is kept");
	}

	@Test
	void aFileFromTheLastVersionIsMovedUpWithoutChangingAnythingItSet() throws IOException {
		FalsityConfig previous = new FalsityConfig();
		previous.configVersion = 2;
		previous.conversation.cooldownSeconds = 10;
		previous.horror.allowUnsettlingLines = false;
		previous.horror.progressionSpeed = 2.5;
		previous.personality.jealousy = 44;
		String written = previous.toJson().replaceAll("(?m)^\\s*\"speaksFirst(Minutes)?\".*\\R", "").replace("\"maxMessageLength\": 256,\n  }", "\"maxMessageLength\": 256\n  }");
		Path file = this.directory.resolve("falsity.json");
		Files.writeString(file, written, StandardCharsets.UTF_8);
		assertFalse(written.contains("speaksFirst"), "the old file knows nothing of the new settings");

		FalsityConfig loaded = FalsityConfig.load(file);

		assertEquals(FalsityConfig.CURRENT_VERSION, loaded.configVersion);
		assertEquals(10, loaded.conversation.cooldownSeconds, "a cooldown chosen under format 2 is a choice and is kept");
		assertFalse(loaded.horror.allowUnsettlingLines);
		assertEquals(2.5, loaded.horror.progressionSpeed);
		assertEquals(44, loaded.personality.jealousy);
		assertEquals(1, loaded.horror.maxStage, "and the horror cap is where it was");
		String rewritten = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(rewritten.contains("\"speaksFirst\": true"), "the new settings are written out where they can be found: " + rewritten);
		assertTrue(rewritten.contains("\"speaksFirstMinutes\": 10"), rewritten);
		assertTrue(rewritten.contains("\"configVersion\": " + FalsityConfig.CURRENT_VERSION), rewritten);
	}

	@Test
	void theFormatNumberMovedOnForThem() {
		assertEquals(3, FalsityConfig.CURRENT_VERSION);

		FalsityConfig previous = new FalsityConfig();
		previous.configVersion = 2;
		assertTrue(previous.migrate(), "a format 2 file has something to gain");
		assertEquals(3, previous.configVersion);
		assertFalse(previous.migrate());
	}
}
