package com.falsity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Groq address in the config. A wrong one makes every request fail while
 * the key and the model are fine, so the usual ways of getting it wrong are
 * put right when the config is read.
 */
class GroqEndpointTest {
	private static final String DEFAULT = "https://api.groq.com/openai/v1/chat/completions";

	private static String fixed(String endpoint) {
		return FalsityConfig.normaliseEndpoint(endpoint);
	}

	@Test
	void theDefaultIsGroqsChatCompletionsAddress() {
		assertEquals(DEFAULT, FalsityConfig.DEFAULT_GROQ_ENDPOINT);
		assertEquals(DEFAULT, new FalsityConfig().groq.endpoint);
		assertEquals(DEFAULT, fixed(DEFAULT));
	}

	@Test
	void aMissingAddressBecomesTheDefault() {
		assertEquals(DEFAULT, fixed(null));
		assertEquals(DEFAULT, fixed(""));
		assertEquals(DEFAULT, fixed("   "));
	}

	@Test
	void theBaseAddressOtherToolsAskForIsCompleted() {
		assertEquals(DEFAULT, fixed("https://api.groq.com/openai/v1"));
		assertEquals(DEFAULT, fixed("https://api.groq.com/openai/v1/"));
		assertEquals(DEFAULT, fixed("https://api.groq.com/openai"));
		assertEquals(DEFAULT, fixed("https://api.groq.com"));
		assertEquals(DEFAULT, fixed("https://api.groq.com/"));
		assertEquals(DEFAULT, fixed("https://api.groq.com/v1/chat/completions"));
	}

	@Test
	void spacesSlashesAndCapitalsDoNotMatter() {
		assertEquals(DEFAULT, fixed("  " + DEFAULT + "  "));
		assertEquals(DEFAULT, fixed(DEFAULT + "/"));
		assertEquals(DEFAULT, fixed(DEFAULT + "\n"));
		assertEquals(DEFAULT, fixed("HTTPS://API.GROQ.COM/OpenAI/V1"));
	}

	@Test
	void aMissingSchemeIsHttps() {
		assertEquals(DEFAULT, fixed("api.groq.com/openai/v1/chat/completions"));
		assertEquals(DEFAULT, fixed("api.groq.com"));
		assertEquals("https://proxy.example/v1/chat/completions", fixed("proxy.example/v1/chat/completions"));
	}

	@Test
	void theKeyIsNeverSentToGroqUnencrypted() {
		assertEquals(DEFAULT, fixed("http://api.groq.com/openai/v1/chat/completions"));
		assertEquals(DEFAULT, fixed("http://api.groq.com/openai/v1"));
		assertEquals("https://api.groq.com/openai/v1/responses", fixed("http://api.groq.com/openai/v1/responses"));
	}

	@Test
	void anotherServersAddressIsLeftAsWritten() {
		assertEquals("http://localhost:8080/v1/chat/completions", fixed("http://localhost:8080/v1/chat/completions"));
		assertEquals("https://my.proxy.example/groq/openai/v1/chat/completions",
			fixed("https://my.proxy.example/groq/openai/v1/chat/completions"));
		assertEquals("http://localhost:8080", fixed("http://localhost:8080"));
		assertEquals("https://console.groq.com/keys", fixed("https://console.groq.com/keys"));
		assertEquals("https://api.groq.com/openai/v1/responses", fixed("https://api.groq.com/openai/v1/responses"));
	}

	@Test
	void anotherServersBaseAddressIsCompletedToo() {
		assertEquals("http://localhost:8080/v1/chat/completions", fixed("http://localhost:8080/v1"));
		assertEquals("http://localhost:8080/v1/chat/completions", fixed("http://localhost:8080/v1/"));
		assertEquals("https://my.proxy.example/openai/v1/chat/completions", fixed("https://my.proxy.example/openai/v1"));
	}

	@Test
	void anAddressWithAQueryIsOnlyTrimmed() {
		String azure = "https://example.openai.azure.com/openai/deployments/x/chat/completions?api-version=2024-02-01";
		assertEquals(azure, fixed("  " + azure + " "));
		assertEquals("http://localhost:8080/v1?x=1", fixed("http://localhost:8080/v1?x=1"));
	}

	@Test
	void readingTheConfigAppliesIt() {
		FalsityConfig config = new FalsityConfig();
		config.groq.endpoint = "https://api.groq.com/openai/v1/";
		config.sanitise();
		assertEquals(DEFAULT, config.groq.endpoint);

		config.groq.endpoint = null;
		config.sanitise();
		assertEquals(DEFAULT, config.groq.endpoint);

		config.groq.endpoint = "http://localhost:1234/v1";
		config.sanitise();
		assertEquals("http://localhost:1234/v1/chat/completions", config.groq.endpoint);
		config.sanitise();
		assertEquals("http://localhost:1234/v1/chat/completions", config.groq.endpoint, "applying it twice changes nothing");
	}
}
