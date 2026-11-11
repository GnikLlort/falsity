package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.ApiKeyProvider.Located;
import com.falsity.ai.ApiKeyProvider.Source;
import com.falsity.config.FalsityConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the key, and saying why not when there is none. A key that is set
 * but not picked up looks exactly like a mod that does not work, so every way
 * of getting it slightly wrong must either be forgiven or be named.
 */
class ApiKeyLocationTest {
	private static final String KEY = "gsk_abcdefghijklmnop1234567890";

	@TempDir
	Path gameDir;

	private static ApiKeyProvider noEnvironment() {
		return new ApiKeyProvider(() -> null);
	}

	private void secrets(String content) throws IOException {
		Files.writeString(this.gameDir.resolve("secrets.json"), content, StandardCharsets.UTF_8);
	}

	private Located locate() {
		return noEnvironment().locate(new FalsityConfig(), this.gameDir);
	}

	@Test
	void aPastedKeyIsTidied() {
		assertEquals(KEY, ApiKeyProvider.normalise(KEY));
		assertEquals(KEY, ApiKeyProvider.normalise("  " + KEY + " \n"));
		assertEquals(KEY, ApiKeyProvider.normalise("\"" + KEY + "\""));
		assertEquals(KEY, ApiKeyProvider.normalise("'" + KEY + "'"));
		assertEquals(KEY, ApiKeyProvider.normalise("\u201C" + KEY + "\u201D"));
		assertEquals(KEY, ApiKeyProvider.normalise("Bearer " + KEY));
		assertEquals(KEY, ApiKeyProvider.normalise("bearer  " + KEY));
		assertEquals(KEY, ApiKeyProvider.normalise("\"Bearer " + KEY + "\""));
		assertEquals(KEY, ApiKeyProvider.normalise("\uFEFF" + KEY), "a byte-order mark from a text editor");
		assertEquals(KEY, ApiKeyProvider.normalise(KEY + "\u200B"), "a zero-width space from a web page");
		assertEquals(KEY, ApiKeyProvider.normalise("\u00A0" + KEY + "\u00A0"), "non-breaking spaces");
		assertEquals(KEY, ApiKeyProvider.normalise(KEY.substring(0, 12) + "\r\n" + KEY.substring(12)), "a key wrapped over two lines");
		assertEquals("", ApiKeyProvider.normalise(null));
		assertEquals("", ApiKeyProvider.normalise("   "));
		assertEquals("", ApiKeyProvider.normalise("\"\""));
	}

	@Test
	void exampleTextIsNotAKey() {
		assertTrue(ApiKeyProvider.isPlaceholder("REPLACE_WITH_YOUR_GROQ_API_KEY"));
		assertTrue(ApiKeyProvider.isPlaceholder("replace_with_your_groq_api_key"));
		assertTrue(ApiKeyProvider.isPlaceholder("YOUR_API_KEY"));
		assertTrue(ApiKeyProvider.isPlaceholder("your_key_here"));
		assertTrue(ApiKeyProvider.isPlaceholder("gsk_..."));
		assertTrue(ApiKeyProvider.isPlaceholder("<your key>"));
		assertTrue(ApiKeyProvider.isPlaceholder("put your key here"));
		assertTrue(ApiKeyProvider.isPlaceholder("gsk_YOUR_GROQ_API_KEY"));

		assertFalse(ApiKeyProvider.isPlaceholder(KEY));
		assertFalse(ApiKeyProvider.isPlaceholder("gsk_Your0wnK3yLooksLikeThis"));
		assertFalse(ApiKeyProvider.isPlaceholder(""));
		assertFalse(ApiKeyProvider.isPlaceholder(null));
	}

	@Test
	void theSourceIsReportedForEachPlace() throws IOException {
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "from-config";

		Located fromConfig = noEnvironment().locate(config, this.gameDir);
		assertEquals(Source.CONFIG, fromConfig.source());
		assertEquals("from-config", fromConfig.key());
		assertTrue(fromConfig.present());

		secrets("{\"groqApiKey\": \"from-secrets\"}");
		Located fromSecrets = noEnvironment().locate(config, this.gameDir);
		assertEquals(Source.SECRETS_FILE, fromSecrets.source());
		assertEquals("from-secrets", fromSecrets.key());

		Located fromEnvironment = new ApiKeyProvider(() -> "from-env").locate(config, this.gameDir);
		assertEquals(Source.ENVIRONMENT, fromEnvironment.source());
		assertEquals("from-env", fromEnvironment.key());
		assertEquals("", fromEnvironment.note());
	}

	@Test
	void nothingAnywhereIsReportedPlainly() {
		Located located = locate();

		assertFalse(located.present());
		assertEquals(Source.NONE, located.source());
		assertEquals("", located.key());
		assertEquals("", located.note(), "nothing was found, so there is nothing to explain");
		assertEquals("nowhere", located.source().description());
	}

