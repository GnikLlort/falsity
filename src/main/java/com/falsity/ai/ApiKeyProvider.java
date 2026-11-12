package com.falsity.ai;

import com.falsity.config.FalsityConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Groq API key without ever logging it.
 *
 * <p>Priority, first non-empty wins:
 * <ol>
 *   <li>the {@code GROQ_API_KEY} environment variable,</li>
 *   <li>{@code secrets.json} in the game directory, or in its {@code config}
 *       folder (git-ignored),</li>
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

	/** The name of the secrets file. */
	public static final String SECRETS_FILE_NAME = "secrets.json";

	/** Names the secrets file is saved under by mistake, most often because Windows hides the ending. */
	private static final List<String> MISNAMED = List.of(
		"secrets.json.txt", "secrets.txt", "secret.json", "secrets.json.json", "secrets.example.json");

	/** Larger than this and the file is not a key file; it is not read. */
	private static final long MAX_SECRETS_BYTES = 64L * 1024L;

	/**
	 * Characters a key never contains but copy and paste adds: line breaks and
	 * tabs, the byte-order mark, zero-width spaces and non-breaking spaces.
	 */
	private static final Pattern INVISIBLE = Pattern.compile("[\\r\\n\\t\\u00A0\\u200B-\\u200D\\u2060\\uFEFF]");

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
	 * A resolved key and where it came from.
	 *
	 * @param key the key, or an empty string when there is none
	 * @param source which of the three places supplied it
	 * @param note why a value that was found is not being used (an unedited
	 *     placeholder, a file that cannot be read, a misnamed file); never
	 *     contains the key
	 * @param secretsPath where {@code secrets.json} is looked for, so a status
	 *     report can name the real folder; empty when there is no game directory
	 */
	public record Located(String key, Source source, String note, String secretsPath) {
		public boolean present() {
			return !this.key.isEmpty();
		}
	}

	/** What a secrets file gave: a usable key, or the reason there is none. */
	private record Secrets(String key, String problem) {
		static final Secrets NOTHING = new Secrets("", "");
	}

	private final Supplier<String> environmentLookup;

	/** The last problem written to the log, so the same one is not repeated on every message. */
	private volatile String loggedProblem = "";

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

	/**
	 * Resolves the key and reports which of the three places supplied it. The
	 * places are read again on every call, so a key added while the server runs
	 * is picked up by the next message.
	 */
	public Located locate(FalsityConfig config, Path gameDir) {
		String secretsPath = gameDir == null ? "" : gameDir.resolve(SECRETS_FILE_NAME).toAbsolutePath().normalize().toString();
		List<String> notes = new ArrayList<>();

		String fromEnvironment = normalise(this.environmentLookup.get());
		if (isPlaceholder(fromEnvironment)) {
			notes.add("GROQ_API_KEY " + whyUnusable(fromEnvironment));
		} else if (!fromEnvironment.isEmpty()) {
			return new Located(fromEnvironment, Source.ENVIRONMENT, "", secretsPath);
		}

		Secrets secrets = readSecrets(gameDir);
		if (!secrets.key().isEmpty()) {
			return new Located(secrets.key(), Source.SECRETS_FILE, "", secretsPath);
		}
		if (!secrets.problem().isEmpty()) {
			notes.add(secrets.problem());
		}

		String fromConfig = config == null ? "" : normalise(config.groq.apiKey);
		if (isPlaceholder(fromConfig)) {
			notes.add("groq.apiKey " + whyUnusable(fromConfig));
		} else if (!fromConfig.isEmpty()) {
			return new Located(fromConfig, Source.CONFIG, "", secretsPath);
		}

		String note = String.join("; ", notes);
		if (!note.isEmpty() && !note.equals(this.loggedProblem)) {
			this.loggedProblem = note;
			LOGGER.warn("No usable Groq API key: {}.", note);
		}
		return new Located("", Source.NONE, note, secretsPath);
	}

	/**
	 * Reads {@code secrets.json} from the game directory, or from its
	 * {@code config} folder, where it is often put instead. The first file that
	 * holds a usable key wins.
	 */
	private static Secrets readSecrets(Path gameDir) {
		if (gameDir == null) {
			return Secrets.NOTHING;
		}

		Path[] directories = {gameDir, gameDir.resolve("config")};
		String[] prefixes = {"", "config/"};

		String problem = "";
		for (int index = 0; index < directories.length; index++) {
			Path file = directories[index].resolve(SECRETS_FILE_NAME);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			Secrets read = readSecretsFile(file, prefixes[index] + SECRETS_FILE_NAME);
			if (!read.key().isEmpty()) {
				return read;
			}
			if (problem.isEmpty()) {
				problem = read.problem();
			}
		}
		if (!problem.isEmpty()) {
			return new Secrets("", problem);
		}

		for (int index = 0; index < directories.length; index++) {
			for (String name : MISNAMED) {
				if (Files.isRegularFile(directories[index].resolve(name))) {
					return new Secrets("", "found " + prefixes[index] + name + ", but the file must be named "
						+ SECRETS_FILE_NAME + " exactly; rename it");
				}
			}
		}
		return Secrets.NOTHING;
	}

	private static Secrets readSecretsFile(Path file, String name) {
		try {
			if (Files.size(file) > MAX_SECRETS_BYTES) {
				return new Secrets("", name + " is too large to be a key file");
			}
			JsonObject parsed = GSON.fromJson(readText(file), JsonObject.class);
			if (parsed == null) {
				return new Secrets("", name + " is empty");
			}
			JsonElement entry = keyEntry(parsed);
			if (entry == null) {
				return new Secrets("", name + " has no groqApiKey entry");
			}
			if (!entry.isJsonPrimitive()) {
				return new Secrets("", "the groqApiKey entry in " + name + " must be the key as text in quotes");
			}
			String key = normalise(entry.getAsString());
			if (key.isEmpty()) {
				return new Secrets("", "the groqApiKey entry in " + name + " is empty");
			}
			if (isPlaceholder(key)) {
				return new Secrets("", name + " " + whyUnusable(key));
			}
			return new Secrets(key, "");
		} catch (IOException | RuntimeException exception) {
			// Never include the file's contents (which may hold the key), nor the
			// parser's message (which quotes them).
			return new Secrets("", name + " could not be read: it must look like {\"groqApiKey\": \"your key\"}");
		}
	}

	/**
	 * The key entry of a secrets file. The documented name is
	 * {@code groqApiKey}; close variants ({@code GROQ_API_KEY}, {@code apiKey})
	 * and the config file's own shape ({@code "groq": {"apiKey": ...}}) are
	 * accepted too, because the intent is unmistakable.
	 */
	private static JsonElement keyEntry(JsonObject object) {
		JsonElement nested = null;
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			String name = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
			if (name.equals("groqapikey") || name.equals("apikey") || name.equals("groqkey")) {
				return entry.getValue();
			}
			if (name.equals("groq") && entry.getValue().isJsonObject()) {
				nested = keyEntry(entry.getValue().getAsJsonObject());
			}
		}
		return nested;
	}

	/**
	 * Reads a hand-edited text file. Windows tools sometimes save UTF-16
	 * (PowerShell's {@code >} does); that is recognised by its byte-order mark.
	 */
	private static String readText(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
			return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
			return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Tidies a pasted key: surrounding whitespace, wrapping quotes, a leading
	 * {@code Bearer}, a line break in the middle and invisible characters picked
	 * up from a web page are all common paste mistakes that would otherwise turn
	 * a good key into an HTTP 401, or into a request that cannot be sent at all.
	 */
	static String normalise(String value) {
		if (value == null) {
			return "";
		}
		String key = INVISIBLE.matcher(value).replaceAll("").strip();
		if (key.length() >= 2 && isQuote(key.charAt(0)) && isQuote(key.charAt(key.length() - 1))) {
			key = key.substring(1, key.length() - 1).strip();
		}
		if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
			key = key.substring(7).strip();
		}
		return key;
	}

	private static boolean isQuote(char character) {
		return character == '"' || character == '\'' || character == '`'
			|| character == '\u201C' || character == '\u201D' || character == '\u2018' || character == '\u2019';
	}

	/** Whether a value is example text, or otherwise cannot be a key, rather than a real key. */
	static boolean isPlaceholder(String key) {
		if (key == null || key.isEmpty()) {
			return false;
		}
		String upper = key.toUpperCase(Locale.ROOT);
		return upper.startsWith("REPLACE_WITH") || upper.startsWith("YOUR_") || upper.contains("YOUR_GROQ_API_KEY")
			|| upper.equals("GSK_...") || upper.contains("<") || upper.contains(" ");
	}

	/** The reason a value rejected by {@link #isPlaceholder} is not used, for the note. */
	private static String whyUnusable(String key) {
		String upper = key.toUpperCase(Locale.ROOT);
		boolean exampleText = upper.contains("REPLACE") || upper.contains("YOUR") || upper.contains("...") || upper.contains("<");
		return exampleText
			? "still holds the placeholder text; replace it with your key"
			: "contains a space, which a key never does; paste the key again";
	}
}
