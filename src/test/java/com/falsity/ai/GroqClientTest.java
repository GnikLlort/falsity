package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Groq request building and response parsing, with a stubbed transport. */
class GroqClientTest {
	@Test
	void requestJsonHasOpenAiShape() {
		String json = GroqClient.buildRequestJson(
			"openai/gpt-oss-20b",
			List.of(
				new GroqClient.ChatMessage("system", "You are Falsity."),
				new GroqClient.ChatMessage("user", "Hello!")
			),
			0.7,
			150
		);

		assertTrue(json.contains("\"model\":\"openai/gpt-oss-20b\""));
		assertTrue(json.contains("\"max_tokens\":150"));
		assertTrue(json.contains("\"role\":\"system\""));
		assertTrue(json.contains("\"content\":\"Hello!\""));
	}

	@Test
	void parsesValidResponseContent() {
		String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi there!\"}}]}";

		Optional<String> content = GroqClient.parseResponseContent(body);

		assertTrue(content.isPresent());
		assertEquals("Hi there!", content.get());
	}

	@Test
	void rejectsMalformedResponses() {
		assertTrue(GroqClient.parseResponseContent(null).isEmpty());
		assertTrue(GroqClient.parseResponseContent("").isEmpty());
		assertTrue(GroqClient.parseResponseContent("not json").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{}").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{\"choices\":[]}").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{\"choices\":[{}]}").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{\"choices\":[{\"message\":{}}]}").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}").isEmpty());
		assertTrue(GroqClient.parseResponseContent("{\"choices\":[{\"message\":{\"content\":null}}]}").isEmpty());
	}

	@Test
	void missingKeyFailsWithoutCallingTransport() {
		boolean[] called = {false};
		GroqClient client = new GroqClient(
			(url, key, body, timeout) -> {
				called[0] = true;
				return CompletableFuture.completedFuture(new GroqClient.HttpResult(200, "{}"));
			},
			new GroqClient.GroqRateLimiter()
		);

		GroqClient.Result result = client.chat(
			"https://api.groq.com/openai/v1/chat/completions",
			"",
			"openai/gpt-oss-20b",
			List.of(new GroqClient.ChatMessage("user", "Hi")),
			0.7, 150, 15, 10
		).join();

		assertTrue(result instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.NO_KEY, ((GroqClient.Result.Failed) result).failure());
		assertFalse(called[0], "Transport must not be called without a key");
	}

	@Test
	void httpErrorsMapToFailures() {
		GroqClient rateLimited = clientReturning(429, "rate limited");
		GroqClient.Result limited = rateLimited.chat("https://example.invalid", "key", "m", List.of(), 0.7, 10, 5, 10).join();
		assertTrue(limited instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.RATE_LIMITED, ((GroqClient.Result.Failed) limited).failure());

		GroqClient serverError = clientReturning(500, "boom");
		GroqClient.Result failed = serverError.chat("https://example.invalid", "key", "m", List.of(), 0.7, 10, 5, 10).join();
		assertTrue(failed instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.HTTP_ERROR, ((GroqClient.Result.Failed) failed).failure());
	}

	@Test
	void invalidBodyMapsToInvalidResponse() {
		GroqClient client = clientReturning(200, "{\"choices\":[]}");
		GroqClient.Result result = client.chat("https://example.invalid", "key", "m", List.of(), 0.7, 10, 5, 10).join();
		assertTrue(result instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.INVALID_RESPONSE, ((GroqClient.Result.Failed) result).failure());
	}

	@Test
	void networkFailureMapsToNetworkError() {
		GroqClient client = new GroqClient(
			(url, key, body, timeout) -> CompletableFuture.failedFuture(new RuntimeException("offline")),
			new GroqClient.GroqRateLimiter()
		);
		GroqClient.Result result = client.chat("https://example.invalid", "key", "m", List.of(), 0.7, 10, 5, 10).join();
		assertTrue(result instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.NETWORK_ERROR, ((GroqClient.Result.Failed) result).failure());
	}

	@Test
	void timeoutFailureMapsToTimeout() {
		GroqClient client = new GroqClient(
			(url, key, body, timeout) -> CompletableFuture.failedFuture(new RuntimeException(new java.net.http.HttpTimeoutException("slow"))),
			new GroqClient.GroqRateLimiter()
		);
		GroqClient.Result result = client.chat("https://example.invalid", "key", "m", List.of(), 0.7, 10, 5, 10).join();
		assertTrue(result instanceof GroqClient.Result.Failed);
		assertEquals(GroqClient.Failure.TIMEOUT, ((GroqClient.Result.Failed) result).failure());
	}

	@Test
	void rateLimiterBlocksBeyondLimit() {
		GroqClient.GroqRateLimiter limiter = new GroqClient.GroqRateLimiter();
		assertTrue(limiter.tryAcquire(2));
		assertTrue(limiter.tryAcquire(2));
		assertFalse(limiter.tryAcquire(2), "Third request in the same minute must be blocked");
		assertEquals(2, limiter.recentCount());
	}

	@Test
	void successfulChatReturnsContent() {
		GroqClient client = clientReturning(200, "{\"choices\":[{\"message\":{\"content\":\"Hello, friend.\"}}]}");
		GroqClient.Result result = client.chat(
			"https://example.invalid", "key", "m",
			List.of(new GroqClient.ChatMessage("user", "Hi")),
			0.7, 10, 5, 10
		).join();
		assertTrue(result instanceof GroqClient.Result.Success);
		assertEquals("Hello, friend.", ((GroqClient.Result.Success) result).content());
	}

	private static GroqClient clientReturning(int status, String body) {
		return new GroqClient(
			(url, key, requestBody, timeout) -> CompletableFuture.completedFuture(new GroqClient.HttpResult(status, body)),
			new GroqClient.GroqRateLimiter()
		);
	}
}
