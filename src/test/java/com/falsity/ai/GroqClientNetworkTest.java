package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.GroqClient.Diagnostics;
import com.falsity.ai.GroqClient.Exchange;
import com.falsity.ai.GroqClient.Failure;
import com.falsity.ai.GroqClient.HttpResult;
import com.falsity.ai.GroqClient.Request;
import com.falsity.config.FalsityConfig;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * What the Groq client does when the request never gets an answer, or gets one
 * from the wrong place: which failures are tried once more, how each is named
 * for the server log, and how a wrong address or a refusal on the way is told
 * apart from a bad key or a missing model. The exception chains used here are
 * the ones the JDK's HTTP client really produces.
 */
class GroqClientNetworkTest {
	private static final String ENDPOINT = FalsityConfig.DEFAULT_GROQ_ENDPOINT;
	private static final String KEY = "gsk_abcdefghijklmnop1234567890";
	private static final String MODEL = "llama-3.3-70b-versatile";
	private static final String OK = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi!\"}}]}";

	/** A transport that plays its steps in order: fail with an exception, or answer. */
	private static final class Steps implements GroqClient.Transport {
		final List<String> urls = new ArrayList<>();
		private final Deque<Object> steps = new ArrayDeque<>();
		private Throwable always;

		Steps fail(Throwable error) {
			this.steps.add(error);
			return this;
		}

		Steps alwaysFail(Throwable error) {
			this.always = error;
			return this;
		}

		Steps answer(int status, String body) {
			this.steps.add(new HttpResult(status, body));
			return this;
		}

		Steps answer(int status, String body, Map<String, String> headers) {
			this.steps.add(new HttpResult(status, body, headers));
			return this;
		}

