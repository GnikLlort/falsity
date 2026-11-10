package com.falsity.ai;

import com.falsity.config.FalsityConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
 * logs, crash reports or config files by this code. What may be logged is
 * where the key came from, which is what {@link #locate} reports.
 */
public final class ApiKeyProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyProvider.class);
	private static final Gson GSON = new Gson();

	/** Where a key was found. */
	public enum Source {
		ENVIRONMENT("the GROQ_API_KEY environment variable"),
		SECRETS_FILE("secrets.json"),
		CONFIG("groq.apiKey in config/falsity.json"),
		NONE("nowhere");

		private final String description;

		Source(String description) {
			this.description = description;
		}

		/** Human-readable place name, safe to log. */
		public String description() {
			return this.description;
		}
	}

	/**
	 * A resolved key and where it came from. {@code note} explains an ignored
	 * value (such as an unedited placeholder) and never contains the key.
	 */
	public record Located(String key, Source source, String note) {
		public boolean present() {
			return !this.key.isEmpty();
		}
	}

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
		Located located = locate(config, gameDir);
		return located.present() ? Optional.of(located.key()) : Optional.empty();
	}

	/** Resolves the key and reports which of the three places supplied it. */
	public Located locate(FalsityConfig config, Path gameDir) {
		String note = "";

		String fromEnvironment = normalise(this.environmentLookup.get());
		if (isPlaceholder(fromEnvironment)) {
			note = "GROQ_API_KEY still holds the placeholder text";
		} else if (!fromEnvironment.isEmpty()) {
			return new Located(fromEnvironment, Source.ENVIRONMENT, "");
		}

		String fromSecrets = normalise(readSecretsFile(gameDir));
		if (isPlaceholder(fromSecrets)) {
			note = "secrets.json still holds the placeholder text; replace it with your key";
		} else if (!fromSecrets.isEmpty()) {
			return new Located(fromSecrets, Source.SECRETS_FILE, "");
		}

		String fromConfig = config == null ? "" : normalise(config.groq.apiKey);
		if (isPlaceholder(fromConfig)) {
			note = "groq.apiKey still holds the placeholder text; replace it with your key";
		} else if (!fromConfig.isEmpty()) {
			return new Located(fromConfig, Source.CONFIG, "");
		}

		return new Located("", Source.NONE, note);
	}

	private static String readSecretsFile(Path gameDir) {
		if (gameDir == null) {
			return "";
		}
		Path secrets = gameDir.resolve("secrets.json");
		if (!Files.exists(secrets)) {
			return "";
		}

		try {
			String json = Files.readString(secrets, StandardCharsets.UTF_8);
			JsonObject parsed = GSON.fromJson(json, JsonObject.class);
			if (parsed == null || !parsed.has("groqApiKey")) {
				LOGGER.warn("secrets.json has no groqApiKey field; ignoring it.");
				return "";
			}
			return parsed.get("groqApiKey").getAsString();
		} catch (Exception exception) {
			// Never include file contents (which may hold the key) in the log.
			LOGGER.warn("Could not read secrets.json; ignoring it. ({})", exception.getClass().getSimpleName());
			return "";
		}
	}

	/**
	 * Tidies a pasted key: surrounding whitespace, wrapping quotes and a
	 * leading {@code Bearer} are all common paste mistakes that would otherwise
	 * turn a good key into an HTTP 401.
	 */
	static String normalise(String value) {
		if (value == null) {
			return "";
		}
		String key = value.trim();
		if (key.length() >= 2
			&& ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'")))) {
			key = key.substring(1, key.length() - 1).trim();
		}
		if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
			key = key.substring(7).trim();
		}
		return key;
	}

	/** Whether a value is example text rather than a real key. */
	static boolean isPlaceholder(String key) {
		if (key == null || key.isEmpty()) {
			return false;
		}
		String upper = key.toUpperCase(Locale.ROOT);
		return upper.startsWith("REPLACE_WITH") || upper.startsWith("YOUR_") || upper.contains("YOUR_GROQ_API_KEY")
			|| upper.equals("GSK_...") || upper.contains("<") || upper.contains(" ");
	}
}