	@Test
	void whereTheSecretsFileBelongsIsSpelledOut() {
		Located located = locate();

		Path expected = this.gameDir.resolve("secrets.json").toAbsolutePath().normalize();
		assertEquals(expected.toString(), located.secretsPath());
		assertTrue(Path.of(located.secretsPath()).isAbsolute());
		assertEquals("", noEnvironment().locate(new FalsityConfig(), null).secretsPath(), "no game directory, no path to name");
	}

	@Test
	void theUneditedExampleIsNamedAsSuch() throws IOException {
		secrets("{\n  \"groqApiKey\": \"REPLACE_WITH_YOUR_GROQ_API_KEY\"\n}\n");

		Located located = locate();

		assertFalse(located.present());
		assertTrue(located.note().contains("secrets.json"), located.note());
		assertTrue(located.note().contains("placeholder"), located.note());
	}

	@Test
	void theShippedExampleFileIsAPlaceholder() throws IOException {
		Path example = Path.of("docs", "secrets.example.json");
		Assumptions.assumeTrue(Files.exists(example), "run from the project directory to check the example file");
		Files.copy(example, this.gameDir.resolve("secrets.json"));

		Located located = locate();

		assertFalse(located.present(), "the example must never be mistaken for a key");
		assertTrue(located.note().contains("placeholder"), located.note());
	}

	@Test
	void aPlaceholderInOnePlaceDoesNotHideARealKeyInAnother() throws IOException {
		secrets("{\"groqApiKey\": \"REPLACE_WITH_YOUR_GROQ_API_KEY\"}");
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = KEY;

		Located located = new ApiKeyProvider(() -> "your_key_here").locate(config, this.gameDir);

		assertEquals(Source.CONFIG, located.source());
		assertEquals(KEY, located.key());
	}

	@Test
	void everyPlaceholderIsMentioned() throws IOException {
		secrets("{\"groqApiKey\": \"REPLACE_WITH_YOUR_GROQ_API_KEY\"}");
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "<your key>";

		Located located = new ApiKeyProvider(() -> "your_key_here").locate(config, this.gameDir);

		assertFalse(located.present());
		assertTrue(located.note().contains("GROQ_API_KEY still holds the placeholder text"), located.note());
		assertTrue(located.note().contains("secrets.json still holds the placeholder text"), located.note());
		assertTrue(located.note().contains("groq.apiKey still holds the placeholder text"), located.note());
	}

	@Test
	void aKeyWithASpaceInItIsNamedAsSuch() {
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "gsk_abc def";

		Located located = noEnvironment().locate(config, this.gameDir);

		assertFalse(located.present());
		assertTrue(located.note().contains("groq.apiKey contains a space"), located.note());
		assertFalse(located.note().contains("gsk_abc"), "the note never quotes the value");
	}

	@Test
	void aKeyInTheConfigFolderIsFoundToo() throws IOException {
		Files.createDirectories(this.gameDir.resolve("config"));
		Files.writeString(this.gameDir.resolve("config").resolve("secrets.json"), "{\"groqApiKey\": \"" + KEY + "\"}");

		Located located = locate();

		assertEquals(Source.SECRETS_FILE, located.source());
		assertEquals(KEY, located.key());
	}

	@Test
	void theGameDirectoryComesBeforeTheConfigFolder() throws IOException {
		Files.createDirectories(this.gameDir.resolve("config"));
		Files.writeString(this.gameDir.resolve("config").resolve("secrets.json"), "{\"groqApiKey\": \"from-config-folder\"}");
		secrets("{\"groqApiKey\": \"from-game-dir\"}");

		assertEquals("from-game-dir", locate().key());

		secrets("{\"groqApiKey\": \"REPLACE_WITH_YOUR_GROQ_API_KEY\"}");
		assertEquals("from-config-folder", locate().key(), "an unedited example does not block the real file");
	}

	@Test
	void aMisnamedFileIsPointedOut() throws IOException {
		Files.writeString(this.gameDir.resolve("secrets.json.txt"), "{\"groqApiKey\": \"" + KEY + "\"}");

		Located located = locate();

		assertFalse(located.present(), "only the documented name is read");
		assertTrue(located.note().contains("secrets.json.txt"), located.note());
		assertTrue(located.note().contains("rename"), located.note());
		assertFalse(located.note().contains(KEY));
	}

	@Test
	void theExampleCopiedWithoutRenamingIsPointedOut() throws IOException {
		Files.createDirectories(this.gameDir.resolve("config"));
		Files.writeString(this.gameDir.resolve("config").resolve("secrets.example.json"), "{\"groqApiKey\": \"" + KEY + "\"}");

		Located located = locate();

		assertFalse(located.present());
		assertTrue(located.note().contains("config/secrets.example.json"), located.note());
	}

	@Test
	void aBrokenFileIsReportedWithoutItsContents() throws IOException {
		secrets("{\"groqApiKey\": \"" + KEY + "\"");

		Located located = locate();

		assertFalse(located.present());
		assertTrue(located.note().contains("secrets.json could not be read"), located.note());
		assertFalse(located.note().contains(KEY), "the note is shown in chat and in the log");
		assertFalse(located.note().contains("gsk_"), located.note());
	}

