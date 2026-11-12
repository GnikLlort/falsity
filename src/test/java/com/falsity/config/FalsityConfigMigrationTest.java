package com.falsity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Old settings files, broken settings files and reloading: none of them may lose or invent a setting. */
class FalsityConfigMigrationTest {
	/** A file as Falsity 0.2.1 wrote it: no format version, the old cooldown, none of the newer options. */
	private static final String OLD_FILE = """
		{
		  "debugLogging": false,
		  "companion": { "followOwner": true, "followStartDistance": 6.0, "teleportDistance": 24.0, "followSpeed": 1.1 },
		  "groq": {
		    "enabled": true,
		    "apiKey": "",
		    "model": "openai/gpt-oss-20b",
		    "endpoint": "https://api.groq.com/openai/v1/chat/completions",
		    "timeoutSeconds": 15,
		    "maxTokens": 150,
		    "temperature": 0.7
		  },
		  "conversation": { "enabled": true, "cooldownSeconds": 10, "maxRequestsPerMinute": 10, "maxMessageLength": 256 },
		  "items": { "enabled": true, "permissionMode": "OPERATOR", "maxPerRequest": 32, "cooldownSeconds": 15, "confirmAboveCount": 128 }
		}
		""";

	@TempDir
	Path directory;

	private Path file() {
		return this.directory.resolve("falsity.json");
	}

	private static String oldFileWithCooldown(int seconds) {
		return OLD_FILE.replace("\"cooldownSeconds\": 10,", "\"cooldownSeconds\": " + seconds + ",");
	}

	@Test
	void aFileFromBeforeFormatTwoGetsTheNewCooldown() {
		FalsityConfig config = FalsityConfig.fromJson(OLD_FILE);
		assertEquals(0, config.configVersion, "an old file carries no version");
		assertEquals(10, config.conversation.cooldownSeconds);

		assertTrue(config.migrate());

		assertEquals(FalsityConfig.CURRENT_VERSION, config.configVersion);
		assertEquals(3, config.conversation.cooldownSeconds, "the old default becomes the new default");
		assertFalse(config.migrate(), "a second migration has nothing left to do");
	}

	@Test
	void whatTheOldFileSetIsKeptAndWhatItLackedGetsDefaults() {
		FalsityConfig config = FalsityConfig.fromJson(OLD_FILE);
		config.migrate();
		config.sanitise();

		assertEquals(ItemPermissionMode.OPERATOR, config.items.permissionMode);
		assertEquals(32, config.items.maxPerRequest);
		assertEquals(8, config.items.maxTypesPerRequest);
		assertEquals("low", config.groq.reasoningEffort);
		assertEquals(512, config.groq.reasoningHeadroomTokens);
		assertEquals(6, config.groq.historyTurns);
		assertTrue(config.world.enabled);
		assertTrue(config.world.shareCoordinates);
		assertEquals(ItemPermissionMode.OWNER, config.world.locatePermission);
		assertEquals(64, config.world.structureSearchRadiusChunks);
		assertEquals(24, config.world.scanRadius);
		assertEquals(5, config.world.locateCooldownSeconds);
	}

	@Test
	void aCooldownChosenByHandIsKept() {
		for (int chosen : new int[] {0, 1, 5, 25, 120}) {
			FalsityConfig config = FalsityConfig.fromJson(oldFileWithCooldown(chosen));

			assertTrue(config.migrate());

			assertEquals(chosen, config.conversation.cooldownSeconds);
		}
	}

	@Test
	void aCurrentFileIsNotMigratedAgain() {
		FalsityConfig config = new FalsityConfig();
		config.configVersion = FalsityConfig.CURRENT_VERSION;
		config.conversation.cooldownSeconds = 10;

		assertFalse(config.migrate());

		assertEquals(10, config.conversation.cooldownSeconds, "ten seconds chosen after the change is a choice, not the old default");
	}

	@Test
	void aFileFromANewerVersionIsLeftAlone() {
		FalsityConfig config = new FalsityConfig();
		config.configVersion = FalsityConfig.CURRENT_VERSION + 1;
		config.conversation.cooldownSeconds = 10;

		assertFalse(config.migrate());

		assertEquals(FalsityConfig.CURRENT_VERSION + 1, config.configVersion);
		assertEquals(10, config.conversation.cooldownSeconds);
	}

