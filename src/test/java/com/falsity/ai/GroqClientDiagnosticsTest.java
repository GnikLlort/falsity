package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.GroqClient.Diagnostics;
import com.falsity.ai.GroqClient.Exchange;
import com.falsity.ai.GroqClient.Failure;
import com.falsity.ai.GroqClient.HttpResult;
import com.falsity.ai.GroqClient.Request;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * How the Groq client talks to a reasoning model, what it retries, and what it
 * reports when a request fails. No network: the transport is scripted.
 */
class GroqClientDiagnosticsTest {
	private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
	private static final String KEY = "gsk_abcdefghijklmnop1234567890";
	private static final String REASONING_MODEL = "openai/gpt-oss-20b";
	private static final String PLAIN_MODEL = "llama-3.3-70b-versatile";

	/** A transport that answers from a script and remembers what it was sent. */
	private static final class Script implements GroqClient.Transport {
		final List<String> bodies = new ArrayList<>();
		final List<String> keys = new ArrayList<>();
		private final Deque<HttpResult> responses = new ArrayDeque<>();

		Script then(int status, String body) {
			this.responses.add(new HttpResult(status, body));
			return this;
		}

		Script then(int status, String body, Map<String, String> headers) {
			this.responses.add(new HttpResult(status, body, headers));
			return this;
		}

