package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.ai.GroqClient.Diagnostics;
import com.falsity.ai.GroqClient.Exchange;
import com.falsity.ai.GroqClient.Failure;
import com.falsity.ai.GroqClient.Request;
import com.falsity.config.FalsityConfig;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Talks to the real Groq service, with the settings a fresh install uses.
 *
 * <p>Every other test replaces Groq, so none of them can show that Groq accepts
 * what the mod sends. This one can, but it needs a key and the internet, so it
 * only runs when the {@code GROQ_API_KEY} environment variable holds a key:
 *
 * <pre>GROQ_API_KEY=gsk_... ./gradlew test --tests '*GroqLiveTest*'</pre>
 *
 * <p>Without a key the tests are reported as skipped, never as passed. They are
 * also skipped, with the reason, when Groq cannot be reached or is busy: that
 * says nothing about the mod. They fail when Groq refuses the key, the model or
 * the request itself, or answers with nothing. In game, {@code /falsity ai test}
 * makes the same request.
 */
class GroqLiveTest {
	private static String key() {
		String raw = System.getenv("GROQ_API_KEY");
		String key = raw == null ? "" : ApiKeyProvider.normalise(raw);
		Assumptions.assumeTrue(!key.isEmpty() && !ApiKeyProvider.isPlaceholder(key),
			"GROQ_API_KEY is not set, so the live Groq check did not run");
		return key;
	}

	private static Exchange ask(String key, List<GroqClient.ChatMessage> messages) {
		FalsityConfig config = new FalsityConfig();
		Exchange exchange = new GroqClient().exchange(new Request(
			config.groq.endpoint, key, config.groq.model, messages,
			config.groq.temperature, config.groq.maxTokens, config.groq.timeoutSeconds, Integer.MAX_VALUE,
			config.groq.reasoningEffort, config.groq.reasoningHeadroomTokens)).join();
		Diagnostics diagnostics = exchange.diagnostics();
		Assumptions.assumeFalse(saysNothingAboutTheMod(diagnostics),
			"Groq could not be reached or was busy, so nothing was checked: " + explain(diagnostics));
		return exchange;
	}

	/** No internet, a slow day at Groq or a used-up allowance are not defects in the mod. */
	static boolean saysNothingAboutTheMod(Diagnostics diagnostics) {
		Failure failure = diagnostics.failure();
		return failure == Failure.NETWORK_ERROR
			|| failure == Failure.TIMEOUT
			|| failure == Failure.RATE_LIMITED
			|| (failure == Failure.HTTP_ERROR && diagnostics.httpStatus() >= 500);
	}

	private static String explain(Diagnostics diagnostics) {
		return diagnostics.summary() + " -> " + AiStatusReport.adviceFor(diagnostics.failure());
	}

	@Test
	void onlyTroubleOutsideTheModIsSkippedOver() {
		assertTrue(saysNothingAboutTheMod(failed(Failure.NETWORK_ERROR, 0)));
		assertTrue(saysNothingAboutTheMod(failed(Failure.TIMEOUT, 0)));
		assertTrue(saysNothingAboutTheMod(failed(Failure.RATE_LIMITED, 429)));
		assertTrue(saysNothingAboutTheMod(failed(Failure.HTTP_ERROR, 503)));

		assertFalse(saysNothingAboutTheMod(failed(Failure.HTTP_ERROR, 400)), "a request Groq calls malformed is the mod's fault");
		assertFalse(saysNothingAboutTheMod(failed(Failure.UNAUTHORIZED, 401)));
		assertFalse(saysNothingAboutTheMod(failed(Failure.FORBIDDEN, 403)));
		assertFalse(saysNothingAboutTheMod(failed(Failure.MODEL_UNAVAILABLE, 404)), "a retired default model must be noticed");
		assertFalse(saysNothingAboutTheMod(failed(Failure.BAD_ENDPOINT, 404)));
		assertFalse(saysNothingAboutTheMod(failed(Failure.INVALID_RESPONSE, 200)), "an empty reply is the very thing to catch");
		assertFalse(saysNothingAboutTheMod(failed(null, 200)));
	}

	private static Diagnostics failed(Failure failure, int status) {
		return new Diagnostics("f1", "openai/gpt-oss-20b", status, 5L, "", "", -1, -1, -1, 1, failure, "", 0L);
	}

	@Test
	void theDefaultModelAnswersTheDefaultRequest() {
		String key = key();

		Exchange exchange = ask(key, List.of(
			new GroqClient.ChatMessage("system", "You are Falsity, a small plush companion. Reply with one short friendly sentence."),
			new GroqClient.ChatMessage("user", "Say hello.")));

		Diagnostics diagnostics = exchange.diagnostics();
		assertTrue(diagnostics.success(), "Groq did not answer: " + explain(diagnostics));
		assertFalse(((GroqClient.Result.Success) exchange.result()).content().isBlank());
		assertEquals(200, diagnostics.httpStatus());
		assertEquals(1, diagnostics.attempts(),
			"Groq took the request as it was first sent, reasoning settings and budget included: " + diagnostics.summary());
		assertFalse(diagnostics.summary().contains(key));
		System.out.println("[GroqLiveTest] " + diagnostics.summary());
	}

	@Test
	void aRealPromptComesBackAsSomethingFalsityCanSay() {
		String key = key();
		String message = "hello Falsity, how are you today?";
		String systemPrompt = PromptBuilder.buildSystemPrompt(
			new PlayerMemory("uuid-live", "Steve", 0L), new PersonalityProfile(), EmotionalState.CURIOUS,
			HorrorStage.INNOCENCE, true, message,
			new PromptBuilder.Context("You and the player are in a plains biome in the overworld. It is day.", ""));

		Exchange exchange = ask(key, PromptBuilder.buildMessages(systemPrompt, message));

		Diagnostics diagnostics = exchange.diagnostics();
		assertTrue(diagnostics.success(), "Groq did not answer: " + explain(diagnostics));
		String raw = ((GroqClient.Result.Success) exchange.result()).content();
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse(raw);
		assertTrue(parsed.isPresent(), "nothing speakable in: " + raw);
		String dialogue = parsed.get().dialogue();
		assertFalse(dialogue.isBlank(), raw);
		assertTrue(dialogue.length() <= 300, dialogue);
		assertFalse(dialogue.contains("\n"), dialogue);
		assertFalse(dialogue.toLowerCase(Locale.ROOT).contains("[action"), dialogue);
		System.out.println("[GroqLiveTest] " + diagnostics.summary() + " said: " + dialogue);
	}
}