	@Test
	void loadingWithNoFileWritesOneInTheCurrentFormat() throws IOException {
		FalsityConfig config = FalsityConfig.load(file());

		assertEquals(FalsityConfig.CURRENT_VERSION, config.configVersion);
		assertTrue(Files.exists(file()));
		FalsityConfig written = FalsityConfig.fromJson(Files.readString(file(), StandardCharsets.UTF_8));
		assertEquals(FalsityConfig.CURRENT_VERSION, written.configVersion);
		assertEquals(config.toJson(), written.toJson());
		assertEquals("", written.groq.apiKey, "a fresh file never contains a key");
	}

	@Test
	void loadingAnOldFileRewritesItWithTheNewOptions() throws IOException {
		Files.writeString(file(), OLD_FILE, StandardCharsets.UTF_8);

		FalsityConfig config = FalsityConfig.load(file());

		assertEquals(3, config.conversation.cooldownSeconds);
		String rewritten = Files.readString(file(), StandardCharsets.UTF_8);
		assertTrue(rewritten.contains("\"configVersion\": " + FalsityConfig.CURRENT_VERSION), rewritten);
		assertTrue(rewritten.contains("\"world\""), "new sections are written out so they can be found and edited");
		assertTrue(rewritten.contains("\"reasoningEffort\""));
		assertTrue(rewritten.contains("\"OPERATOR\""), "what the owner had set is still there");
		assertFalse(Files.exists(this.directory.resolve("falsity.json.tmp")), "no temporary file is left behind");
	}

	@Test
	void loadingACurrentFileDoesNotRewriteIt() throws IOException {
		FalsityConfig current = new FalsityConfig();
		current.configVersion = FalsityConfig.CURRENT_VERSION;
		String text = current.toJson().replace("\"debugLogging\": false", "\"debugLogging\":   false");
		Files.writeString(file(), text, StandardCharsets.UTF_8);

		FalsityConfig.load(file());

		assertEquals(text, Files.readString(file(), StandardCharsets.UTF_8), "the owner's formatting is not touched without need");
	}

	@Test
	void aBrokenFileGivesDefaultsAndIsLeftUntouched() throws IOException {
		String broken = "{ \"conversation\": { \"cooldownSeconds\": 7 ";
		Files.writeString(file(), broken, StandardCharsets.UTF_8);

		FalsityConfig config = FalsityConfig.load(file());

		assertEquals(new FalsityConfig().conversation.cooldownSeconds, config.conversation.cooldownSeconds);
		assertEquals(broken, Files.readString(file(), StandardCharsets.UTF_8), "the owner can still repair what they wrote");
	}

	@Test
	void aSettingOfTheWrongKindIsABrokenFile() throws IOException {
		Files.writeString(file(), "{ \"conversation\": { \"cooldownSeconds\": \"fast\" } }", StandardCharsets.UTF_8);

		assertTrue(FalsityConfig.read(file()).isEmpty());
		assertEquals(new FalsityConfig().conversation.cooldownSeconds, FalsityConfig.load(file()).conversation.cooldownSeconds);
	}

	@Test
	void aSectionSetToNullIsRejectedInsteadOfCrashing() throws IOException {
		assertThrows(JsonSyntaxException.class, () -> FalsityConfig.fromJson("{ \"groq\": null }"));
		assertThrows(JsonSyntaxException.class, () -> FalsityConfig.fromJson("{ \"world\": null, \"debugLogging\": true }"));

		Files.writeString(file(), "{ \"groq\": null }", StandardCharsets.UTF_8);

		FalsityConfig config = FalsityConfig.load(file());
		assertEquals("openai/gpt-oss-20b", config.groq.model);
		assertTrue(FalsityConfig.read(file()).isEmpty());
	}

	@Test
	void nullValuesInsideASectionFallBackToDefaults() {
		FalsityConfig config = FalsityConfig.fromJson(
			"{ \"groq\": { \"model\": null, \"endpoint\": null, \"apiKey\": null, \"reasoningEffort\": null },"
				+ " \"items\": { \"permissionMode\": null }, \"world\": { \"locatePermission\": \"EVERYBODY\" } }");

		config.sanitise();

		assertEquals("openai/gpt-oss-20b", config.groq.model);
		assertEquals("https://api.groq.com/openai/v1/chat/completions", config.groq.endpoint);
		assertEquals("", config.groq.apiKey);
		assertEquals("", config.groq.reasoningEffort, "no effort set means no reasoning settings are sent");
		assertEquals(ItemPermissionMode.OWNER, config.items.permissionMode);
		assertEquals(ItemPermissionMode.OWNER, config.world.locatePermission, "an unknown mode never becomes a wider one");
	}

