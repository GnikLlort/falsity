package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.ApiKeyProvider.Located;
import com.falsity.ai.ApiKeyProvider.Source;
import com.falsity.ai.GroqClient.Diagnostics;
import com.falsity.ai.GroqClient.Exchange;
import com.falsity.ai.GroqClient.Failure;
import com.falsity.ai.GroqClient.HttpResult;
import com.falsity.ai.GroqClient.Request;
import com.falsity.ai.GroqClient.Status;
import com.falsity.config.FalsityConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * What {@code /falsity ai status} and {@code /falsity ai test} say. They exist
 * so nobody has to guess why Falsity is not talking: the report must be
 * truthful about what is known, name the next step, and never show the key.
 */
class AiStatusReportTest {
	private static final String KEY = "gsk_abcdefghijklmnop1234567890";
	private static final String SECRETS_PATH = "/srv/minecraft/secrets.json";
	private static final Located FOUND = new Located(KEY, Source.SECRETS_FILE, "", SECRETS_PATH);
	private static final Located MISSING = new Located("", Source.NONE, "", SECRETS_PATH);
	private static final Status FRESH = new Status(0L, 0L, 0L, 0L, 0L, null, null);

	private static Request request() {
		return new Request(
			"https://api.groq.com/openai/v1/chat/completions", KEY, "openai/gpt-oss-20b",
			List.of(new GroqClient.ChatMessage("user", "Hello")), 0.7, 150, 15, 30, "low", 512);
	}

	/** One real exchange against a scripted response, for genuine diagnostics. */
	private static Exchange exchange(int status, String body) {
		GroqClient client = new GroqClient(
			(url, apiKey, json, timeout) -> CompletableFuture.completedFuture(new HttpResult(status, body)),
			new GroqClient.GroqRateLimiter());
		return client.exchange(request()).join();
	}