		@Override
		public CompletableFuture<HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout) {
			this.bodies.add(jsonBody);
			this.keys.add(apiKey);
			HttpResult next = this.responses.poll();
			return CompletableFuture.completedFuture(next == null ? new HttpResult(500, "{}") : next);
		}
	}

	private static GroqClient client(GroqClient.Transport transport) {
		return new GroqClient(transport, new GroqClient.GroqRateLimiter());
	}

	private static Request request(String model, String effort, int maxTokens, int headroom) {
		return new Request(
			ENDPOINT, KEY, model,
			List.of(new GroqClient.ChatMessage("system", "You are Falsity."), new GroqClient.ChatMessage("user", "Hello there")),
			0.7, maxTokens, 15, 30, effort, headroom);
	}

	private static String reply(String content, String finishReason) {
		return "{\"id\":\"chatcmpl-1\",\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{\"role\":\"assistant\","
			+ "\"content\":\"" + content + "\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34,\"total_tokens\":46},"
			+ "\"x_groq\":{\"id\":\"req_123\"}}";
	}

	private static String error(String code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\",\"code\":\"" + code + "\"}}";
	}

	@Test
	void reasoningModelGetsRoomToThinkAndIsAskedToThinkBriefly() {
		Script script = new Script().then(200, reply("Hi!", "stop"));

		Exchange exchange = client(script).exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertTrue(exchange.result() instanceof GroqClient.Result.Success);
		String body = script.bodies.get(0);
		assertTrue(body.contains("\"max_completion_tokens\":662"), "150 for the answer plus 512 to think: " + body);
		assertTrue(body.contains("\"reasoning_effort\":\"low\""), body);
		assertTrue(body.contains("\"include_reasoning\":false"), body);
		assertFalse(body.contains("\"max_tokens\""), "the old limit would cut the model off mid-thought: " + body);
	}

	@Test
	void otherModelsGetThePlainRequest() {
		Script script = new Script().then(200, reply("Hi!", "stop"));

		client(script).exchange(request(PLAIN_MODEL, "low", 150, 512)).join();

		String body = script.bodies.get(0);
		assertTrue(body.contains("\"max_tokens\":150"), body);
		assertFalse(body.contains("reasoning"), body);
		assertFalse(body.contains("max_completion_tokens"), body);
	}

	@Test
	void aBlankEffortSendsNoReasoningSettings() {
		Script script = new Script().then(200, reply("Hi!", "stop"));

		client(script).exchange(request(REASONING_MODEL, "", 150, 512)).join();

		String body = script.bodies.get(0);
		assertTrue(body.contains("\"max_tokens\":150"), body);
		assertFalse(body.contains("reasoning"), body);
		assertTrue(GroqClient.usesReasoningParameters(REASONING_MODEL, "low"));
		assertTrue(GroqClient.usesReasoningParameters("OpenAI/GPT-OSS-120b", "medium"));
		assertFalse(GroqClient.usesReasoningParameters(REASONING_MODEL, " "));
		assertFalse(GroqClient.usesReasoningParameters(PLAIN_MODEL, "low"));
		assertFalse(GroqClient.usesReasoningParameters(null, "low"));
	}

	@Test
	void rejectedReasoningSettingsAreRetriedWithoutThemAndRemembered() {
		Script script = new Script()
			.then(400, error("invalid_request_error", "property 'reasoning_effort' is unsupported"))
			.then(200, reply("Hello!", "stop"))
			.then(200, reply("Hello again!", "stop"));
		GroqClient client = client(script);

		Exchange first = client.exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertEquals("Hello!", ((GroqClient.Result.Success) first.result()).content());
		assertEquals(2, first.diagnostics().attempts());
		assertTrue(script.bodies.get(0).contains("reasoning_effort"));
		assertFalse(script.bodies.get(1).contains("reasoning"), script.bodies.get(1));
		assertTrue(script.bodies.get(1).contains("\"max_tokens\":662"),
			"the model still thinks, so it keeps its headroom: " + script.bodies.get(1));

		Exchange second = client.exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertEquals(1, second.diagnostics().attempts(), "the client remembers what this model accepts");
		assertFalse(script.bodies.get(2).contains("reasoning"), script.bodies.get(2));
	}

	@Test
	void aBadRequestForAnotherReasonIsReportedAndNotBlamedOnReasoning() {
		Script script = new Script()
			.then(400, error("context_length_exceeded", "too many tokens"))
			.then(400, error("context_length_exceeded", "too many tokens"))
			.then(200, reply("Hi!", "stop"));
		GroqClient client = client(script);

		Exchange failed = client.exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertEquals(Failure.HTTP_ERROR, failed.diagnostics().failure());
		assertEquals(400, failed.diagnostics().httpStatus());
		assertTrue(failed.diagnostics().detail().contains("context_length_exceeded"), failed.diagnostics().detail());

		client.exchange(request(REASONING_MODEL, "low", 150, 512)).join();
		assertTrue(script.bodies.get(2).contains("reasoning_effort"),
			"the reasoning settings were not the problem, so they are still sent");
	}

	@Test
	void anAnswerLostToThinkingIsAskedForAgainWithMoreRoom() {
		Script script = new Script()
			.then(200, reply("", "length"))
			.then(200, reply("Now I can answer.", "stop"));

		Exchange exchange = client(script).exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertEquals("Now I can answer.", ((GroqClient.Result.Success) exchange.result()).content());
		assertEquals(2, exchange.diagnostics().attempts());
		assertTrue(script.bodies.get(1).contains("\"max_completion_tokens\":1580"), script.bodies.get(1));
	}

	@Test
	void anAnswerLostTwiceIsReportedWithWhatToChange() {
		Script script = new Script()
			.then(200, reply("", "length"))
			.then(200, reply("", "length"))
			.then(200, reply("never asked for", "stop"));

		Exchange exchange = client(script).exchange(request(REASONING_MODEL, "low", 150, 512)).join();

		assertEquals(Failure.INVALID_RESPONSE, exchange.diagnostics().failure());
		assertEquals(2, script.bodies.size(), "one retry, not a loop");
		assertEquals("length", exchange.diagnostics().finishReason());
		assertTrue(exchange.diagnostics().detail().contains("groq.maxTokens"), exchange.diagnostics().detail());
		assertEquals(46, exchange.diagnostics().totalTokens(), "the tokens that were spent are still reported");
	}

	@Test
	void theOriginalEntryPointNeverRetries() {
		Script script = new Script().then(200, reply("", "length")).then(200, reply("late", "stop"));

		GroqClient.Result result = client(script)
			.chat(ENDPOINT, KEY, REASONING_MODEL, List.of(new GroqClient.ChatMessage("user", "Hi")), 0.7, 150, 15, 10)
			.join();

		assertTrue(result instanceof GroqClient.Result.Failed);
		assertEquals(1, script.bodies.size());
		assertTrue(script.bodies.get(0).contains("\"max_tokens\":150"));
	}

	@Test
	void aRejectedKeyAndAMissingModelAreNamed() {
		assertEquals(Failure.UNAUTHORIZED, failureFor(401, error("invalid_api_key", "Invalid API Key")));
		assertEquals(Failure.UNAUTHORIZED, failureFor(403, "{}"));
		assertEquals(Failure.MODEL_UNAVAILABLE, failureFor(404, error("model_not_found", "The model does not exist")));
		assertEquals(Failure.MODEL_UNAVAILABLE, failureFor(400, error("model_decommissioned", "The model has been decommissioned")));
		assertEquals(Failure.RATE_LIMITED, failureFor(429, "{}"));
		assertEquals(Failure.HTTP_ERROR, failureFor(503, "<html>bad gateway</html>"));
	}

	private static Failure failureFor(int status, String body) {
		Script script = new Script().then(status, body);
		return client(script).exchange(request(PLAIN_MODEL, "low", 150, 512)).join().diagnostics().failure();
	}

	@Test
	void aRateLimitSaysHowLongToWait() {
		assertEquals(2000L, retryAfter("2"));
		assertEquals(1500L, retryAfter("1.5"));
		assertEquals(0L, retryAfter("soon"));
		assertEquals(0L, retryAfter("-3"));
		assertEquals(600_000L, retryAfter("86400"), "an absurd wait is capped");

		Script script = new Script().then(429, error("rate_limit_exceeded", "Rate limit reached"), Map.of("retry-after", "2"));
		Diagnostics diagnostics = client(script).exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics();
		assertTrue(diagnostics.summary().contains("retry_after=2000ms"), diagnostics.summary());
		assertTrue(diagnostics.summary().contains("failure=RATE_LIMITED"), diagnostics.summary());
	}

	private static long retryAfter(String header) {
		Script script = new Script().then(429, "{}", Map.of("retry-after", header));
		return client(script).exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics().retryAfterMillis();
	}

	@Test
	void theLocalLimitIsReportedWithoutCallingGroq() {
		Script script = new Script().then(200, reply("Hi!", "stop"));
		GroqClient client = client(script);
		Request once = new Request(ENDPOINT, KEY, PLAIN_MODEL, List.of(new GroqClient.ChatMessage("user", "Hi")), 0.7, 150, 15, 1, "low", 0);

		assertTrue(client.exchange(once).join().diagnostics().success());
		Diagnostics refused = client.exchange(once).join().diagnostics();

		assertEquals(Failure.RATE_LIMITED, refused.failure());
		assertEquals(0, refused.httpStatus());
		assertEquals(0, refused.attempts());
		assertEquals(1, script.bodies.size(), "the refused request never left the server");
		assertTrue(refused.detail().contains("conversation.maxRequestsPerMinute"), refused.detail());
		assertTrue(refused.retryAfterMillis() > 0L && refused.retryAfterMillis() <= 60_000L,
			"the wait until the window frees: " + refused.retryAfterMillis());
	}

	@Test
	void theRateLimiterKnowsWhenASlotFrees() {
		GroqClient.GroqRateLimiter limiter = new GroqClient.GroqRateLimiter();

		assertEquals(0L, limiter.millisUntilAvailable(2));
		assertTrue(limiter.tryAcquire(2));
		assertEquals(0L, limiter.millisUntilAvailable(2));
		assertTrue(limiter.tryAcquire(2));
		long wait = limiter.millisUntilAvailable(2);
		assertTrue(wait > 0L && wait <= 60_000L, "wait " + wait);
		assertFalse(limiter.tryAcquire(2));
		assertEquals(0L, limiter.millisUntilAvailable(3), "a higher limit has room");
	}

	@Test
	void nothingLoggedContainsTheKeyOrTheConversation() {
		Script script = new Script().then(401, error("invalid_api_key", "Invalid API Key: " + KEY + " (Bearer " + KEY + ")"));

		Diagnostics diagnostics = client(script).exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics();

		String summary = diagnostics.summary();
		assertFalse(summary.contains(KEY), summary);
		assertFalse(summary.contains("gsk_"), summary);
		assertTrue(summary.contains("[redacted]"), summary);
		assertTrue(summary.contains("invalid_api_key"), summary);
		assertFalse(summary.contains("Hello there"), "player messages are never part of the diagnostics");
		assertFalse(summary.contains("You are Falsity"), summary);
		assertEquals(KEY, script.keys.get(0), "the key itself is still sent to Groq");
	}

	@Test
	void anExceptionMessageIsRedactedToo() {
		GroqClient client = client((url, key, body, timeout) ->
			CompletableFuture.failedFuture(new java.io.IOException("connection reset while sending Bearer " + KEY)));

		Diagnostics diagnostics = client.exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics();

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure());
		assertFalse(diagnostics.summary().contains(KEY), diagnostics.summary());
		assertTrue(diagnostics.detail().contains("IOException"), diagnostics.detail());
	}

	@Test
	void aBodyThatIsNotAGroqErrorIsNeverEchoed() {
		Script script = new Script().then(502, "<html><body>Bad gateway from proxy.example " + KEY + "</body></html>");

		Diagnostics diagnostics = client(script).exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics();

		assertEquals("", diagnostics.detail());
		assertFalse(diagnostics.summary().contains("html"), diagnostics.summary());
		assertTrue(diagnostics.summary().contains("status=502"), diagnostics.summary());
	}

	@Test
	void aSuccessIsSummarisedWithTimingTokensAndIds() {
		Script script = new Script().then(200, reply("Hi!", "stop"));

		Diagnostics diagnostics = client(script).exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics();

		assertTrue(diagnostics.success());
		assertNull(diagnostics.failure());
		String summary = diagnostics.summary();
		assertTrue(summary.contains("model=" + PLAIN_MODEL), summary);
		assertTrue(summary.contains("status=200"), summary);
		assertTrue(summary.contains("finish_reason=stop"), summary);
		assertTrue(summary.contains("tokens=12+34=46"), summary);
		assertTrue(summary.contains("groq_id=req_123"), summary);
		assertTrue(summary.contains("attempts=1"), summary);
		assertTrue(summary.contains("duration="), summary);
		assertFalse(summary.contains("failure="), summary);
	}

	@Test
	void statusCountsRequestsAndKeepsTheLastOfEachKind() {
		Script script = new Script()
			.then(401, error("invalid_api_key", "Invalid API Key"))
			.then(200, reply("Hi!", "stop"));
		GroqClient client = client(script);

		assertFalse(client.status().verified());
		assertEquals(0L, client.status().requests());

		client.exchange(request(PLAIN_MODEL, "low", 150, 0)).join();
		assertFalse(client.status().verified(), "a failure proves nothing");
		assertEquals(Failure.UNAUTHORIZED, client.status().lastFailure().failure());
		assertNull(client.status().lastSuccess());

		client.exchange(request(PLAIN_MODEL, "low", 150, 0)).join();
		GroqClient.Status status = client.status();
		assertTrue(status.verified());
		assertEquals(2L, status.requests());
		assertEquals(1L, status.successes());
		assertEquals(1L, status.failures());
		assertNotNull(status.lastSuccess());
		assertTrue(status.lastSuccessEpochMillis() > 0L);
	}

	@Test
	void aTransportThatMisbehavesIsANetworkErrorNotACrash() {
		GroqClient throwing = client((url, key, body, timeout) -> {
			throw new IllegalStateException("boom");
		});
		GroqClient silent = client((url, key, body, timeout) -> null);
		GroqClient nothing = client((url, key, body, timeout) -> CompletableFuture.completedFuture(null));

		assertEquals(Failure.NETWORK_ERROR, throwing.exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics().failure());
		assertEquals(Failure.NETWORK_ERROR, silent.exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics().failure());
		assertEquals(Failure.NETWORK_ERROR, nothing.exchange(request(PLAIN_MODEL, "low", 150, 0)).join().diagnostics().failure());
	}

	@Test
	void parsesTheUsefulPartsOfAResponse() {
		GroqClient.ParsedResponse parsed = GroqClient.parseResponse(reply("Hi!", "stop"));
		assertEquals("Hi!", parsed.content());
		assertEquals("stop", parsed.finishReason());
		assertEquals("req_123", parsed.groqRequestId());
		assertEquals(12, parsed.promptTokens());
		assertTrue(parsed.hasChoices());

		GroqClient.ParsedResponse empty = GroqClient.parseResponse("not json at all");
		assertEquals("", empty.content());
		assertEquals(-1, empty.totalTokens());
		assertFalse(empty.hasChoices());

		assertEquals("chatcmpl-9", GroqClient.parseResponse("{\"id\":\"chatcmpl-9\",\"choices\":[]}").groqRequestId());
	}
}