	@Test
	void anEmptyOrKeylessFileIsReported() throws IOException {
		secrets("");
		assertTrue(locate().note().contains("secrets.json is empty"), locate().note());

		secrets("{}");
		assertTrue(locate().note().contains("has no groqApiKey entry"), locate().note());

		secrets("{\"somethingElse\": \"" + KEY + "\"}");
		assertFalse(locate().present(), "an unrelated entry is not taken for the key");
		assertTrue(locate().note().contains("has no groqApiKey entry"), locate().note());

		secrets("{\"groqApiKey\": \"\"}");
		assertTrue(locate().note().contains("is empty"), locate().note());

		secrets("{\"groqApiKey\": null}");
		assertTrue(locate().note().contains("must be the key as text"), locate().note());

		secrets("{\"groqApiKey\": [\"" + KEY + "\"]}");
		assertFalse(locate().present());
		assertFalse(locate().note().contains(KEY));

		secrets("[]");
		assertTrue(locate().note().contains("could not be read"), locate().note());
	}

	@Test
	void closeSpellingsOfTheEntryAreAccepted() throws IOException {
		for (String name : new String[] {"groqApiKey", "GROQ_API_KEY", "groq_api_key", "GroqApiKey", "apiKey", "api_key"}) {
			secrets("{\"" + name + "\": \"" + KEY + "\"}");
			assertEquals(KEY, locate().key(), name);
		}

		secrets("{\"groq\": {\"apiKey\": \"" + KEY + "\"}}");
		assertEquals(KEY, locate().key(), "the settings file's own shape");
	}

	@Test
	void aKeyInTheFileIsTidiedLikeAnyOther() throws IOException {
		secrets("{\"groqApiKey\": \"  Bearer " + KEY + " \"}");

		assertEquals(KEY, locate().key());
	}

	@Test
	void aFileSavedByWindowsToolsIsStillRead() throws IOException {
		String json = "{\"groqApiKey\": \"" + KEY + "\"}\r\n";

		byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
		byte[] withBom = new byte[utf8.length + 3];
		withBom[0] = (byte) 0xEF;
		withBom[1] = (byte) 0xBB;
		withBom[2] = (byte) 0xBF;
		System.arraycopy(utf8, 0, withBom, 3, utf8.length);
		Files.write(this.gameDir.resolve("secrets.json"), withBom);
		assertEquals(KEY, locate().key(), "UTF-8 with a byte-order mark (Notepad)");

		byte[] utf16 = json.getBytes(StandardCharsets.UTF_16LE);
		byte[] littleEndian = new byte[utf16.length + 2];
		littleEndian[0] = (byte) 0xFF;
		littleEndian[1] = (byte) 0xFE;
		System.arraycopy(utf16, 0, littleEndian, 2, utf16.length);
		Files.write(this.gameDir.resolve("secrets.json"), littleEndian);
		assertEquals(KEY, locate().key(), "UTF-16, as written by PowerShell's > redirection");

		byte[] utf16be = json.getBytes(StandardCharsets.UTF_16BE);
		byte[] bigEndian = new byte[utf16be.length + 2];
		bigEndian[0] = (byte) 0xFE;
		bigEndian[1] = (byte) 0xFF;
		System.arraycopy(utf16be, 0, bigEndian, 2, utf16be.length);
		Files.write(this.gameDir.resolve("secrets.json"), bigEndian);
		assertEquals(KEY, locate().key(), "UTF-16 big-endian");
	}

	@Test
	void aFolderNamedLikeTheFileIsIgnored() throws IOException {
		Files.createDirectories(this.gameDir.resolve("secrets.json"));

		Located located = locate();

		assertFalse(located.present());
		assertEquals("", located.note());
	}

	@Test
	void anEnormousFileIsNotRead() throws IOException {
		secrets("{\"groqApiKey\": \"" + KEY + "\", \"padding\": \"" + "x".repeat(70_000) + "\"}");

		Located located = locate();

		assertFalse(located.present());
		assertTrue(located.note().contains("too large"), located.note());
	}

	@Test
	void aKeyAddedWhileTheServerRunsIsPickedUp() throws IOException {
		ApiKeyProvider provider = noEnvironment();
		FalsityConfig config = new FalsityConfig();
		assertTrue(provider.resolve(config, this.gameDir).isEmpty());

		secrets("{\"groqApiKey\": \"" + KEY + "\"}");
		assertEquals(KEY, provider.resolve(config, this.gameDir).orElseThrow());

		Files.delete(this.gameDir.resolve("secrets.json"));
		assertTrue(provider.resolve(config, this.gameDir).isEmpty(), "and a key that is taken away is gone");
	}

	@Test
	void noGameDirectoryAndNoConfigAreSurvivable() {
		Located located = noEnvironment().locate(null, null);

		assertFalse(located.present());
		assertEquals(Source.NONE, located.source());
		assertEquals(KEY, new ApiKeyProvider(() -> KEY).locate(null, null).key());
	}
}
