package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.GroqClient.Diagnostics;
import com.falsity.ai.GroqClient.Exchange;
import com.falsity.ai.GroqClient.Failure;
import com.falsity.ai.GroqClient.Request;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Groq client over real HTTP. Every other test scripts the transport; here
 * the client's own HTTP code talks to a server on this machine, so what goes
 * over the wire (the method, the headers, the bytes of the body) and what the
 * client makes of real responses, a real timeout and a real refused
 * connection are all checked. Only Groq itself is replaced.
 */
class GroqHttpTransportTest {
	private static final String KEY = "gsk_localtest0123456789abcdef";
	private static final String PATH = "/openai/v1/chat/completions";
	private static final Gson GSON = new Gson();

	/** One request as the server received it. */
	private record Seen(String method, String path, Map<String, String> headers, String body) {
	}

	/** What the server answers with. */
	private record Answer(int status, String body, Map<String, String> headers, long delayMillis) {
	}

	private final List<Seen> seen = new CopyOnWriteArrayList<>();
	private volatile Answer answer = new Answer(200, groqReply("Hello."), Map.of(), 0L);
	private HttpServer server;
	private ExecutorService executor;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		this.executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "falsity-test-http");
			thread.setDaemon(true);
			return thread;
		});
		this.server.setExecutor(this.executor);
		this.server.createContext("/", exchange -> {
			Map<String, String> headers = new HashMap<>();
			exchange.getRequestHeaders().forEach((name, values) ->
				headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			this.seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));

			Answer current = this.answer;
			if (current.delayMillis() > 0L) {
				try {
					Thread.sleep(current.delayMillis());
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
			current.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
			try {
				if (bytes.length == 0) {
					exchange.sendResponseHeaders(current.status(), -1);
				} else {
					exchange.sendResponseHeaders(current.status(), bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			} catch (IOException clientGone) {
				// The client gave up waiting; that is what the timeout test is about.
			} finally {
				exchange.close();
			}
		});
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	private String endpoint() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + PATH;
	}

	private String host() {
		return "127.0.0.1:" + this.server.getAddress().getPort();
	}

	private static Request request(String endpoint, String userMessage, int timeoutSeconds) {
		return new Request(
			endpoint, KEY, "openai/gpt-oss-20b",
			List.of(new GroqClient.ChatMessage("system", "You are Falsity."), new GroqClient.ChatMessage("user", userMessage)),
			0.7, 150, timeoutSeconds, 30, "low", 512);
	}

	/** A response body in the shape Groq sends. */
	private static String groqReply(String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", content);
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.addProperty("finish_reason", "stop");
		choice.add("message", message);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject usage = new JsonObject();
		usage.addProperty("prompt_tokens", 21);
		usage.addProperty("completion_tokens", 9);
		usage.addProperty("total_tokens", 30);
		JsonObject groq = new JsonObject();
		groq.addProperty("id", "req_local");
		JsonObject body = new JsonObject();
		body.addProperty("id", "chatcmpl-local");
		body.addProperty("object", "chat.completion");
		body.add("choices", choices);
		body.add("usage", usage);
		body.add("x_groq", groq);
		return GSON.toJson(body);
	}

	private static String groqError(String code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("message", message);
		error.addProperty("type", "invalid_request_error");
		error.addProperty("code", code);
		JsonObject body = new JsonObject();
		body.add("error", error);
		return GSON.toJson(body);
	}

	@Test
	void aRealRequestIsAPostWithTheKeyAJsonBodyAndItsOwnName() {
		String said = "caf\u00e9 \u2615 \u4f60\u597d - can you hear me?";
		String answered = "Loud and clear \u2728 caf\u00e9!";
		this.answer = new Answer(200, groqReply(answered), Map.of("Content-Type", "application/json"), 0L);

		Exchange exchange = new GroqClient().exchange(request(endpoint(), said, 5)).join();

		assertTrue(exchange.result() instanceof GroqClient.Result.Success, exchange.diagnostics().summary());
		assertEquals(answered, ((GroqClient.Result.Success) exchange.result()).content(),
			"the reply survives the trip, letters outside ASCII included");
		Diagnostics diagnostics = exchange.diagnostics();
		assertEquals(200, diagnostics.httpStatus());
		assertEquals(1, diagnostics.attempts());
		assertEquals("stop", diagnostics.finishReason());
		assertEquals("req_local", diagnostics.groqRequestId());
		assertEquals(30, diagnostics.totalTokens());

		assertEquals(1, this.seen.size());
		Seen request = this.seen.get(0);
		assertEquals("POST", request.method());
		assertEquals(PATH, request.path());
		assertEquals("Bearer " + KEY, request.headers().get("authorization"));
		assertTrue(request.headers().get("content-type").startsWith("application/json"), request.headers().toString());
		assertEquals(GroqClient.USER_AGENT, request.headers().get("user-agent"));
		assertFalse(request.headers().get("user-agent").toLowerCase(Locale.ROOT).contains("java"),
			"the stock Java client name is one that web firewalls turn away");
		assertFalse(request.headers().containsKey("upgrade"), "plain HTTP/1.1, no protocol upgrade: " + request.headers());

		JsonObject body = GSON.fromJson(request.body(), JsonObject.class);
		assertEquals("openai/gpt-oss-20b", body.get("model").getAsString());
		assertEquals(662, body.get("max_completion_tokens").getAsInt());
		assertEquals("low", body.get("reasoning_effort").getAsString());
		assertFalse(body.get("include_reasoning").getAsBoolean());
		assertFalse(body.has("max_tokens"), request.body());
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals(2, messages.size());
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
		assertEquals(said, messages.get(1).getAsJsonObject().get("content").getAsString(),
			"the player's words arrive as UTF-8, not as question marks");
	}

	@Test
	void aRejectedKeyComesBackWithGroqsOwnWordsAndWithoutTheKey() {
		this.answer = new Answer(401, groqError("invalid_api_key", "Invalid API Key"), Map.of("Content-Type", "application/json"), 0L);

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint(), "Hello", 5)).join().diagnostics();

		assertEquals(Failure.UNAUTHORIZED, diagnostics.failure());
		assertEquals(401, diagnostics.httpStatus());
		assertTrue(diagnostics.detail().contains("invalid_api_key"), diagnostics.detail());
		assertFalse(diagnostics.summary().contains(KEY), diagnostics.summary());
		assertEquals(1, this.seen.size(), "a refused key is not tried again");
	}

	@Test
	void aRateLimitBringsItsWaitingTimeAlong() {
		this.answer = new Answer(429, groqError("rate_limit_exceeded", "Rate limit reached"), Map.of("Retry-After", "3"), 0L);

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint(), "Hello", 5)).join().diagnostics();

		assertEquals(Failure.RATE_LIMITED, diagnostics.failure());
		assertEquals(3000L, diagnostics.retryAfterMillis());
		assertEquals(1, this.seen.size());
	}

	@Test
	void aServerThatTakesTooLongIsATimeoutAndIsNotAskedTwice() {
		this.answer = new Answer(200, groqReply("too late"), Map.of(), 3_000L);
		long started = System.nanoTime();

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint(), "Hello", 1)).join().diagnostics();

		long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
		assertEquals(Failure.TIMEOUT, diagnostics.failure());
		assertEquals("no answer from " + host() + " within 1 s", diagnostics.detail());
		assertEquals(1, diagnostics.attempts());
		assertTrue(waitedMillis < 2_900L, "the client stops waiting at its own limit, not the server's: " + waitedMillis + " ms");
		assertEquals(1, this.seen.size());
	}

	@Test
	void nothingListeningIsANetworkErrorThatNamesTheHost() {
		String endpoint = endpoint();
		String host = host();
		this.server.stop(0);

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint, "Hello", 5)).join().diagnostics();

		assertEquals(Failure.NETWORK_ERROR, diagnostics.failure());
		assertEquals(0, diagnostics.httpStatus());
		assertTrue(diagnostics.detail().contains(host), diagnostics.detail());
		assertFalse(diagnostics.detail().contains("Exception"), "said in plain words: " + diagnostics.detail());
	}

	@Test
	void aRedirectIsReportedAsAWrongAddressAndNotFollowed() {
		this.answer = new Answer(301, "", Map.of("Location", "https://elsewhere.example/openai/v1/chat/completions"), 0L);

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint(), "Hello", 5)).join().diagnostics();

		assertEquals(Failure.BAD_ENDPOINT, diagnostics.failure());
		assertEquals("the address answered with a redirect to elsewhere.example", diagnostics.detail());
		assertEquals(1, this.seen.size(), "the key is never carried along to wherever a redirect points");
	}

	@Test
	void aFirewallsRefusalIsRecognisedWithoutQuotingIt() {
		this.answer = new Answer(403, "error code: 1010", Map.of("Content-Type", "text/plain; charset=UTF-8"), 0L);

		Diagnostics diagnostics = new GroqClient().exchange(request(endpoint(), "Hello", 5)).join().diagnostics();

		assertEquals(Failure.FORBIDDEN, diagnostics.failure());
		assertEquals("turned away before reaching Groq (error code 1010)", diagnostics.detail());
	}

	@Test
	void aConnectionDroppedOnceIsTriedAgainOnAFreshOne() throws Exception {
		AtomicInteger connections = new AtomicInteger();
		byte[] reply = groqReply("Second time lucky.").getBytes(StandardCharsets.UTF_8);
		try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
			Thread serverThread = new Thread(() -> {
				try {
					// The first connection is closed without a word, which is what a
					// stale pooled connection looks like to the client.
					try (Socket first = listener.accept()) {
						connections.incrementAndGet();
						readRequest(first.getInputStream());
					}
					try (Socket second = listener.accept()) {
						connections.incrementAndGet();
						readRequest(second.getInputStream());
						OutputStream out = second.getOutputStream();
						out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + reply.length
							+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
						out.write(reply);
						out.flush();
					}
				} catch (IOException closed) {
					// The test is over.
				}
			}, "falsity-test-dropping-server");
			serverThread.setDaemon(true);
			serverThread.start();

			String endpoint = "http://127.0.0.1:" + listener.getLocalPort() + PATH;
			Exchange exchange = new GroqClient().exchange(request(endpoint, "Hello", 5)).join();

			assertTrue(exchange.result() instanceof GroqClient.Result.Success, exchange.diagnostics().summary());
			assertEquals("Second time lucky.", ((GroqClient.Result.Success) exchange.result()).content());
			assertEquals(2, exchange.diagnostics().attempts());
			assertEquals(2, connections.get());
		}
	}

	/** Reads one HTTP request (headers and body) from a raw socket. */
	private static void readRequest(InputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		int matched = 0;
		while (matched < 4) {
			int next = in.read();
			if (next < 0) {
				return;
			}
			head.write(next);
			boolean expectsReturn = matched % 2 == 0;
			if ((expectsReturn && next == '\r') || (!expectsReturn && next == '\n')) {
				matched++;
			} else {
				matched = next == '\r' ? 1 : 0;
			}
		}
		int length = 0;
		for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
			if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
				length = Integer.parseInt(line.substring("content-length:".length()).trim());
			}
		}
		in.readNBytes(length);
	}
}