	@Test
	void readingForAReloadNeverWritesAndNeverGuesses() throws IOException {
		assertTrue(FalsityConfig.read(file()).isEmpty(), "no file: nothing to reload");
		assertFalse(Files.exists(file()), "reading must not create the file");
		assertTrue(FalsityConfig.read(null).isEmpty());

		Files.writeString(file(), "{ not json", StandardCharsets.UTF_8);
		assertTrue(FalsityConfig.read(file()).isEmpty(), "a broken file keeps the running settings");

		Files.writeString(file(), OLD_FILE, StandardCharsets.UTF_8);
		Optional<FalsityConfig> read = FalsityConfig.read(file());
		assertTrue(read.isPresent());
		assertEquals(3, read.get().conversation.cooldownSeconds, "a reload migrates in memory");
		assertEquals(FalsityConfig.CURRENT_VERSION, read.get().configVersion);
		assertEquals(OLD_FILE, Files.readString(file(), StandardCharsets.UTF_8), "but leaves the file as the owner wrote it");
	}

	@Test
	void aReloadClampsWhatItReads() throws IOException {
		Files.writeString(file(),
			"{ \"configVersion\": 2, \"conversation\": { \"cooldownSeconds\": 99999 }, \"world\": { \"scanRadius\": 5000 },"
				+ " \"groq\": { \"historyTurns\": -3, \"reasoningEffort\": \"EXTREME\" } }",
			StandardCharsets.UTF_8);

		FalsityConfig config = FalsityConfig.read(file()).orElseThrow();

		assertEquals(300, config.conversation.cooldownSeconds);
		assertEquals(48, config.world.scanRadius);
		assertEquals(0, config.groq.historyTurns);
		assertEquals("low", config.groq.reasoningEffort);
	}

	@Test
	void copyingReplacesEveryValueButKeepsTheSameObjects() {
		FalsityConfig running = new FalsityConfig();
		FalsityConfig.Groq groq = running.groq;
		FalsityConfig.World world = running.world;
		FalsityConfig.Conversation conversation = running.conversation;

		FalsityConfig reloaded = FalsityConfig.fromJson(everyValueChanged(new FalsityConfig().toJson()));
		String before = reloaded.toJson();
		assertNotEquals(running.toJson(), before);

		running.copyFrom(reloaded);

		assertEquals(before, running.toJson(), "every setting took the reloaded value");
		assertEquals(before, reloaded.toJson(), "the source is not changed");
		assertSame(groq, running.groq, "whoever holds a section keeps seeing live values");
		assertSame(world, running.world);
		assertSame(conversation, running.conversation);
		assertEquals(reloaded.groq.model, groq.model);
		assertEquals(reloaded.world.scanRadius, world.scanRadius);
	}

	@Test
	void copyingFromNothingOrFromItselfChangesNothing() {
		FalsityConfig config = new FalsityConfig();
		config.conversation.cooldownSeconds = 7;
		String before = config.toJson();

		config.copyFrom(null);
		config.copyFrom(config);

		assertEquals(before, config.toJson());
	}

	@Test
	void theDocumentedExampleIsTheCurrentDefaults() throws IOException {
		Path example = Path.of("docs", "falsity.example.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(example), "run from the project directory to check the example file");

		FalsityConfig documented = FalsityConfig.fromJson(Files.readString(example, StandardCharsets.UTF_8));
		FalsityConfig defaults = new FalsityConfig();
		defaults.configVersion = FalsityConfig.CURRENT_VERSION;

		assertEquals(defaults.toJson(), documented.toJson(), "docs/falsity.example.json must list every setting with its default");
		assertEquals("", documented.groq.apiKey, "the example must never contain a key");
	}

	/** The same settings file with every single value changed to something else. */
	private static String everyValueChanged(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		change(root);
		return root.toString();
	}

	private static void change(JsonObject object) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			JsonElement value = entry.getValue();
			if (value.isJsonObject()) {
				change(value.getAsJsonObject());
				continue;
			}
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				entry.setValue(new JsonPrimitive(!primitive.getAsBoolean()));
			} else if (primitive.isNumber()) {
				String text = primitive.getAsString();
				entry.setValue(text.contains(".")
					? new JsonPrimitive(primitive.getAsDouble() + 0.25)
					: new JsonPrimitive(primitive.getAsInt() + 1));
			} else if (entry.getKey().equals("permissionMode") || entry.getKey().equals("locatePermission")) {
				entry.setValue(new JsonPrimitive("CREATIVE"));
			} else {
				entry.setValue(new JsonPrimitive(primitive.getAsString() + "-changed"));
			}
		}
	}
}
