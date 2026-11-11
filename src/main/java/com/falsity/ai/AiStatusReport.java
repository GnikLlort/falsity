package com.falsity.ai;

import com.falsity.config.FalsityConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of {@code /falsity ai status} and {@code /falsity ai test}.
 *
 * <p>The point of these commands is to replace guessing. Having a key
 * configured is not the same as Groq working, so the report separates what is
 * configured from what has actually been observed: a connection counts as
 * verified only after a request has succeeded. The key itself is never shown,
 * only where it was found.
 */
public final class AiStatusReport {
	private AiStatusReport() {
	}

	/** The status report, one chat line per entry. */
	public static List<String> lines(
		FalsityConfig config,
		ApiKeyProvider.Located key,
		GroqClient.Status status,
		int waitingMessages,
		long nowEpochMillis
	) {
		List<String> lines = new ArrayList<>();
		lines.add("--- Falsity AI status ---");
		lines.add(stateLine(config, key, status));
		lines.add("Model: " + config.groq.model + " (max tokens " + config.groq.maxTokens
			+ reasoningNote(config) + ", timeout " + config.groq.timeoutSeconds + " s, history "
			+ config.groq.historyTurns + " turns)");
		lines.add(keyLine(key));
		lines.add("Pacing: cooldown " + config.conversation.cooldownSeconds + " s, at most "
			+ config.conversation.maxRequestsPerMinute + " requests a minute, " + waitingMessages + " waiting");
		lines.add("This session: " + status.requests() + " requests, " + status.successes() + " answered, "
			+ status.failures() + " failed");
		if (status.lastSuccess() != null) {
			lines.add("Last success " + ago(nowEpochMillis - status.lastSuccessEpochMillis()) + ": " + status.lastSuccess().summary());
		}
		if (status.lastFailure() != null) {
			lines.add("Last failure " + ago(nowEpochMillis - status.lastFailureEpochMillis()) + ": " + status.lastFailure().summary());
			String advice = adviceFor(status.lastFailure().failure());
			if (!advice.isEmpty()) {
				lines.add("  -> " + advice);
			}
		}
		return lines;
	}

	/** One line saying whether Groq is off, unconfigured, unverified or working. */
	public static String stateLine(FalsityConfig config, ApiKeyProvider.Located key, GroqClient.Status status) {
		if (!config.groq.enabled) {
			return "Groq: OFF (groq.enabled is false). Falsity uses its offline lines.";
		}
		if (!key.present()) {
			return "Groq: NO API KEY. Falsity uses its offline lines.";
		}
		if (!status.verified()) {
			return status.failures() > 0
				? "Groq: NOT WORKING. Every request so far has failed; see the last failure below."
				: "Groq: configured, not verified yet. Run /falsity ai test, or just talk to Falsity.";
		}
		if (status.lastFailureEpochMillis() > status.lastSuccessEpochMillis()) {
			return "Groq: working earlier, but the most recent request failed; see below.";
		}
		return "Groq: WORKING (the last request was answered).";
	}

	/** Where the key comes from. Never includes the key. */
	public static String keyLine(ApiKeyProvider.Located key) {
		if (key.present()) {
			return "API key: found in " + key.source().description() + " (" + key.key().length() + " characters; not shown)";
		}
		String note = key.note().isEmpty() ? "" : " Note: " + key.note() + ".";
		String secrets = key.secretsPath().isEmpty() ? "secrets.json in the game directory" : key.secretsPath();
		return "API key: not found. Set the GROQ_API_KEY environment variable, or put it in " + secrets
			+ ", or in groq.apiKey in config/falsity.json." + note;
	}

	/** The result of {@code /falsity ai test}, in one line. */
	public static String testResult(GroqClient.Exchange exchange, Throwable error) {
		if (error != null || exchange == null) {
			// Only the kind of error: its message is not known to be free of the key.
			return "Groq test failed before a request could be made"
				+ (error == null ? "." : " (" + error.getClass().getSimpleName() + "). See the server log.");
		}
		GroqClient.Diagnostics diagnostics = exchange.diagnostics();
		if (exchange.result() instanceof GroqClient.Result.Success success) {
			String reply = AIResponseParser.parse(success.content()).map(AIResponseParser.ParsedResponse::dialogue).orElse("");
			if (reply.length() > 100) {
				reply = reply.substring(0, 100).trim() + "...";
			}
			return "Groq works. It answered \"" + reply + "\" (" + diagnostics.summary() + ")";
		}
		String advice = adviceFor(diagnostics.failure());
		return "Groq test failed: " + diagnostics.summary() + (advice.isEmpty() ? "" : " -> " + advice);
	}

	/** What an operator should do about a failure, in one short sentence. */
	public static String adviceFor(GroqClient.Failure failure) {
		if (failure == null) {
			return "";
		}
		return switch (failure) {
			case UNAUTHORIZED -> "The API key was not accepted: create a new one at console.groq.com/keys and put it in "
				+ "GROQ_API_KEY, secrets.json or groq.apiKey.";
			case MODEL_UNAVAILABLE -> "The model in groq.model is not available to this key: pick another model.";
			case RATE_LIMITED -> "Groq or the local limit (conversation.maxRequestsPerMinute) is rate limiting requests.";
			case TIMEOUT -> "The request timed out: check the network or raise groq.timeoutSeconds.";
			case NETWORK_ERROR -> "The server could not reach Groq: check the network, proxy or firewall.";
			case INVALID_RESPONSE -> "The reply was empty or malformed: try raising groq.maxTokens or groq.reasoningHeadroomTokens.";
			case HTTP_ERROR -> "Groq returned an error status; the detail above is Groq's own message.";
			case NO_KEY -> "No API key is configured.";
			case DISABLED -> "Groq is disabled in the config.";
		};
	}

	private static String reasoningNote(FalsityConfig config) {
		if (!GroqClient.usesReasoningParameters(config.groq.model, config.groq.reasoningEffort)) {
			return "";
		}
		return " + " + config.groq.reasoningHeadroomTokens + " for reasoning at effort " + config.groq.reasoningEffort;
	}

	private static String ago(long millis) {
		long seconds = Math.max(0L, millis) / 1000L;
		if (seconds < 90L) {
			return seconds + " s ago";
		}
		long minutes = seconds / 60L;
		if (minutes < 90L) {
			return minutes + " min ago";
		}
		return (minutes / 60L) + " h ago";
	}
}