	private static String reply(String content) {
		return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}],"
			+ "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34,\"total_tokens\":46}}";
	}

	private static String error(String code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\",\"code\":\"" + code + "\"}}";
	}

	private static Diagnostics success() {
		return exchange(200, reply("Hi!")).diagnostics();
	}

	private static Diagnostics failure(int status, String body) {
		return exchange(status, body).diagnostics();
	}

	@Test
	void offMeansOff() {
		FalsityConfig config = new FalsityConfig();
		config.groq.enabled = false;

		String line = AiStatusReport.stateLine(config, FOUND, FRESH);

		assertTrue(line.startsWith("Groq: OFF"), line);
		assertTrue(line.contains("groq.enabled"), "it names the setting to change: " + line);
	}

	@Test
	void noKeyIsSaidBeforeAnythingElse() {
		String line = AiStatusReport.stateLine(new FalsityConfig(), MISSING, FRESH);

		assertTrue(line.startsWith("Groq: NO API KEY"), line);
		assertTrue(line.contains("offline lines"), line);
	}

	@Test
	void aKeyAloneIsNotCalledWorking() {
		String line = AiStatusReport.stateLine(new FalsityConfig(), FOUND, FRESH);

		assertTrue(line.contains("not verified"), line);
		assertTrue(line.contains("/falsity ai test"), "it says how to find out: " + line);
		assertFalse(line.contains("WORKING"), line);
		assertFalse(line.toLowerCase().contains("connected"), line);
	}

	@Test
	void onlyFailuresMeansNotWorking() {
		Status status = new Status(3L, 0L, 3L, 0L, 5_000L, null, failure(401, error("invalid_api_key", "Invalid API Key")));

		String line = AiStatusReport.stateLine(new FalsityConfig(), FOUND, status);

		assertTrue(line.startsWith("Groq: NOT WORKING"), line);
	}

	@Test
	void anAnswerMeansWorking() {
		Status status = new Status(1L, 1L, 0L, 5_000L, 0L, success(), null);

		assertTrue(AiStatusReport.stateLine(new FalsityConfig(), FOUND, status).startsWith("Groq: WORKING"));
	}

	@Test
	void aFailureAfterASuccessIsNotHidden() {
		Diagnostics failed = failure(429, error("rate_limit_exceeded", "Rate limit reached"));
		Status failedLast = new Status(2L, 1L, 1L, 5_000L, 9_000L, success(), failed);
		Status succeededLast = new Status(2L, 1L, 1L, 9_000L, 5_000L, success(), failed);

		String line = AiStatusReport.stateLine(new FalsityConfig(), FOUND, failedLast);
		assertTrue(line.contains("most recent request failed"), line);
		assertFalse(line.contains("WORKING"), line);
		assertTrue(AiStatusReport.stateLine(new FalsityConfig(), FOUND, succeededLast).startsWith("Groq: WORKING"));
	}

	@Test
	void theKeyIsDescribedButNeverShown() {
		String line = AiStatusReport.keyLine(FOUND);

		assertTrue(line.contains("secrets.json"), line);
		assertTrue(line.contains(KEY.length() + " characters"), line);
		assertFalse(line.contains(KEY), line);
		assertFalse(line.contains("gsk_"), "not even its beginning: " + line);
	}

	@Test
	void aMissingKeyLineSaysExactlyWhereToPutIt() {
		String line = AiStatusReport.keyLine(MISSING);

		assertTrue(line.startsWith("API key: not found"), line);
		assertTrue(line.contains("GROQ_API_KEY"), line);
		assertTrue(line.contains(SECRETS_PATH), "the real folder, not just 'the game directory': " + line);
		assertTrue(line.contains("groq.apiKey"), line);
		assertFalse(line.contains("Note:"), line);
	}

	@Test
	void aMissingKeyLineExplainsWhatWasFoundInstead() {
		Located placeholder = new Located("", Source.NONE, "secrets.json still holds the placeholder text; replace it with your key", SECRETS_PATH);

		String line = AiStatusReport.keyLine(placeholder);

		assertTrue(line.contains("Note: secrets.json still holds the placeholder text"), line);
		assertTrue(line.endsWith("."), line);
	}

	@Test
	void withoutAGameDirectoryTheLineStillMakesSense() {
		String line = AiStatusReport.keyLine(new Located("", Source.NONE, "", ""));

		assertTrue(line.contains("secrets.json in the game directory"), line);
	}

	@Test
	void theReportListsWhatAnOperatorNeeds() {
		FalsityConfig config = new FalsityConfig();
		Diagnostics failed = failure(401, error("invalid_api_key", "Invalid API Key"));
		Status status = new Status(4L, 1L, 3L, 100_000L, 160_000L, success(), failed);

		List<String> lines = AiStatusReport.lines(config, FOUND, status, 2, 165_000L);
		String report = String.join("\n", lines);

		assertEquals("--- Falsity AI status ---", lines.get(0));
		assertTrue(lines.get(1).startsWith("Groq: "), lines.get(1));
		assertTrue(report.contains("Model: openai/gpt-oss-20b (max tokens 150 + 512 for reasoning at effort low, timeout 15 s, history 6 turns)"), report);
		assertTrue(report.contains("Pacing: cooldown 3 s, at most 10 requests a minute, 2 waiting"), report);
		assertTrue(report.contains("This session: 4 requests, 1 answered, 3 failed"), report);
		assertTrue(report.contains("Last success 65 s ago: "), report);
		assertTrue(report.contains("Last failure 5 s ago: "), report);
		assertTrue(report.contains("status=401"), report);
		assertTrue(report.contains("failure=UNAUTHORIZED"), report);
		assertTrue(report.contains("  -> " + AiStatusReport.adviceFor(Failure.UNAUTHORIZED)), report);
		assertFalse(report.contains(KEY), "the key appears nowhere in the report");
	}

	@Test
	void aModelWithoutReasoningShowsNoReasoningBudget() {
		FalsityConfig config = new FalsityConfig();
		config.groq.model = "llama-3.3-70b-versatile";

		String report = String.join("\n", AiStatusReport.lines(config, FOUND, FRESH, 0, 0L));

		assertTrue(report.contains("Model: llama-3.3-70b-versatile (max tokens 150, timeout 15 s, history 6 turns)"), report);
		assertFalse(report.contains("Last success"), report);
		assertFalse(report.contains("Last failure"), report);
	}

	@Test
	void timesAreReadable() {
		Status status = new Status(1L, 1L, 0L, 0L, 0L, success(), null);

		assertTrue(String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, 89_000L)).contains("Last success 89 s ago"));
		assertTrue(String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, 90_000L)).contains("Last success 1 min ago"));
		assertTrue(String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, 45L * 60_000L)).contains("Last success 45 min ago"));
		assertTrue(String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, 3L * 3_600_000L)).contains("Last success 3 h ago"));
		assertTrue(String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, -5_000L)).contains("Last success 0 s ago"),
			"a clock that went backwards does not print a negative time");
	}

	@Test
	void aSuccessfulTestQuotesTheReply() {
		String result = AiStatusReport.testResult(exchange(200, reply("Hi! I am here.")), null);

		assertTrue(result.startsWith("Groq works. It answered \"Hi! I am here.\""), result);
		assertTrue(result.contains("status=200"), result);
		assertTrue(result.contains("tokens=12+34=46"), result);
	}

	@Test
	void aSuccessfulTestShowsWordsOnly() {
		String thinking = AiStatusReport.testResult(exchange(200, reply("<think>what do I say</think>Hello! [action:greet]")), null);
		assertTrue(thinking.contains("It answered \"Hello!\""), thinking);
		assertFalse(thinking.contains("think"), thinking);
		assertFalse(thinking.contains("action"), thinking);

		String longReply = AiStatusReport.testResult(exchange(200, reply("word ".repeat(50).trim())), null);
		assertTrue(longReply.contains("...\""), "a long reply is shortened: " + longReply);
	}

	@Test
	void aFailedTestSaysWhatHappenedAndWhatToDo() {
		String unauthorized = AiStatusReport.testResult(exchange(401, error("invalid_api_key", "Invalid API Key")), null);
		assertTrue(unauthorized.startsWith("Groq test failed: "), unauthorized);
		assertTrue(unauthorized.contains("status=401"), unauthorized);
		assertTrue(unauthorized.contains("Invalid API Key"), "Groq's own message is passed on: " + unauthorized);
		assertTrue(unauthorized.contains(" -> " + AiStatusReport.adviceFor(Failure.UNAUTHORIZED)), unauthorized);

		String gone = AiStatusReport.testResult(exchange(404, error("model_not_found", "The model does not exist")), null);
		assertTrue(gone.contains(" -> " + AiStatusReport.adviceFor(Failure.MODEL_UNAVAILABLE)), gone);
		assertTrue(gone.contains("groq.model"), gone);
	}

	@Test
	void aTestThatNeverRanSaysSoWithoutDetails() {
		String withError = AiStatusReport.testResult(null, new IllegalStateException("Bearer " + KEY));
		assertTrue(withError.startsWith("Groq test failed before a request could be made"), withError);
		assertTrue(withError.contains("IllegalStateException"), withError);
		assertFalse(withError.contains(KEY), "an exception message is never trusted to be free of the key");

		String nothing = AiStatusReport.testResult(null, null);
		assertTrue(nothing.startsWith("Groq test failed before a request could be made"), nothing);
		assertFalse(nothing.contains("null"), nothing);
	}

	@Test
	void everyFailureComesWithAdvice() {
		for (Failure failure : Failure.values()) {
			String advice = AiStatusReport.adviceFor(failure);
			assertFalse(advice.isBlank(), "no advice for " + failure);
			assertTrue(advice.endsWith("."), advice);
		}
		assertEquals("", AiStatusReport.adviceFor(null));
	}

	@Test
	void adviceNamesTheSettingToChange() {
		assertTrue(AiStatusReport.adviceFor(Failure.UNAUTHORIZED).contains("console.groq.com/keys"));
		assertTrue(AiStatusReport.adviceFor(Failure.MODEL_UNAVAILABLE).contains("groq.model"));
		assertTrue(AiStatusReport.adviceFor(Failure.RATE_LIMITED).contains("conversation.maxRequestsPerMinute"));
		assertTrue(AiStatusReport.adviceFor(Failure.TIMEOUT).contains("groq.timeoutSeconds"));
		assertTrue(AiStatusReport.adviceFor(Failure.INVALID_RESPONSE).contains("groq.reasoningHeadroomTokens"));
		assertTrue(AiStatusReport.adviceFor(Failure.NETWORK_ERROR).contains("firewall"));
	}

	@Test
	void aKeyEchoedByAnErrorIsNotRepeated() {
		Diagnostics failed = failure(401, error("invalid_api_key", "Invalid API Key: " + KEY));
		Status status = new Status(1L, 0L, 1L, 0L, 1_000L, null, failed);

		String report = String.join("\n", AiStatusReport.lines(new FalsityConfig(), FOUND, status, 0, 2_000L));

		assertFalse(report.contains(KEY), report);
		assertTrue(report.contains("[redacted]"), report);
	}
}
