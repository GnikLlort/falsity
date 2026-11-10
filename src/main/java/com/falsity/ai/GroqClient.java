package com.falsity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 * <p>The HTTP layer is behind a {@link Transport} interface so unit tests
 * can stub responses without a network or a real API key.
 */
public final class GroqClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(GroqClient.class);
	private static final Gson GSON = new Gson();

	/** Why a request failed. Callers only need to know "use the fallback". */
	public enum Failure {
		DISABLED,
		NO_KEY,
		TIMEOUT,
		RATE_LIMITED,
		HTTP_ERROR,
		INVALID_RESPONSE,
		NETWORK_ERROR
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

	/** Minimal HTTP outcome: status code plus raw body. */
	public record HttpResult(int statusCode, String body) {
	}

	private final Transport transport;
	private final GroqRateLimiter rateLimiter;

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

	/**
	 * Sends one chat completion. Never blocks; the future completes on a
	 * background thread. Callers must schedule any game interaction back
	 * onto the server thread themselves.
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
		if (apiKey == null || apiKey.isBlank()) {
			return CompletableFuture.completedFuture(new Result.Failed(Failure.NO_KEY));
		}
		if (!this.rateLimiter.tryAcquire(maxRequestsPerMinute)) {
			LOGGER.debug("Groq request skipped: server rate limit reached.");
			return CompletableFuture.completedFuture(new Result.Failed(Failure.RATE_LIMITED));
		}

		String body = buildRequestJson(model, messages, temperature, maxTokens);
		Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

		return this.transport.send(endpoint, apiKey, body, timeout)
			.handle((httpResult, error) -> {
				if (error != null) {
					return (Result) new Result.Failed(classify(error));
				}
				return parseHttpResult(httpResult);
			});
	}

	private Result parseHttpResult(HttpResult httpResult) {
		if (httpResult == null) {
			return new Result.Failed(Failure.NETWORK_ERROR);
		}
		if (httpResult.statusCode() == 429) {
			LOGGER.debug("Groq rate-limited the request (429).");
			return new Result.Failed(Failure.RATE_LIMITED);
		}
		if (httpResult.statusCode() < 200 || httpResult.statusCode() >= 300) {
			// Status only: bodies can contain request echoes, never log them.
			LOGGER.debug("Groq returned HTTP {}.", httpResult.statusCode());
			return new Result.Failed(Failure.HTTP_ERROR);
		}
		Optional<String> content = parseResponseContent(httpResult.body());
		if (content.isEmpty()) {
			LOGGER.debug("Groq response had no usable content.");
			return new Result.Failed(Failure.INVALID_RESPONSE);
		}
		return new Result.Success(content.get());
	}

	private static Failure classify(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String name = cause.getClass().getSimpleName();
		if (name.contains("Timeout") || name.contains("ConnectException")) {
			// A connect failure is also a timeout for practical purposes:
			// the player gets the offline line either way.
			return name.contains("Timeout") ? Failure.TIMEOUT : Failure.NETWORK_ERROR;
		}
		return Failure.NETWORK_ERROR;
	}

	/** Builds the OpenAI-compatible request JSON. Pure and unit tested. */
	public static String buildRequestJson(String model, List<ChatMessage> messages, double temperature, int maxTokens) {
		JsonObject request = new JsonObject();
		request.addProperty("model", model);
		request.addProperty("temperature", temperature);
		request.addProperty("max_tokens", maxTokens);

		JsonArray array = new JsonArray();
		for (ChatMessage message : messages) {
			JsonObject entry = new JsonObject();
			entry.addProperty("role", message.role());
			entry.addProperty("content", message.content());
			array.add(entry);
		}
		request.add("messages", array);
		return GSON.toJson(request);
	}

	/**
	 * Extracts {@code choices[0].message.content} from a Groq response.
	 * Returns empty for anything malformed instead of throwing.
	 */
	public static Optional<String> parseResponseContent(String json) {
		if (json == null || json.isBlank()) {
			return Optional.empty();
		}
		try {
			JsonObject parsed = GSON.fromJson(json, JsonObject.class);
			if (parsed == null || !parsed.has("choices")) {
				return Optional.empty();
			}
			JsonArray choices = parsed.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				return Optional.empty();
			}
			JsonElement first = choices.get(0);
			if (!first.isJsonObject()) {
				return Optional.empty();
			}
			JsonObject choice = first.getAsJsonObject();
			if (!choice.has("message")) {
				return Optional.empty();
			}
			JsonObject message = choice.getAsJsonObject("message");
			if (!message.has("content") || message.get("content").isJsonNull()) {
				return Optional.empty();
			}
			String content = message.get("content").getAsString();
			if (content == null || content.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(content.trim());
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Transport defaultTransport() {
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

		return (url, apiKey, jsonBody, timeout) -> {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

			return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> new HttpResult(response.statusCode(), response.body()))
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

		/** Requests recorded in the current window. Visible for tests. */
		public synchronized int recentCount() {
			long windowStart = System.currentTimeMillis() - 60_000L;
			this.requestTimes.removeIf(time -> time < windowStart);
			return this.requestTimes.size();
		}
	}
}