		@Override
		public CompletableFuture<HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout) {
			this.urls.add(url);
			Object next = this.steps.poll();
			if (next instanceof HttpResult result) {
				return CompletableFuture.completedFuture(result);
			}
			Throwable error = next instanceof Throwable scripted ? scripted : this.always;
			return CompletableFuture.failedFuture(error == null ? new IllegalStateException("the script ran out") : error);
		}
	}

	private static GroqClient client(GroqClient.Transport transport) {
		return new GroqClient(transport, new GroqClient.GroqRateLimiter());
	}

	private static Request request(String endpoint) {
		return new Request(
			endpoint, KEY, MODEL, List.of(new GroqClient.ChatMessage("user", "Hello there")), 0.7, 150, 15, 30, "", 0);
	}

	private static Diagnostics after(Steps steps) {
		return client(steps).exchange(request(ENDPOINT)).join().diagnostics();
	}

	private static String error(String code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\",\"code\":\"" + code + "\"}}";
	}

	/** The way the JDK reports a connection that could not be made: the reason sits underneath. */
	private static ConnectException connectFailure(String message, Throwable cause) {
		ConnectException exception = message == null ? new ConnectException() : new ConnectException(message);
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}

	private static IOException droppedConnection() {
		return new IOException("HTTP/1.1 header parser received no bytes", new EOFException("EOF reached while reading"));
	}

	@Test
	void aConnectionLostAtOnceIsTriedOnceMore() {
		Steps steps = new Steps().fail(new CompletionException(droppedConnection())).answer(200, OK);

		Exchange exchange = client(steps).exchange(request(ENDPOINT)).join();

		assertTrue(exchange.result() instanceof GroqClient.Result.Success, exchange.diagnostics().summary());
		assertEquals(2, exchange.diagnostics().attempts());
		assertEquals(2, steps.urls.size());
	}

	@Test
	void aConnectionLostTwiceIsReportedInsteadOfTriedForever() {
		Steps steps = new Steps().alwaysFail(droppedConnection());

		Diagnostics diagnostics = after(steps);

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure());
		assertEquals(2, steps.urls.size(), "one more try, not a loop");
		assertEquals(2, diagnostics.attempts());
		assertEquals("connection to api.groq.com closed before a reply arrived", diagnostics.detail());
	}

	@Test
	void aTimeoutIsNeverRepeated() {
		Steps steps = new Steps().fail(new CompletionException(new HttpTimeoutException("request timed out"))).answer(200, OK);

		Diagnostics diagnostics = after(steps);

		assertEquals(Failure.TIMEOUT, diagnostics.failure());
		assertEquals(1, steps.urls.size(), "the player has waited the full timeout once already");
		assertEquals("no answer from api.groq.com within 15 s", diagnostics.detail());
	}

	@Test
	void aFailureThatIsNotTheConnectionIsNotRepeated() {
		Steps steps = new Steps().fail(new IllegalStateException("boom")).answer(200, OK);

		Diagnostics diagnostics = after(steps);

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure());
		assertEquals(1, steps.urls.size());
		assertTrue(diagnostics.detail().contains("IllegalStateException"), diagnostics.detail());
	}

	@Test
	void theRetryKeepsTheRestOfTheRequestAsItWas() {
		List<String> bodies = new ArrayList<>();
		int[] calls = {0};
		GroqClient client = client((url, key, body, timeout) -> {
			bodies.add(body);
			return calls[0]++ == 0
				? CompletableFuture.failedFuture(droppedConnection())
				: CompletableFuture.completedFuture(new HttpResult(200, OK));
		});

		Exchange exchange = client.exchange(new Request(
			ENDPOINT, KEY, "openai/gpt-oss-20b", List.of(new GroqClient.ChatMessage("user", "Hello")), 0.7, 150, 15, 30, "low", 512)).join();

		assertTrue(exchange.result() instanceof GroqClient.Result.Success, exchange.diagnostics().summary());
		assertEquals(2, bodies.size());
		assertEquals(bodies.get(0), bodies.get(1), "same model, same budget, same reasoning settings");
		assertTrue(bodies.get(1).contains("\"max_completion_tokens\":662"), bodies.get(1));
	}

	@Test
	void aHostThatCannotBeFoundIsSaidInPlainWords() {
		Diagnostics unresolved = after(new Steps().alwaysFail(
			new CompletionException(connectFailure(null, new UnresolvedAddressException()))));
		assertEquals(Failure.NETWORK_ERROR, unresolved.failure());
		assertEquals("cannot find api.groq.com (the host name could not be looked up)", unresolved.detail());

		Diagnostics unknown = after(new Steps().alwaysFail(new UnknownHostException("api.groq.com")));
		assertEquals(Failure.NETWORK_ERROR, unknown.failure());
		assertEquals("cannot find api.groq.com (the host name could not be looked up)", unknown.detail());
	}

	@Test
	void aRefusedConnectionIsSaidInPlainWords() {
		Diagnostics refused = after(new Steps().alwaysFail(
			new CompletionException(connectFailure(null, new ClosedChannelException()))));
		assertEquals(Failure.NETWORK_ERROR, refused.failure());
		assertEquals("connection to api.groq.com refused", refused.detail());

		Diagnostics unreachable = after(new Steps().alwaysFail(connectFailure("Network is unreachable", null)));
		assertEquals(Failure.NETWORK_ERROR, unreachable.failure());
		assertEquals("could not connect to api.groq.com (Network is unreachable)", unreachable.detail());
	}

	@Test
	void aConnectionThatNeverOpensIsANetworkProblemNotASlowModel() {
		HttpConnectTimeoutException timedOut = new HttpConnectTimeoutException("HTTP connect timed out");
		timedOut.initCause(connectFailure("HTTP connect timed out", null));
		Steps steps = new Steps().alwaysFail(new CompletionException(timedOut));

		Diagnostics diagnostics = after(steps);

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure(),
			"raising groq.timeoutSeconds would not help when nothing answers at all");
		assertEquals("could not connect to api.groq.com in time", diagnostics.detail());
	}

	@Test
	void aBrokenSecureConnectionSaysSo() {
		Diagnostics diagnostics = after(new Steps().alwaysFail(new CompletionException(new SSLHandshakeException(
			"PKIX path building failed: unable to find valid certification path to requested target"))));

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure());
		assertTrue(diagnostics.detail().startsWith("secure connection to api.groq.com failed ("), diagnostics.detail());
		assertTrue(diagnostics.detail().contains("PKIX path building failed"), diagnostics.detail());
	}

	@Test
	void theHostInTheMessageIsTheConfiguredOne() {
		Diagnostics diagnostics = client(new Steps().alwaysFail(connectFailure(null, new ClosedChannelException())))
			.exchange(request("http://localhost:8080/v1/chat/completions")).join().diagnostics();

		assertEquals("connection to localhost:8080 refused", diagnostics.detail());
	}

	@Test
	void aNetworkFailureNeverShowsTheKey() {
		Diagnostics diagnostics = after(new Steps().alwaysFail(connectFailure("refused while sending Bearer " + KEY, null)));

		assertFalse(diagnostics.summary().contains(KEY), diagnostics.summary());
		assertTrue(diagnostics.detail().contains("[redacted]"), diagnostics.detail());
	}

	@Test
	void aRefusalIsNotBlamedOnTheKey() {
		Diagnostics byGroq = after(new Steps().answer(403, error(
			"model_permission_blocked_project", "The model is blocked at the project level")));
		assertEquals(Failure.FORBIDDEN, byGroq.failure());
		assertTrue(byGroq.detail().contains("model_permission_blocked_project"), byGroq.detail());
		assertTrue(byGroq.detail().contains("blocked at the project level"), byGroq.detail());

		Diagnostics byFirewall = after(new Steps().answer(403, "error code: 1010"));
		assertEquals(Failure.FORBIDDEN, byFirewall.failure());
		assertEquals("turned away before reaching Groq (error code 1010)", byFirewall.detail());

		Diagnostics byPage = after(new Steps().answer(403,
			"<html><title>Attention Required! | Cloudflare</title><body>Sorry, you have been blocked " + KEY + "</body></html>"));
		assertEquals(Failure.FORBIDDEN, byPage.failure());
		assertEquals("turned away before reaching Groq (Cloudflare page)", byPage.detail());
		assertFalse(byPage.summary().contains(KEY), byPage.summary());

		assertEquals(Failure.UNAUTHORIZED, after(new Steps().answer(401, error("invalid_api_key", "Invalid API Key"))).failure());
	}

	@Test
	void onlyTheCodeOfAFirewallPageIsKept() {
		assertEquals("", GroqClient.blockedDetail(null));
		assertEquals("", GroqClient.blockedDetail("   "));
		assertEquals("", GroqClient.blockedDetail("<html>Bad gateway</html>"));
		assertEquals("turned away before reaching Groq (error code 1020)", GroqClient.blockedDetail("Error code 1020"));
		assertEquals("turned away before reaching Groq (Cloudflare error code 1015)",
			GroqClient.blockedDetail("<p>You are being rate limited</p><span>Error code: 1015</span> cloudflare"));
		assertEquals("", GroqClient.blockedDetail("error code: 12"), "two digits are not such a code");
	}

	@Test
	void aWrongAddressIsToldApartFromAMissingModel() {
		Diagnostics wrongAddress = after(new Steps().answer(404, error(
			"unknown_url", "Unknown request URL: POST /openai/v1. Please check the URL for typos")));
		assertEquals(Failure.BAD_ENDPOINT, wrongAddress.failure());
		assertTrue(wrongAddress.detail().contains("Unknown request URL"), wrongAddress.detail());

		assertEquals(Failure.MODEL_UNAVAILABLE, after(new Steps().answer(404, error(
			"model_not_found", "The model `nope` does not exist or you do not have access to it."))).failure());
		assertEquals(Failure.MODEL_UNAVAILABLE, after(new Steps().answer(404, error(
			"not_found", "The model `nope` does not exist"))).failure(), "the message alone can say it is about the model");
		assertEquals(Failure.MODEL_UNAVAILABLE, after(new Steps().answer(400, error(
			"model_decommissioned", "The model has been decommissioned"))).failure());

		assertEquals(Failure.BAD_ENDPOINT, after(new Steps().answer(404, "{}")).failure());
		assertEquals(Failure.BAD_ENDPOINT, after(new Steps().answer(404, "<html>Not Found</html>")).failure());
		assertEquals(Failure.BAD_ENDPOINT, after(new Steps().answer(405, "")).failure());
		assertEquals(Failure.HTTP_ERROR, after(new Steps().answer(500, "")).failure());
		assertEquals(Failure.HTTP_ERROR, after(new Steps().answer(413, error("request_too_large", "Request too large"))).failure());
	}

	@Test
	void aRedirectIsAWrongAddressToo() {
		Diagnostics moved = after(new Steps().answer(301, "", Map.of("location", "https://api.groq.com/openai/v1/chat/completions")));
		assertEquals(Failure.BAD_ENDPOINT, moved.failure());
		assertEquals("the address answered with a redirect to api.groq.com", moved.detail());

		Diagnostics nowhere = after(new Steps().answer(302, "<html>moved</html>"));
		assertEquals(Failure.BAD_ENDPOINT, nowhere.failure());
		assertEquals("the address answered with a redirect", nowhere.detail());
	}

	@Test
	void anAddressThatCannotBeUsedNeverReachesTheNetwork() {
		String[] unusable = {
			null, "", "   ", "api.groq.com/openai/v1/chat/completions", "ftp://api.groq.com/openai", "https://",
			"http://exa mple.org/", "not an address", "file:///etc/passwd"
		};
		for (String endpoint : unusable) {
			Steps steps = new Steps().answer(200, OK);
			GroqClient client = client(steps);

			Diagnostics diagnostics = client.exchange(request(endpoint)).join().diagnostics();

			assertEquals(Failure.BAD_ENDPOINT, diagnostics.failure(), String.valueOf(endpoint));
			assertEquals(0, diagnostics.attempts(), String.valueOf(endpoint));
			assertTrue(diagnostics.detail().startsWith("groq.endpoint "), diagnostics.detail());
			assertTrue(steps.urls.isEmpty(), "nothing is sent to " + endpoint);
			assertEquals(0, client.rateLimiter().recentCount(), "and it does not use up the minute's allowance");
		}
	}

	@Test
	void anAddressThatCanBeUsedIsNotComplainedAbout() {
		assertEquals("", GroqClient.endpointProblem(ENDPOINT));
		assertEquals("", GroqClient.endpointProblem("http://localhost:8080/v1/chat/completions"));
		assertEquals("", GroqClient.endpointProblem("  " + ENDPOINT + "\n"));
		assertEquals("", GroqClient.endpointProblem("HTTPS://API.GROQ.COM/openai/v1/chat/completions"));
		assertEquals("groq.endpoint must start with https://", GroqClient.endpointProblem("api.groq.com/openai/v1/chat/completions"));
		assertEquals("groq.endpoint is empty", GroqClient.endpointProblem(" "));
	}

	@Test
	void spacesAroundTheAddressAreNotSent() {
		Steps steps = new Steps().answer(200, OK);

		Exchange exchange = client(steps).exchange(request("  " + ENDPOINT + " \n")).join();

		assertTrue(exchange.result() instanceof GroqClient.Result.Success, exchange.diagnostics().summary());
		assertEquals(ENDPOINT, steps.urls.get(0));
	}

	@Test
	void theHostOfAnAddressIsReadWithAWrittenPort() {
		assertEquals("api.groq.com", GroqClient.hostOf(ENDPOINT));
		assertEquals("127.0.0.1:8080", GroqClient.hostOf("http://127.0.0.1:8080/v1"));
		assertEquals("", GroqClient.hostOf(""));
		assertEquals("", GroqClient.hostOf(null));
		assertEquals("", GroqClient.hostOf("not an address"));
		assertEquals("", GroqClient.hostOf("/only/a/path"));
	}

	@Test
	void aReplyMadeOfPartsIsReadToo() {
		String parts = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":"
			+ "[{\"type\":\"text\",\"text\":\"Hello\"},{\"type\":\"text\",\"text\":\" there.\"}]}}]}";
		assertEquals("Hello there.", GroqClient.parseResponse(parts).content());

		String strings = "{\"choices\":[{\"message\":{\"content\":[\"One. \",\"Two.\"]}}]}";
		assertEquals("One. Two.", GroqClient.parseResponse(strings).content());

		String nothingToSay = "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"image\"}]}}]}";
		assertEquals("", GroqClient.parseResponse(nothingToSay).content());
		assertEquals(Failure.INVALID_RESPONSE, after(new Steps().answer(200, nothingToSay)).failure());

		String object = "{\"choices\":[{\"message\":{\"content\":{\"text\":\"not a shape anyone sends\"}}}]}";
		assertEquals("", GroqClient.parseResponse(object).content());
	}
}
