package com.falsity.ai;

import com.falsity.config.FalsityConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Groq API key without ever logging it.
 *
 * <p>Priority, first non-empty wins:
 * <ol>
 *   <li>the {@code GROQ_API_KEY} environment variable,</li>
 *   <li>{@code secrets.json} in the game directory (git-ignored),</li>
 *   <li>the {@code groq.apiKey} config field (ships empty).</li>
 * </ol>
 *
 * <p>The key is only ever held in memory on the server and sent as an
 * {@code Authorization: Bearer} header to Groq. It is never written to chat,
 * logs, crash reports or config files by this code.
 */
public final class ApiKeyProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyProvider.class);
	private static final Gson GSON = new Gson();

	private final Supplier<String> environmentLookup;

	public ApiKeyProvider() {
		this(() -> System.getenv("GROQ_API_KEY"));
	}

	/** Visible for tests: injects the environment lookup. */
	public ApiKeyProvider(Supplier<String> environmentLookup) {
		this.environmentLookup = environmentLookup;
	}

	/**
	 * Resolves the key for the given config and game directory. Empty means
	 * "no key configured" and callers must use the offline fallback.
	 */
	public Optional<String> resolve(FalsityConfig config, Path gameDir) {
		String fromEnvironment = trimToEmpty(this.environmentLookup.get());
		if (!fromEnvironment.isEmpty()) {
			LOGGER.debug("Groq API key provided via environment variable.");
			return Optional.of(fromEnvironment);
		}

		Optional<String> fromSecrets = readSecretsFile(gameDir);
		if (fromSecrets.isPresent()) {
			LOGGER.debug("Groq API key provided via secrets.json.");
			return fromSecrets;
		}

		String fromConfig = config == null ? "" : trimToEmpty(config.groq.apiKey);
		if (!fromConfig.isEmpty()) {
			LOGGER.debug("Groq API key provided via config file.");
			return Optional.of(fromConfig);
		}

		return Optional.empty();
	}

	private Optional<String> readSecretsFile(Path gameDir) {
		if (gameDir == null) {
			return Optional.empty();
		}
		Path secrets = gameDir.resolve("secrets.json");
		if (!Files.exists(secrets)) {
			return Optional.empty();
		}

		try {
			String json = Files.readString(secrets, StandardCharsets.UTF_8);
			JsonObject parsed = GSON.fromJson(json, JsonObject.class);
			if (parsed == null || !parsed.has("groqApiKey")) {
				LOGGER.warn("secrets.json has no groqApiKey field; ignoring it.");
				return Optional.empty();
			}
			String key = trimToEmpty(parsed.get("groqApiKey").getAsString());
			return key.isEmpty() ? Optional.empty() : Optional.of(key);
		} catch (Exception exception) {
			// Never include file contents (which may hold the key) in the log.
			LOGGER.warn("Could not read secrets.json; ignoring it. ({})", exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private static String trimToEmpty(String value) {
		return value == null ? "" : value.trim();
	}
}
