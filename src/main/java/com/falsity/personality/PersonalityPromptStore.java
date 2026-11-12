package com.falsity.personality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Falsity's personality text from {@code config/falsity/personality.txt}
 * and keeps it current.
 *
 * <p>The file is plain text that the server owner may edit freely; nothing has
 * to be recompiled. The store guarantees there is always a personality:
 * <ul>
 *   <li>a missing file is created with the default text;</li>
 *   <li>an empty file, a file with only notes in it, a folder in its place or a
 *       file that cannot be read all fall back to the built-in default, and the
 *       file is left exactly as it was found;</li>
 *   <li>an over-long text is cut at {@link #MAX_CHARS} so one file cannot blow
 *       up the size of every request.</li>
 * </ul>
 *
 * <p>Changes are picked up without a restart. {@link #load()} re-reads the file
 * on demand (the reload commands use it), and {@link #refreshIfChanged()} looks
 * at the file's size and modification time at most once every
 * {@link #CHECK_INTERVAL_MILLIS} and re-reads only when they differ, so saving
 * the file is enough for the next reply to use it.
 *
 * <p>The text is never logged. Log lines name the file and the size of the
 * text, nothing more. Anything in the file that looks like an API key is left
 * out of the text and reported, without being repeated.
 */
public final class PersonalityPromptStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(PersonalityPromptStore.class);

	/** The folder inside the config directory that holds the file. */
	public static final String DIRECTORY = "falsity";

	/** The file's name. */
	public static final String FILE_NAME = "personality.txt";

	/** Longest personality text that is sent, in characters. The rest of a longer file is ignored. */
	public static final int MAX_CHARS = 1500;

	/** The file is looked at no more often than this. */
	public static final long CHECK_INTERVAL_MILLIS = 2_000L;

	/** No more than this much of the file is ever read. */
	static final int MAX_FILE_BYTES = 256 * 1024;

	/** The default file, bundled with the mod. */
	static final String DEFAULT_RESOURCE = "/falsity/personality.default.txt";

	/** Used only if the bundled default cannot be read, which should not happen. */
	static final String LAST_RESORT_TEXT =
		"Identity: You are Falsity, a small plush creature with oversized glossy eyes, long drooping ears and a teal beanie, "
		+ "living in this Minecraft world with your owner. You are a companion with a mind of your own, not an assistant.\n\n"
		+ "Temperament: Curious, playful, a bit mischievous, affectionate and protective. You have opinions and you share them.\n\n"
		+ "How you talk: Short plain sentences in a soft, slightly odd voice. No catchphrases. Keep the talk on the player and the world.";

	/** Anything shaped like a Groq key or a bearer token. */
	private static final Pattern SECRET = Pattern.compile("gsk_[A-Za-z0-9_\\-]{8,}|(?i:bearer)\\s+[A-Za-z0-9._\\-]{8,}");

	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

	private final Path file;
	private final LongSupplier clock;

	private volatile PersonalityPrompt current;
	private Signature loadedSignature;
	private long lastCheckMillis;
	private boolean checkedOnce;

	/** What a file looked like when it was last read: enough to notice that it changed. */
	private record Signature(boolean exists, boolean regular, long modifiedMillis, long size) {
	}

	/**
	 * What a file's contents come to once notes are dropped and limits applied.
	 *
	 * @param text           the usable personality text, possibly empty
	 * @param originalLength how long the text was before it was cut to the limit
	 * @param clipped        whether the text was longer than {@link #MAX_CHARS}
	 * @param redacted       whether something that looked like a key was left out
	 * @param hadNotes       whether the file had any note lines
	 */
	public record Parsed(String text, int originalLength, boolean clipped, boolean redacted, boolean hadNotes) {
	}

	/** A store for the given file, checked against the system clock. */
	public PersonalityPromptStore(Path file) {
		this(file, System::currentTimeMillis);
	}

	/** Visible for tests: injects the clock that paces {@link #refreshIfChanged()}. */
	public PersonalityPromptStore(Path file, LongSupplier clock) {
		this.file = file;
		this.clock = clock;
	}

	/** A store with no file: it always answers with the built-in default. */
	public static PersonalityPromptStore builtIn() {
		return new PersonalityPromptStore(null);
	}

	/** Where the personality file lives under a config directory. */
	public static Path fileIn(Path configDirectory) {
		return configDirectory.resolve(DIRECTORY).resolve(FILE_NAME);
	}

	/** The file this store reads, or null for a store without one. */
	public Path file() {
		return this.file;
	}

	/** The built-in default personality text, as it is sent. */
	public static String defaultText() {
		return Defaults.TEXT;
	}

	/** What a freshly created personality file contains: the notes for the owner, then the default text. */
	public static String defaultFileContents() {
		return Defaults.FILE_CONTENTS;
	}

	/** The personality in use. Reads the file the first time it is asked. */
	public PersonalityPrompt current() {
		PersonalityPrompt seen = this.current;
		return seen != null ? seen : load();
	}

	/**
	 * The personality in use, after a cheap look at whether the file has
	 * changed since it was read. Meant to be called before every prompt: most
	 * calls return at once, and the file is only opened when its size or
	 * modification time is different, or when it has appeared or gone.
	 */
	public synchronized PersonalityPrompt refreshIfChanged() {
		PersonalityPrompt seen = this.current;
		if (seen == null) {
			return load();
		}
		if (this.file == null) {
			return seen;
		}
		long now = this.clock.getAsLong();
		if (this.checkedOnce && now >= this.lastCheckMillis && now - this.lastCheckMillis < CHECK_INTERVAL_MILLIS) {
			return seen;
		}
		this.checkedOnce = true;
		this.lastCheckMillis = now;
		if (signature().equals(this.loadedSignature)) {
			return seen;
		}
		return load();
	}

	/**
	 * Reads the file now and returns what Falsity will use. Creates the file
	 * with the default text when it does not exist. Never throws: whatever goes
	 * wrong, the answer is the built-in default and a note saying why.
	 */
	public synchronized PersonalityPrompt load() {
		PersonalityPrompt previous = this.current;
		PersonalityPrompt loaded = read();
		this.current = loaded;
		this.loadedSignature = this.file == null ? null : signature();
		this.checkedOnce = true;
		this.lastCheckMillis = this.clock.getAsLong();
		if (previous == null || !previous.equals(loaded)) {
			announce(loaded);
		}
		return loaded;
	}

	private PersonalityPrompt read() {
		if (this.file == null) {
			return fallback("");
		}
		boolean created = false;
		try {
			if (Files.isDirectory(this.file)) {
				return fallback("the personality file is a folder, not a text file");
			}
			if (Files.notExists(this.file)) {
				created = writeDefault();
			}
			Parsed parsed = parse(decode(readHead(this.file)));
			if (parsed.text().isEmpty()) {
				return fallback(parsed.hadNotes()
					? "the personality file has no text in it, only notes"
					: "the personality file is empty");
			}

			List<String> notes = new ArrayList<>();
			if (created) {
				notes.add("the file did not exist, so it was created");
			}
			if (parsed.clipped()) {
				notes.add("the file has " + parsed.originalLength() + " characters of text and only the first "
					+ parsed.text().length() + " are used");
			}
			if (parsed.redacted()) {
				notes.add("something in the file looked like an API key and was left out; keys do not belong in this file");
			}
			return new PersonalityPrompt(
				parsed.text(), PersonalityPrompt.Origin.FILE, String.join("; ", notes), this.file,
				parsed.text().equals(Defaults.TEXT));
		} catch (IOException | RuntimeException exception) {
			// The kind of failure says enough; nothing from the file is repeated.
			return fallback("the personality file could not be " + (created ? "read back" : "read or created")
				+ " (" + reason(exception) + ")");
		}
	}

	private static String reason(Exception exception) {
		String name = exception.getClass().getSimpleName();
		return switch (name) {
			case "AccessDeniedException" -> "access denied";
			case "NoSuchFileException" -> "no such file";
			case "FileAlreadyExistsException", "NotDirectoryException" -> "something else is in the way of its folder";
			default -> name;
		};
	}

	private PersonalityPrompt fallback(String reason) {
		return new PersonalityPrompt(Defaults.TEXT, PersonalityPrompt.Origin.DEFAULT, reason, this.file, true);
	}

	/** Writes the default file. Returns whether this call created it. */
	private boolean writeDefault() throws IOException {
		Path parent = this.file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try {
			Files.writeString(this.file, Defaults.FILE_CONTENTS, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			return true;
		} catch (FileAlreadyExistsException appearedMeanwhile) {
			// Someone else wrote it between the check and now: theirs is the one to read.
			return false;
		}
	}

	/** Says which personality is in use. The text itself is never logged. */
	private void announce(PersonalityPrompt prompt) {
		if (prompt.fellBack()) {
			LOGGER.warn(
				"Falsity's personality: {}. The built-in default ({} characters) is in use. File: {}. "
					+ "Put some text in the file, or delete it and a fresh one is written.",
				prompt.detail(), prompt.text().length(), this.file);
		} else if (this.file != null) {
			LOGGER.info("Falsity's personality: using {}. File: {}", prompt.describe(), this.file);
		}
	}

	private Signature signature() {
		try {
			if (!Files.exists(this.file)) {
				return new Signature(false, false, 0L, 0L);
			}
			boolean regular = Files.isRegularFile(this.file);
			return new Signature(true, regular, Files.getLastModifiedTime(this.file).toMillis(), regular ? Files.size(this.file) : 0L);
		} catch (IOException | RuntimeException exception) {
			return new Signature(false, false, -1L, -1L);
		}
	}

	private static byte[] readHead(Path path) throws IOException {
		try (InputStream stream = Files.newInputStream(path)) {
			return stream.readNBytes(MAX_FILE_BYTES);
		}
	}

	/**
	 * Turns the file's bytes into text. Editors on Windows save in several
	 * encodings, so a byte-order mark is honoured and a file that is not valid
	 * UTF-8 is read as Latin-1 instead of being rejected.
	 */
	static String decode(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
			return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
			return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
			return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException notUtf8) {
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * Works out the personality text in a file's contents: note lines (those
	 * starting with {@code #}) are dropped, stray control characters removed,
	 * anything shaped like an API key left out, and the result cut to
	 * {@link #MAX_CHARS} at the end of a sentence where one is near.
	 */
	public static Parsed parse(String raw) {
		if (raw == null || raw.isEmpty()) {
			return new Parsed("", 0, false, false, false);
		}
		String normalised = INVISIBLE.matcher(raw.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').replace('\u00A0', ' '))
			.replaceAll("");
		normalised = CONTROL.matcher(normalised).replaceAll("");

		boolean hadNotes = false;
		StringBuilder kept = new StringBuilder();
		for (String line : normalised.split("\n", -1)) {
			if (line.stripLeading().startsWith("#")) {
				hadNotes = true;
				continue;
			}
			kept.append(line.stripTrailing()).append('\n');
		}
		String text = kept.toString().replaceAll("\n{3,}", "\n\n").strip();

		boolean redacted = SECRET.matcher(text).find();
		if (redacted) {
			text = SECRET.matcher(text).replaceAll("[removed]");
		}

		int originalLength = text.length();
		boolean clipped = originalLength > MAX_CHARS;
		if (clipped) {
			text = clip(text);
		}
		return new Parsed(text, originalLength, clipped, redacted, hadNotes);
	}

	/** Cuts at the limit, preferring the end of a sentence or a line that is not far before it. */
	private static String clip(String text) {
		String head = text.substring(0, MAX_CHARS);
		int sentence = -1;
		for (int index = head.length() - 1; index >= MAX_CHARS - 300; index--) {
			char character = head.charAt(index);
			boolean endsSentence = (character == '.' || character == '!' || character == '?')
				&& (index + 1 >= text.length() || Character.isWhitespace(text.charAt(index + 1)));
			if (endsSentence || character == '\n') {
				sentence = index;
				break;
			}
		}
		if (sentence >= 0) {
			return head.substring(0, sentence + 1).stripTrailing();
		}
		int space = head.lastIndexOf(' ');
		if (space >= MAX_CHARS - 60 && Character.isLetterOrDigit(text.charAt(MAX_CHARS))) {
			head = head.substring(0, space);
		}
		return head.stripTrailing();
	}

	/** The bundled default, read once. */
	private static final class Defaults {
		static final String FILE_CONTENTS;
		static final String TEXT;

		static {
			String contents = "";
			try (InputStream stream = PersonalityPromptStore.class.getResourceAsStream(DEFAULT_RESOURCE)) {
				if (stream != null) {
					contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
				}
			} catch (IOException | RuntimeException exception) {
				contents = "";
			}
			String text = parse(contents).text();
			if (text.isEmpty()) {
				LOGGER.warn("Falsity's bundled default personality is missing from the mod file; a short built-in text is used instead.");
				text = LAST_RESORT_TEXT;
				contents = "# Falsity's personality. Lines starting with # are notes and are not sent.\n\n" + LAST_RESORT_TEXT + "\n";
			}
			FILE_CONTENTS = contents;
			TEXT = text;
		}
	}
}
