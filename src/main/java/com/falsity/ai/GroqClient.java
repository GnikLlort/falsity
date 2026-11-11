package com.falsity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous client for Groq's OpenAI-compatible chat-completions API.
 *
 * <p>Requests run on background threads via {@link HttpClient#sendAsync};
 * the Minecraft server tick is never blocked waiting for Groq. Every
 * expected failure (no key, timeout, rate limit, bad response, offline) is
 * returned as a {@link Result} failure rather than thrown, so callers can
 * fall back to offline lines without try/catch spaghetti.
 *
 * <p>Every request also produces {@link Diagnostics}: the HTTP status, how long
 * it took, Groq's own request id, the finish reason and the token usage. That
 * is what makes "why did Falsity answer with a canned line?" answerable from
 * the server log. Diagnostics never contain the API key or any message text.
 *
 * <p>Reasoning models (such as {@code openai/gpt-oss-20b}) think before they
 * answer, and the thinking is billed against the completion budget. With a
 * small budget the model can spend all of it thinking and return an empty
 * reply, so for those models the request reserves extra headroom, asks for low
 * reasoning effort and leaves the reasoning text out of the response.
 *
 * <p>A connection that is dropped before any reply arrives is tried once more,
 * because that is what a stale pooled connection looks like; timeouts and HTTP
 * errors are never repeated.
 *
 * <p>The HTTP layer is behind a {@link Transport} interface so unit tests
 * can stub responses without a network or a real API key.
 */
public final class GroqClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(GroqClient.class);
	private static final Gson GSON = new Gson();

	/** Anything that looks like a Groq key or a bearer token, for redaction. */
	private static final Pattern SECRET = Pattern.compile("(gsk_[A-Za-z0-9_\\-]{4,}|Bearer\\s+\\S+)");

	/** Longest error detail kept from a Groq error body, in characters. */
	private static final int MAX_DETAIL_CHARS = 240;

	/** Largest completion budget an automatic retry may ask for. */
	private static final int MAX_RETRY_COMPLETION_TOKENS = 4096;

	/** How far down a chain of causes the client looks; guards against a cycle. */
	private static final int MAX_CAUSE_DEPTH = 12;

	/** A network failure that arrives this fast is worth one immediate second try. */
	private static final long QUICK_FAILURE_MILLIS = 2_000L;

	/** How this mod names itself to the server it talks to. */
	static final String USER_AGENT = "falsity-minecraft-mod (https://github.com/GnikLlort/falsity)";

	/** The "error code: 1010" line a web firewall answers with when it turns a request away. */
	private static final Pattern EDGE_ERROR_CODE = Pattern.compile("(?i)\\berror code:?\\s*(\\d{3,5})\\b");

	/** Why a request failed. Callers only need to know "use the fallback". */
	public enum Failure {
		DISABLED,
		NO_KEY,
		TIMEOUT,
		RATE_LIMITED,
		HTTP_ERROR,
		INVALID_RESPONSE,
		NETWORK_ERROR,
		/** Groq rejected the API key (HTTP 401), or the key cannot be sent at all. */
		UNAUTHORIZED,
		/** The configured model does not exist or has been retired. */
		MODEL_UNAVAILABLE,
		/**
		 * The request was refused (HTTP 403): by Groq because the key may not do
		 * this, or by something in front of Groq before the key was even looked at.
		 */
		FORBIDDEN,
		/** {@code groq.endpoint} is not a chat-completions address. */
		BAD_ENDPOINT
	}

	/** Outcome of one chat-completion request. */
	public sealed interface Result {
		record Success(String content) implements Result {
		}

		record Failed(Failure failure) implements Result {
		}
	}

	/** One chat message in the OpenAI shape. */
	public record ChatMessage(String role, String content) {
	}

	/** Minimal HTTP transport, stubbed in tests. */
	public interface Transport {
		CompletableFuture<HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout);
	}

	/**
	 * Minimal HTTP outcome: status code, raw body and the few response headers
	 * the client reads (lower-case names). Headers are optional.
	 */
	public record HttpResult(int statusCode, String body, Map<String, String> headers) {
		public HttpResult {
			headers = headers == null ? Map.of() : Map.copyOf(headers);
		}

		public HttpResult(int statusCode, String body) {
			this(statusCode, body, Map.of());
		}

		/** A response header by lower-case name, or an empty string. */
		public String header(String name) {
			String value = this.headers.get(name);
			return value == null ? "" : value;
		}
	}

	/**
	 * Everything one request needs. {@code reasoningEffort} and
	 * {@code reasoningHeadroomTokens} only apply to reasoning models; a blank
	 * effort disables the reasoning parameters altogether.
	 */
	public record Request(
		String endpoint,
		String apiKey,
		String model,
		List<ChatMessage> messages,
		double temperature,
		int maxTokens,
		int timeoutSeconds,
		int maxRequestsPerMinute,
		String reasoningEffort,
		int reasoningHeadroomTokens
	) {
		public Request {
			messages = messages == null ? List.of() : List.copyOf(messages);
			reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim().toLowerCase(Locale.ROOT);
			reasoningHeadroomTokens = Math.max(0, reasoningHeadroomTokens);
		}

		/**
		 * Whether a reply that was cut off before any text appeared is worth
		 * one more attempt with a larger budget. Only when the caller asked
		 * for reasoning handling; the original {@code chat} entry point never
		 * retries.
		 */
		boolean retryOnEmpty() {
			return !this.reasoningEffort.isEmpty();
		}
	}

	/**
	 * What happened to one request, safe to log in full.
	 *
	 * @param requestId       local id, so log lines of one request can be matched
	 * @param model           the model that was asked
	 * @param httpStatus      last HTTP status, or 0 when no response arrived
	 * @param durationMillis  wall time from first send to final outcome
	 * @param groqRequestId   Groq's id for the request, or empty
	 * @param finishReason    {@code stop}, {@code length}, ... or empty
	 * @param promptTokens    prompt tokens billed, or -1 when unknown
	 * @param completionTokens completion tokens billed, or -1 when unknown
	 * @param totalTokens     total tokens billed, or -1 when unknown
	 * @param attempts        HTTP attempts made (0 when nothing was sent)
	 * @param failure         why it failed, or {@code null} on success
	 * @param detail          short human-readable reason; never contains a secret
	 * @param retryAfterMillis how long Groq asked the client to wait, or 0
	 */
	public record Diagnostics(
		String requestId,
		String model,
		int httpStatus,
		long durationMillis,
		String groqRequestId,
		String finishReason,
		int promptTokens,
		int completionTokens,
		int totalTokens,
		int attempts,
		Failure failure,
		String detail,
		long retryAfterMillis
	) {
		public boolean success() {
			return this.failure == null;
		}

		/** One log-safe line describing the request. */
		public String summary() {
			StringBuilder builder = new StringBuilder();
			builder.append("request=").append(this.requestId);
			builder.append(" model=").append(this.model);
			builder.append(" status=").append(this.httpStatus == 0 ? "-" : String.valueOf(this.httpStatus));
			builder.append(" duration=").append(this.durationMillis).append("ms");
			builder.append(" attempts=").append(this.attempts);
			if (!this.finishReason.isEmpty()) {
				builder.append(" finish_reason=").append(this.finishReason);
			}
			if (this.totalTokens >= 0) {
				builder.append(" tokens=").append(this.promptTokens).append('+').append(this.completionTokens)
					.append('=').append(this.totalTokens);
			}
			if (!this.groqRequestId.isEmpty()) {
				builder.append(" groq_id=").append(this.groqRequestId);
			}
			if (this.failure != null) {
				builder.append(" failure=").append(this.failure);
			}
			if (this.retryAfterMillis > 0L) {
				builder.append(" retry_after=").append(this.retryAfterMillis).append("ms");
			}
			if (!this.detail.isEmpty()) {
				builder.append(" detail=\"").append(this.detail).append('"');
			}
			return builder.toString();
		}
	}

	/** A result together with its diagnostics. */
	public record Exchange(Result result, Diagnostics diagnostics) {
	}

	/** The parts of a Groq response body the client understands. */
	public record ParsedResponse(
		String content,
		String finishReason,
		String groqRequestId,
		int promptTokens,
		int completionTokens,
		int totalTokens,
		boolean hasChoices
	) {
	}

	/** Running totals, for the status command and the startup log. */
	public record Status(
		long requests,
		long successes,
		long failures,
		long lastSuccessEpochMillis,
		long lastFailureEpochMillis,
		Diagnostics lastSuccess,
		Diagnostics lastFailure
	) {
		/** Whether Groq has answered at least once since the server started. */
		public boolean verified() {
			return this.successes > 0L;
		}
	}

	private final Transport transport;
	private final GroqRateLimiter rateLimiter;
	private final AtomicLong requestCounter = new AtomicLong();
	private final Set<String> plainOnlyModels = ConcurrentHashMap.newKeySet();

	private final Object statusLock = new Object();
	private long requests;
	private long successes;
	private long failures;
	private long lastSuccessEpochMillis;
	private long lastFailureEpochMillis;
	private Diagnostics lastSuccess;
	private Diagnostics lastFailure;

	public GroqClient() {
		this(defaultTransport(), new GroqRateLimiter());
	}

	/** Visible for tests: injects the transport and rate limiter. */
	public GroqClient(Transport transport, GroqRateLimiter rateLimiter) {
		this.transport = transport;
		this.rateLimiter = rateLimiter;
	}

	/** The shared rate limiter, so cooldown tests can observe it. */
	public GroqRateLimiter rateLimiter() {
		return this.rateLimiter;
	}

	/** Running totals since the client was created. */
	public Status status() {
		synchronized (this.statusLock) {
			return new Status(
				this.requests, this.successes, this.failures,
				this.lastSuccessEpochMillis, this.lastFailureEpochMillis,
				this.lastSuccess, this.lastFailure
			);
		}
	}

	/**
	 * Sends one chat completion. Never blocks; the future completes on a
	 * background thread. Callers must schedule any game interaction back
	 * onto the server thread themselves.
	 *
	 * <p>This is the original entry point, kept for callers that only need the
	 * result. It sends the plain {@code max_tokens} request and never retries.
	 */
	public CompletableFuture<Result> chat(
		String endpoint,
		String apiKey,
		String model,
		List<ChatMessage> messages,
		double temperature,
		int maxTokens,
		int timeoutSeconds,
		int maxRequestsPerMinute
	) {
		Request request = new Request(endpoint, apiKey, model, messages, temperature, maxTokens, timeoutSeconds, maxRequestsPerMinute, "", 0);
		return exchange(request).thenApply(Exchange::result);
	}

	/**
	 * Sends one chat completion and reports what happened. Never blocks and
	 * never completes exceptionally.
	 *
	 * <p>Two automatic retries exist, each used at most once per request: if
	 * Groq rejects a request that carried reasoning settings (HTTP 400) it is
	 * repeated without them, and if the model uses its whole budget without
	 * producing any text, the request is repeated with a larger budget.
	 */
	public CompletableFuture<Exchange> exchange(Request request) {
		String requestId = "f" + Long.toString(this.requestCounter.incrementAndGet(), 36);
		String model = request.model() == null ? "" : request.model();
		long startNanos = System.nanoTime();

		if (request.apiKey() == null || request.apiKey().isBlank()) {
			return CompletableFuture.completedFuture(finish(failed(
				requestId, model, startNanos, 0, 0, Failure.NO_KEY, "no API key configured", 0L, null)));
		}
		if (!isSendableKey(request.apiKey())) {
			// Sending it would fail inside the HTTP client with a message that quotes the key.
			return CompletableFuture.completedFuture(finish(failed(
				requestId, model, startNanos, 0, 0, Failure.UNAUTHORIZED,
				"the API key contains a space, a line break or another character a key cannot have; paste it again",
				0L, null)));
		}
		String endpointProblem = endpointProblem(request.endpoint());
		if (!endpointProblem.isEmpty()) {
			return CompletableFuture.completedFuture(finish(failed(
				requestId, model, startNanos, 0, 0, Failure.BAD_ENDPOINT, endpointProblem, 0L, null)));
		}
		if (!this.rateLimiter.tryAcquire(request.maxRequestsPerMinute())) {
			String detail = "local limit of " + Math.max(1, request.maxRequestsPerMinute())
				+ " requests per minute reached (conversation.maxRequestsPerMinute)";
			return CompletableFuture.completedFuture(finish(failed(
				requestId, model, startNanos, 0, 0, Failure.RATE_LIMITED, detail,
				this.rateLimiter.millisUntilAvailable(request.maxRequestsPerMinute()), null)));
		}

		// A reasoning model needs room to think whether or not the reasoning
		// settings themselves are sent, so the budget depends on the model alone.
		boolean reasoningModel = usesReasoningParameters(model, request.reasoningEffort());
		boolean reasoning = reasoningModel && !this.plainOnlyModels.contains(model);
		int completionTokens = reasoningModel
			? request.maxTokens() + request.reasoningHeadroomTokens()
			: request.maxTokens();

		return attempt(request, requestId, startNanos, 1, reasoning, completionTokens, false, false, false)
			.exceptionally(error -> failed(
				requestId, model, startNanos, 0, 1, classify(error), describeError(error, request), 0L, null))
			.thenApply(this::finish);
	}

	private CompletableFuture<Exchange> attempt(
		Request request,
		String requestId,
		long startNanos,
		int attemptNumber,
		boolean reasoning,
		int completionTokens,
		boolean budgetRetried,
		boolean reasoningRejected,
		boolean networkRetried
	) {
		String model = request.model() == null ? "" : request.model();
		String body = reasoning
			? buildReasoningRequestJson(model, request.messages(), request.temperature(), completionTokens, request.reasoningEffort())
			: buildRequestJson(model, request.messages(), request.temperature(), completionTokens);
		Duration timeout = Duration.ofSeconds(Math.max(1, request.timeoutSeconds()));

		long attemptStartNanos = System.nanoTime();
		CompletableFuture<HttpResult> sent;
		try {
			sent = this.transport.send(request.endpoint().trim(), request.apiKey(), body, timeout);
		} catch (RuntimeException exception) {
			sent = CompletableFuture.failedFuture(exception);
		}
		if (sent == null) {
			sent = CompletableFuture.completedFuture(null);
		}

		return sent.handle((httpResult, error) -> {
			if (error != null) {
				Failure failure = classify(error);
				if (failure == Failure.NETWORK_ERROR && !networkRetried && connectionFailed(error)
					&& elapsedMillis(attemptStartNanos) < QUICK_FAILURE_MILLIS) {
					// A connection that fails at once is usually a stale one from the
					// pool, and the second try opens a fresh one. Slow failures are not
					// repeated: the player has waited long enough already.
					LOGGER.debug("Groq request {} lost its connection ({}); trying once more.",
						requestId, describeError(error, request));
					return attempt(request, requestId, startNanos, attemptNumber + 1, reasoning, completionTokens,
						budgetRetried, reasoningRejected, true);
				}
				return CompletableFuture.completedFuture(failed(
					requestId, model, startNanos, 0, attemptNumber, failure, describeError(error, request), 0L, null));
			}
			if (httpResult == null) {
				return CompletableFuture.completedFuture(failed(
					requestId, model, startNanos, 0, attemptNumber, Failure.NETWORK_ERROR, "no response", 0L, null));
			}

			int status = httpResult.statusCode();
			if (status == 400 && reasoning) {
				// Possibly the model (or an OpenAI-compatible proxy) does not accept
				// the reasoning settings. Try once without them, keeping the budget.
				LOGGER.info(
					"Groq rejected a request with reasoning settings for model {} ({}); retrying without them.",
					model, errorDetail(httpResult.body()));
				return attempt(request, requestId, startNanos, attemptNumber + 1, false, completionTokens, budgetRetried, true,
					networkRetried);
			}
			if (status < 200 || status >= 300) {
				return CompletableFuture.completedFuture(failed(
					requestId, model, startNanos, status, attemptNumber, classifyStatus(status, httpResult.body()),
					statusDetail(httpResult), retryAfterMillis(httpResult), null));
			}

			if (reasoningRejected) {
				// The plain request went through, so the reasoning settings were the
				// problem: stop sending them to this model.
				this.plainOnlyModels.add(model);
			}

			ParsedResponse parsed = parseResponse(httpResult.body());
			if (parsed.content().isEmpty()) {
				boolean ranOutThinking = "length".equals(parsed.finishReason());
				if (ranOutThinking && request.retryOnEmpty() && !budgetRetried && completionTokens < MAX_RETRY_COMPLETION_TOKENS) {
					int larger = Math.min(MAX_RETRY_COMPLETION_TOKENS, completionTokens * 2 + 256);
					LOGGER.info(
						"Groq model {} used all {} completion tokens without producing a reply; retrying once with {}.",
						model, completionTokens, larger);
					return attempt(request, requestId, startNanos, attemptNumber + 1, reasoning, larger, true, false,
						networkRetried);
				}
				String detail;
				if (!parsed.hasChoices()) {
					detail = "response had no choices";
				} else if (ranOutThinking) {
					detail = "empty reply: the model hit the completion-token limit before answering"
						+ " (raise groq.maxTokens or groq.reasoningHeadroomTokens)";
				} else {
					detail = "empty reply" + (parsed.finishReason().isEmpty() ? "" : " (finish_reason " + parsed.finishReason() + ")");
				}
				return CompletableFuture.completedFuture(failed(
					requestId, model, startNanos, status, attemptNumber, Failure.INVALID_RESPONSE, detail, 0L, parsed));
			}

			Diagnostics diagnostics = new Diagnostics(
				requestId, model, status, elapsedMillis(startNanos), parsed.groqRequestId(), parsed.finishReason(),
				parsed.promptTokens(), parsed.completionTokens(), parsed.totalTokens(), attemptNumber, null, "", 0L);
			return CompletableFuture.completedFuture(new Exchange(new Result.Success(parsed.content()), diagnostics));
		}).thenCompose(future -> future);
	}

	private Exchange finish(Exchange exchange) {
		Diagnostics diagnostics = exchange.diagnostics();
		synchronized (this.statusLock) {
			this.requests++;
			if (diagnostics.success()) {
				this.successes++;
				this.lastSuccess = diagnostics;
				this.lastSuccessEpochMillis = System.currentTimeMillis();
			} else {
				this.failures++;
				this.lastFailure = diagnostics;
				this.lastFailureEpochMillis = System.currentTimeMillis();
			}
		}
		LOGGER.debug("Groq {}", diagnostics.summary());
		return exchange;
	}

	private static Exchange failed(
		String requestId,
		String model,
		long startNanos,
		int status,
		int attempts,
		Failure failure,
		String detail,
		long retryAfterMillis,
		ParsedResponse parsed
	) {
		Diagnostics diagnostics = new Diagnostics(
			requestId, model, status, elapsedMillis(startNanos),
			parsed == null ? "" : parsed.groqRequestId(),
			parsed == null ? "" : parsed.finishReason(),
			parsed == null ? -1 : parsed.promptTokens(),
			parsed == null ? -1 : parsed.completionTokens(),
			parsed == null ? -1 : parsed.totalTokens(),
			attempts, failure, detail == null ? "" : detail, retryAfterMillis);
		return new Exchange(new Result.Failed(failure), diagnostics);
	}

	private static long elapsedMillis(long startNanos) {
		return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
	}

	/** Whether the key is made of visible ASCII only, the one form that can travel in a header. */
	static boolean isSendableKey(String apiKey) {
		if (apiKey == null || apiKey.isEmpty()) {
			return false;
		}
		for (int index = 0; index < apiKey.length(); index++) {
			char character = apiKey.charAt(index);
			if (character <= ' ' || character >= 0x7F) {
				return false;
			}
		}
		return true;
	}

	private static Failure classifyStatus(int status, String body) {
		if (status == 429) {
			return Failure.RATE_LIMITED;
		}
		if (status == 401) {
			return Failure.UNAUTHORIZED;
		}
		if (status == 403) {
			return Failure.FORBIDDEN;
		}
		String code = errorField(body, "code").toLowerCase(Locale.ROOT);
		if (code.contains("model_not_found") || code.contains("model_decommissioned")) {
			return Failure.MODEL_UNAVAILABLE;
		}
		if (status == 404) {
			// Groq answers 404 for a model it does not know and for an address it
			// does not know. Only the first is about groq.model.
			String message = errorField(body, "message").toLowerCase(Locale.ROOT);
			return code.contains("model") || message.contains("model") ? Failure.MODEL_UNAVAILABLE : Failure.BAD_ENDPOINT;
		}
		if ((status >= 300 && status < 400) || status == 405) {
			return Failure.BAD_ENDPOINT;
		}
		return Failure.HTTP_ERROR;
	}

	/**
	 * What an error response says, in a form that is safe to log: Groq's own
	 * code and message when the body is Groq's error JSON, otherwise only what
	 * can be said without quoting the body.
	 */
	private static String statusDetail(HttpResult httpResult) {
		String detail = errorDetail(httpResult.body());
		if (!detail.isEmpty()) {
			return detail;
		}
		int status = httpResult.statusCode();
		if (status >= 300 && status < 400) {
			String target = hostOf(httpResult.header("location"));
			return "the address answered with a redirect" + (target.isEmpty() ? "" : " to " + target);
		}
		return blockedDetail(httpResult.body());
	}

	/**
	 * Recognises the page a web firewall sends when it turns a request away
	 * before Groq sees it. Only a fixed phrase and the numeric code are kept.
	 */
	static String blockedDetail(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}
		String head = body.length() > 4096 ? body.substring(0, 4096) : body;
		boolean cloudflare = head.toLowerCase(Locale.ROOT).contains("cloudflare");
		Matcher code = EDGE_ERROR_CODE.matcher(head);
		if (code.find()) {
			return "turned away before reaching Groq (" + (cloudflare ? "Cloudflare " : "") + "error code " + code.group(1) + ")";
		}
		return cloudflare ? "turned away before reaching Groq (Cloudflare page)" : "";
	}

	/** Why {@code endpoint} cannot be used as written, or an empty string when it can. */
	static String endpointProblem(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return "groq.endpoint is empty";
		}
		URI uri;
		try {
			uri = new URI(endpoint.trim());
		} catch (URISyntaxException malformed) {
			return "groq.endpoint is not a valid address";
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) {
			return "groq.endpoint must start with https://";
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return "groq.endpoint has no host name";
		}
		return "";
	}

	/** The host (and port, when one is written) of an address, or an empty string. */
	static String hostOf(String address) {
		if (address == null || address.isBlank()) {
			return "";
		}
		try {
			URI uri = new URI(address.trim());
			if (uri.getHost() == null || uri.getHost().isBlank()) {
				return "";
			}
			return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
		} catch (URISyntaxException malformed) {
			return "";
		}
	}

	private static Failure classify(Throwable error) {
		if (inChain(error, "HttpConnectTimeoutException")) {
			// Nothing answered at all. That is a reachability problem, not a slow
			// model, and raising groq.timeoutSeconds would not help.
			return Failure.NETWORK_ERROR;
		}
		return inChain(error, "Timeout") ? Failure.TIMEOUT : Failure.NETWORK_ERROR;
	}

	/** Whether the failure came from the connection itself rather than from a programming error. */
	private static boolean connectionFailed(Throwable error) {
		Throwable cause = error;
		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (cause instanceof IOException) {
				return true;
			}
			cause = cause.getCause() == cause ? null : cause.getCause();
		}
		return false;
	}

	/** Whether any exception in the cause chain has {@code namePart} in its class name. */
	private static boolean inChain(Throwable error, String namePart) {
		Throwable cause = error;
		for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (cause.getClass().getSimpleName().contains(namePart)) {
				return true;
			}
			cause = cause.getCause() == cause ? null : cause.getCause();
		}
		return false;
	}

	private static Throwable rootCause(Throwable error) {
		Throwable cause = error;
		for (int depth = 0; depth < MAX_CAUSE_DEPTH && cause.getCause() != null && cause.getCause() != cause; depth++) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * A network failure in plain words, redacted and shortened for the log.
	 * The common ones are named for what they mean ("cannot find the host",
	 * "connection refused"); anything else keeps its exception class and message.
	 */
	private static String describeError(Throwable error, Request request) {
		String host = hostOf(request.endpoint());
		String where = host.isEmpty() ? "the endpoint" : host;
		if (inChain(error, "UnresolvedAddress") || inChain(error, "UnknownHost")) {
			return "cannot find " + where + " (the host name could not be looked up)";
		}
		if (inChain(error, "HttpConnectTimeoutException")) {
			return "could not connect to " + where + " in time";
		}
		if (inChain(error, "Timeout")) {
			return "no answer from " + where + " within " + Math.max(1, request.timeoutSeconds()) + " s";
		}

		Throwable cause = rootCause(error);
		String message = cause.getMessage();
		String text = cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
		if (inChain(error, "SSL") || inChain(error, "Certificate") || inChain(error, "CertPath")) {
			return sanitiseDetail("secure connection to " + where + " failed (" + text + ")");
		}
		if (inChain(error, "ConnectException")) {
			return message == null || message.isBlank()
				? "connection to " + where + " refused"
				: sanitiseDetail("could not connect to " + where + " (" + message + ")");
		}
		if (inChain(error, "EOFException") || inChain(error, "ClosedChannel")) {
			return "connection to " + where + " closed before a reply arrived";
		}
		return sanitiseDetail(text);
	}

	/**
	 * Whether the reasoning parameters should be sent for this model. True for
	 * OpenAI's open-weight reasoning models hosted by Groq, as long as an effort
	 * is configured.
	 */
	public static boolean usesReasoningParameters(String model, String reasoningEffort) {
		if (model == null || reasoningEffort == null || reasoningEffort.isBlank()) {
			return false;
		}
		return model.toLowerCase(Locale.ROOT).startsWith("openai/gpt-oss");
	}

	/** Builds the OpenAI-compatible request JSON. Pure and unit tested. */
	public static String buildRequestJson(String model, List<ChatMessage> messages, double temperature, int maxTokens) {
		JsonObject request = new JsonObject();
		request.addProperty("model", model);
		request.addProperty("temperature", temperature);
		request.addProperty("max_tokens", maxTokens);
		request.add("messages", messagesJson(messages));
		return GSON.toJson(request);
	}

	/**
	 * Builds the request for a reasoning model: the budget covers thinking and
	 * answer together, the effort is kept low, and the reasoning text is left
	 * out of the response so only the answer comes back.
	 */
	public static String buildReasoningRequestJson(
		String model,
		List<ChatMessage> messages,
		double temperature,
		int maxCompletionTokens,
		String reasoningEffort
	) {
		JsonObject request = new JsonObject();
		request.addProperty("model", model);
		request.addProperty("temperature", temperature);
		request.addProperty("max_completion_tokens", maxCompletionTokens);
		request.addProperty("reasoning_effort", reasoningEffort);
		request.addProperty("include_reasoning", false);
		request.add("messages", messagesJson(messages));
		return GSON.toJson(request);
	}

	private static JsonArray messagesJson(List<ChatMessage> messages) {
		JsonArray array = new JsonArray();
		if (messages != null) {
			for (ChatMessage message : messages) {
				JsonObject entry = new JsonObject();
				entry.addProperty("role", message.role());
				entry.addProperty("content", message.content());
				array.add(entry);
			}
		}
		return array;
	}

	/**
	 * Extracts {@code choices[0].message.content} from a Groq response.
	 * Returns empty for anything malformed instead of throwing.
	 */
	public static Optional<String> parseResponseContent(String json) {
		String content = parseResponse(json).content();
		return content.isEmpty() ? Optional.empty() : Optional.of(content);
	}

	/**
	 * Reads the reply, finish reason, Groq request id and token usage from a
	 * response body. Never throws: anything missing is reported as empty or -1.
	 */
	public static ParsedResponse parseResponse(String json) {
		String content = "";
		String finishReason = "";
		String groqRequestId = "";
		int promptTokens = -1;
		int completionTokens = -1;
		int totalTokens = -1;
		boolean hasChoices = false;

		if (json != null && !json.isBlank()) {
			try {
				JsonObject parsed = GSON.fromJson(json, JsonObject.class);
				if (parsed != null) {
					JsonObject usage = object(parsed.get("usage"));
					if (usage != null) {
						promptTokens = integer(usage.get("prompt_tokens"));
						completionTokens = integer(usage.get("completion_tokens"));
						totalTokens = integer(usage.get("total_tokens"));
					}
					JsonObject groq = object(parsed.get("x_groq"));
					if (groq != null) {
						groqRequestId = string(groq.get("id"));
					}
					if (groqRequestId.isEmpty()) {
						groqRequestId = string(parsed.get("id"));
					}

					JsonElement choicesElement = parsed.get("choices");
					if (choicesElement != null && choicesElement.isJsonArray() && !choicesElement.getAsJsonArray().isEmpty()) {
						hasChoices = true;
						JsonObject choice = object(choicesElement.getAsJsonArray().get(0));
						if (choice != null) {
							finishReason = string(choice.get("finish_reason"));
							JsonObject message = object(choice.get("message"));
							if (message != null) {
								content = contentText(message.get("content")).trim();
							}
						}
					}
				}
			} catch (RuntimeException malformed) {
				content = "";
			}
		}

		return new ParsedResponse(content, finishReason, groqRequestId, promptTokens, completionTokens, totalTokens, hasChoices);
	}

	/**
	 * A short, log-safe description of a Groq error body: its code or type and
	 * its message, with anything resembling a key removed. Bodies that are not
	 * Groq error JSON are never echoed.
	 */
	public static String errorDetail(String body) {
		String code = errorField(body, "code");
		String type = errorField(body, "type");
		String message = errorField(body, "message");
		StringBuilder builder = new StringBuilder();
		if (!code.isEmpty()) {
			builder.append(code);
		} else if (!type.isEmpty()) {
			builder.append(type);
		}
		if (!message.isEmpty()) {
			if (builder.length() > 0) {
				builder.append(": ");
			}
			builder.append(message);
		}
		return sanitiseDetail(builder.toString());
	}

	private static String errorField(String body, String field) {
		if (body == null || body.isBlank()) {
			return "";
		}
		try {
			JsonObject parsed = GSON.fromJson(body, JsonObject.class);
			JsonObject error = parsed == null ? null : object(parsed.get("error"));
			return error == null ? "" : string(error.get(field));
		} catch (RuntimeException malformed) {
			return "";
		}
	}

	private static String sanitiseDetail(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = SECRET.matcher(text).replaceAll("[redacted]").replaceAll("[\\r\\n\\t\"]+", " ").replaceAll("\\s+", " ").trim();
		if (cleaned.length() > MAX_DETAIL_CHARS) {
			cleaned = cleaned.substring(0, MAX_DETAIL_CHARS).trim() + "...";
		}
		return cleaned;
	}

	/** How long Groq asked the client to wait, from {@code retry-after}, or 0. */
	static long retryAfterMillis(HttpResult httpResult) {
		String header = httpResult.header("retry-after").trim();
		if (header.isEmpty()) {
			return 0L;
		}
		try {
			double seconds = Double.parseDouble(header);
			if (Double.isNaN(seconds) || seconds <= 0.0) {
				return 0L;
			}
			return (long) Math.min(600_000.0, Math.ceil(seconds * 1000.0));
		} catch (NumberFormatException notANumber) {
			return 0L;
		}
	}

	private static JsonObject object(JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	/**
	 * The reply text. Groq sends a plain string; some OpenAI-compatible servers
	 * send a list of parts instead, whose text parts are joined here.
	 */
	private static String contentText(JsonElement element) {
		if (element == null || !element.isJsonArray()) {
			return string(element);
		}
		StringBuilder builder = new StringBuilder();
		for (JsonElement part : element.getAsJsonArray()) {
			JsonObject partObject = object(part);
			builder.append(partObject == null ? string(part) : string(partObject.get("text")));
		}
		return builder.toString();
	}

	private static String string(JsonElement element) {
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return "";
		}
		String value = element.getAsString();
		return value == null ? "" : value;
	}

	private static int integer(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return -1;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException notANumber) {
			return -1;
		}
	}

	private static Transport defaultTransport() {
		// HTTP/1.1 on purpose: one small request at a time gains nothing from
		// HTTP/2, and proxies and HTTPS-scanning security software handle the
		// older protocol more reliably.
		HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

		return (url, apiKey, jsonBody, timeout) -> {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				// A name of its own instead of Java's stock client name: generic
				// client names are what a web firewall's default rules tend to turn
				// away, and this one says who is calling.
				.header("User-Agent", USER_AGENT)
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

			return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					Map<String, String> headers = new HashMap<>();
					for (String name : new String[] {"retry-after", "x-request-id", "location"}) {
						response.headers().firstValue(name).ifPresent(value -> headers.put(name, value));
					}
					return new HttpResult(response.statusCode(), response.body(), headers);
				})
				.exceptionally(error -> {
					// Re-throw so GroqClient.classify can see the real cause.
					throw new RuntimeException(error);
				});
		};
	}

	/** Sliding-window rate limiter: at most N requests per minute. */
	public static final class GroqRateLimiter {
		private final List<Long> requestTimes = new ArrayList<>();

		/** Returns true and records the request if under the limit. */
		public synchronized boolean tryAcquire(int maxRequestsPerMinute) {
			long now = System.currentTimeMillis();
			long windowStart = now - 60_000L;
			this.requestTimes.removeIf(time -> time < windowStart);
			if (this.requestTimes.size() >= Math.max(1, maxRequestsPerMinute)) {
				return false;
			}
			this.requestTimes.add(now);
			return true;
		}

		/**
		 * Milliseconds until a request would be accepted again; zero when one
		 * would be accepted now.
		 */
		public synchronized long millisUntilAvailable(int maxRequestsPerMinute) {
			long now = System.currentTimeMillis();
			long windowStart = now - 60_000L;
			this.requestTimes.removeIf(time -> time < windowStart);
			int limit = Math.max(1, maxRequestsPerMinute);
			if (this.requestTimes.size() < limit) {
				return 0L;
			}
			// The slot frees when the oldest request that still counts leaves the window.
			long oldestCounting = this.requestTimes.get(this.requestTimes.size() - limit);
			return Math.max(1L, oldestCounting + 60_000L - now);
		}

		/** Requests recorded in the current window. Visible for tests. */
		public synchronized int recentCount() {
			long windowStart = System.currentTimeMillis() - 60_000L;
			this.requestTimes.removeIf(time -> time < windowStart);
			return this.requestTimes.size();
		}
	}
}
