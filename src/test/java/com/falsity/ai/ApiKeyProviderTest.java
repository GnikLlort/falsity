package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.config.FalsityConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** API key resolution: priority order, and never a key where none exists. */
class ApiKeyProviderTest {
	@TempDir
	Path tempDir;

	@Test
	void environmentWinsOverEverything() throws Exception {
		Files.writeString(this.tempDir.resolve("secrets.json"), "{\"groqApiKey\": \"from-secrets\"}");
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "from-config";

		ApiKeyProvider provider = new ApiKeyProvider(() -> "from-env");
		Optional<String> key = provider.resolve(config, this.tempDir);

		assertTrue(key.isPresent());
		assertEquals("from-env", key.get());
	}

	@Test
	void secretsFileBeatsConfig() throws Exception {
		Files.writeString(this.tempDir.resolve("secrets.json"), "{\"groqApiKey\": \"from-secrets\"}");
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "from-config";

		ApiKeyProvider provider = new ApiKeyProvider(() -> "");
		Optional<String> key = provider.resolve(config, this.tempDir);

		assertTrue(key.isPresent());
		assertEquals("from-secrets", key.get());
	}

	@Test
	void configIsLastResort() {
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "from-config";

		ApiKeyProvider provider = new ApiKeyProvider(() -> "");
		Optional<String> key = provider.resolve(config, this.tempDir);

		assertTrue(key.isPresent());
		assertEquals("from-config", key.get());
	}

	@Test
	void noKeyAnywhereMeansOffline() {
		FalsityConfig config = new FalsityConfig();
		config.groq.apiKey = "";

		ApiKeyProvider provider = new ApiKeyProvider(() -> "");
		assertTrue(provider.resolve(config, this.tempDir).isEmpty());
	}

	@Test
	void blankAndMissingSecretsAreIgnored() throws Exception {
		Files.writeString(this.tempDir.resolve("secrets.json"), "{\"groqApiKey\": \"   \"}");
		FalsityConfig config = new FalsityConfig();

		ApiKeyProvider provider = new ApiKeyProvider(() -> null);
		assertTrue(provider.resolve(config, this.tempDir).isEmpty());
	}

	@Test
	void corruptSecretsFileIsIgnored() throws Exception {
		Files.writeString(this.tempDir.resolve("secrets.json"), "{broken");
		FalsityConfig config = new FalsityConfig();

		ApiKeyProvider provider = new ApiKeyProvider(() -> "");
		assertTrue(provider.resolve(config, this.tempDir).isEmpty(), "A corrupt secrets file must not crash resolution");
	}
}
