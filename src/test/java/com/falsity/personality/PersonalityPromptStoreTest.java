package com.falsity.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The personality file: created when missing, read when present, and never
 * able to leave Falsity without a personality.
 */
class PersonalityPromptStoreTest {
	private static final String CUSTOM =
		"You are Falsity, a stern little guardian who speaks in clipped sentences and counts everything twice.";

	@TempDir
	Path configDir;

	private Path file() {
		return PersonalityPromptStore.fileIn(this.configDir);
	}

	private PersonalityPromptStore store() {
		return new PersonalityPromptStore(file());
	}

	private void write(String contents) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), contents, StandardCharsets.UTF_8);
	}

	// ---- creation ---------------------------------------------------------

	@Test
	void theFileLivesInItsOwnFolderUnderTheConfigDirectory() {
		assertEquals(this.configDir.resolve("falsity").resolve("personality.txt"), file());
	}

	@Test
	void aMissingFileIsCreatedWithTheDefault() throws IOException {
		assertFalse(Files.exists(file()));

		PersonalityPrompt prompt = store().load();

		assertTrue(Files.isRegularFile(file()), "the file is written, folder included");
		assertEquals(PersonalityPromptStore.defaultFileContents(), Files.readString(file(), StandardCharsets.UTF_8));
		assertEquals(PersonalityPrompt.Origin.FILE, prompt.origin(), "what was just written is what is read");
		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertTrue(prompt.matchesDefault());
		assertFalse(prompt.custom());
		assertFalse(prompt.fellBack());
		assertTrue(prompt.detail().contains("created"), prompt.detail());
	}

	@Test
	void theCreatedFileExplainsItselfToWhoeverOpensIt() {
		String contents = PersonalityPromptStore.defaultFileContents();

		assertTrue(contents.startsWith("#"), "it opens with notes for the owner");
		assertTrue(contents.contains("Lines that start with # are notes"), contents);
		assertTrue(contents.contains("/falsity personality reload"), contents);
		assertTrue(contents.contains(String.valueOf(PersonalityPromptStore.MAX_CHARS)), "the limit it states is the real one");
		assertTrue(contents.contains("Delete this file"), contents);
		assertTrue(contents.contains("horror stages"), "it says the stages are a separate layer");
		assertTrue(contents.endsWith("\n"));
	}

	@Test
	void theDefaultTextIsTheFileWithoutItsNotes() {
		String text = PersonalityPromptStore.defaultText();

		assertFalse(text.contains("#"), text);
		assertTrue(text.startsWith("Identity: You are Falsity"), text);
		assertTrue(text.length() >= 400 && text.length() <= PersonalityPromptStore.MAX_CHARS, "length " + text.length());
		assertEquals(text, PersonalityPromptStore.parse(PersonalityPromptStore.defaultFileContents()).text());
		assertNotEquals(PersonalityPromptStore.LAST_RESORT_TEXT, text, "the bundled file was found and used");
	}

	@Test
	void anExistingFileIsNeverOverwritten() throws IOException {
		write(CUSTOM + "\n");

		store().load();
		store().load();

		assertEquals(CUSTOM + "\n", Files.readString(file(), StandardCharsets.UTF_8));
	}

	// ---- loading ----------------------------------------------------------

	@Test
	void aCustomPersonalityIsLoadedAsWritten() throws IOException {
		write(CUSTOM);

		PersonalityPrompt prompt = store().load();

		assertEquals(CUSTOM, prompt.text());
		assertEquals(PersonalityPrompt.Origin.FILE, prompt.origin());
		assertTrue(prompt.custom());
		assertFalse(prompt.matchesDefault());
		assertEquals(file(), prompt.file());
		assertEquals("", prompt.detail());
	}

	@Test
	void notesAreNotPartOfThePersonality() throws IOException {
		write("# a note for me\n\n" + CUSTOM + "\n   # an indented note\nShe hums when it rains.\n\n\n\n# the end\n");

		PersonalityPrompt prompt = store().load();

		assertEquals(CUSTOM + "\nShe hums when it rains.", prompt.text());
	}

	@Test
	void paragraphsSurviveButStrayWhitespaceDoesNot() throws IOException {
		write("\r\n\r\n  First paragraph.   \r\n\r\n\r\n\r\nSecond\tparagraph.\u200B\r\n\r\n");

		assertEquals("First paragraph.\n\nSecond paragraph.", store().load().text());
	}

	@Test
	void aByteOrderMarkAndOtherEncodingsAreRead() throws IOException {
		Files.createDirectories(file().getParent());

		byte[] utf8 = CUSTOM.getBytes(StandardCharsets.UTF_8);
		byte[] withMark = new byte[utf8.length + 3];
		withMark[0] = (byte) 0xEF;
		withMark[1] = (byte) 0xBB;
		withMark[2] = (byte) 0xBF;
		System.arraycopy(utf8, 0, withMark, 3, utf8.length);
		Files.write(file(), withMark);
		assertEquals(CUSTOM, store().load().text(), "UTF-8 with a byte-order mark, as Notepad writes it");

		byte[] utf16 = ("\uFEFF" + CUSTOM).getBytes(StandardCharsets.UTF_16LE);
		Files.write(file(), utf16);
		assertEquals(CUSTOM, store().load().text(), "UTF-16, Notepad's \"Unicode\"");

		Files.write(file(), "Caf\u00E9 owner, na\u00EFve and kind.".getBytes(StandardCharsets.ISO_8859_1));
		assertEquals("Caf\u00E9 owner, na\u00EFve and kind.", store().load().text(), "not valid UTF-8: read as Latin-1, not rejected");

		Files.writeString(file(), "Caf\u00E9 owner, na\u00EFve and kind.", StandardCharsets.UTF_8);
		assertEquals("Caf\u00E9 owner, na\u00EFve and kind.", store().load().text());
	}

	// ---- fallback ---------------------------------------------------------

	@Test
	void anEmptyFileFallsBackToTheDefaultAndIsLeftAlone() throws IOException {
		write("");

		PersonalityPrompt prompt = store().load();

		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertEquals(PersonalityPrompt.Origin.DEFAULT, prompt.origin());
		assertTrue(prompt.fellBack());
		assertFalse(prompt.custom());
		assertTrue(prompt.detail().contains("empty"), prompt.detail());
		assertEquals("", Files.readString(file(), StandardCharsets.UTF_8), "the owner's file is not touched");
	}

	@Test
	void aFileOfOnlyWhitespaceCountsAsEmpty() throws IOException {
		write("  \n\t\n\r\n   ");

		PersonalityPrompt prompt = store().load();

		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertEquals(PersonalityPrompt.Origin.DEFAULT, prompt.origin());
		assertTrue(prompt.detail().contains("empty"), prompt.detail());
	}

	@Test
	void aFileOfOnlyNotesFallsBackToTheDefault() throws IOException {
		write("# I deleted everything\n# and left only this\n");

		PersonalityPrompt prompt = store().load();

		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertEquals(PersonalityPrompt.Origin.DEFAULT, prompt.origin());
		assertTrue(prompt.detail().contains("only notes"), prompt.detail());
	}

	@Test
	void aFolderWhereTheFileShouldBeFallsBackToTheDefault() throws IOException {
		Files.createDirectories(file());

		PersonalityPrompt prompt = store().load();

		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertEquals(PersonalityPrompt.Origin.DEFAULT, prompt.origin());
		assertTrue(prompt.detail().contains("folder"), prompt.detail());
		assertTrue(Files.isDirectory(file()), "nothing was deleted to make room");
	}

	@Test
	void aFileThatCannotBeCreatedFallsBackToTheDefault() throws IOException {
		// The folder the file belongs in is itself a file, so nothing can be written there.
		Files.writeString(this.configDir.resolve("falsity"), "in the way");

		PersonalityPrompt prompt = store().load();

		assertEquals(PersonalityPromptStore.defaultText(), prompt.text());
		assertEquals(PersonalityPrompt.Origin.DEFAULT, prompt.origin());
		assertTrue(prompt.fellBack());
		assertTrue(prompt.detail().contains("could not be"), prompt.detail());
		assertEquals("in the way", Files.readString(this.configDir.resolve("falsity")));
	}

	@Test
	void aStoreWithoutAFileAlwaysGivesTheDefault() {
		PersonalityPromptStore store = PersonalityPromptStore.builtIn();

		assertNull(store.file());
		assertEquals(PersonalityPromptStore.defaultText(), store.current().text());
		assertEquals(PersonalityPromptStore.defaultText(), store.load().text());
		assertEquals(PersonalityPromptStore.defaultText(), store.refreshIfChanged().text());
		assertFalse(store.current().fellBack(), "there was no file to fall back from");
		assertEquals("", store.current().shortPath());
	}

	@Test
	void thePersonalityIsNeverBlankWhateverTheFileHolds() throws IOException {
		for (String contents : new String[] {"", " ", "#", "#\n#\n", "\u0000\u0001\u0002", "\uFEFF", "\n\n\n", "x"}) {
			write(contents);
			PersonalityPrompt prompt = store().load();
			assertFalse(prompt.text().isBlank(), "for a file holding [" + contents.replace("\n", "\\n") + "]");
		}
	}

	// ---- limits and secrets -----------------------------------------------

	@Test
	void anOverLongFileIsCutAtTheLimitOnASentence() throws IOException {
		String sentence = "Falsity counts the stars and names each one after a block. ";
		write(sentence.repeat(60));

		PersonalityPrompt prompt = store().load();

		assertTrue(prompt.text().length() <= PersonalityPromptStore.MAX_CHARS, "length " + prompt.text().length());
		assertTrue(prompt.text().length() > PersonalityPromptStore.MAX_CHARS - 300, "most of the allowance is used");
		assertTrue(prompt.text().endsWith("after a block."), "cut at the end of a sentence: ..." + prompt.text().substring(prompt.text().length() - 40));
		assertEquals(PersonalityPrompt.Origin.FILE, prompt.origin(), "a long file is still the owner's file");
		assertTrue(prompt.detail().contains("only the first " + prompt.text().length() + " are used"), prompt.detail());
		assertTrue(prompt.detail().contains(String.valueOf(sentence.repeat(60).strip().length())), prompt.detail());
	}

	@Test
	void textWithNoSentenceEndsIsStillCut() throws IOException {
		write("word ".repeat(1000));
		assertTrue(store().load().text().length() <= PersonalityPromptStore.MAX_CHARS);

		write("x".repeat(10_000));
		assertEquals(PersonalityPromptStore.MAX_CHARS, store().load().text().length());
	}

	@Test
	void aTextExactlyAtTheLimitIsNotCut() throws IOException {
		String exact = "y".repeat(PersonalityPromptStore.MAX_CHARS);
		write(exact);

		PersonalityPrompt prompt = store().load();

		assertEquals(exact, prompt.text());
		assertEquals("", prompt.detail());
	}

	@Test
	void anEnormousFileIsNotReadWhole() throws IOException {
		write("Falsity is brave. " + "z".repeat(2 * PersonalityPromptStore.MAX_FILE_BYTES));

		PersonalityPrompt prompt = store().load();

		assertTrue(prompt.text().startsWith("Falsity is brave."), prompt.text().substring(0, 30));
		assertTrue(prompt.text().length() <= PersonalityPromptStore.MAX_CHARS);
	}

	@Test
	void somethingShapedLikeAKeyIsLeftOutAndNotRepeated() throws IOException {
		String key = "gsk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP";
		write("You are Falsity. My key is " + key + " and also Bearer abcdefghij1234567890 just in case.");

		PersonalityPrompt prompt = store().load();

		assertFalse(prompt.text().contains(key), prompt.text());
		assertFalse(prompt.text().contains("gsk_"), prompt.text());
		assertFalse(prompt.text().contains("abcdefghij1234567890"), prompt.text());
		assertTrue(prompt.text().startsWith("You are Falsity. My key is [removed]"), prompt.text());
		assertTrue(prompt.detail().contains("API key"), prompt.detail());
		assertFalse(prompt.detail().contains("gsk_"), "the note does not repeat it");
		assertFalse(prompt.describe().contains("gsk_"), "nor does the log line");
	}

	@Test
	void theDefaultFileHoldsNoKeyAndNoSecretSetting() {
		String contents = PersonalityPromptStore.defaultFileContents();

		assertFalse(contents.contains("gsk_"), contents);
		assertFalse(contents.toLowerCase().contains("groq_api_key"), contents);
		assertFalse(contents.toLowerCase().contains("apikey"), contents);
		assertFalse(PersonalityPromptStore.parse(contents).redacted());
	}

	@Test
	void whatIsLoggedNamesTheFileAndSizeButNeverTheText() throws IOException {
		write(CUSTOM);
		PersonalityPrompt custom = store().load();
		assertEquals("a custom personality from falsity/personality.txt (" + CUSTOM.length() + " characters)",
			custom.describe().replace(this.configDir.getFileName() + "/", ""));
		assertFalse(custom.describe().contains("stern"), custom.describe());
		assertTrue(custom.shortPath().endsWith("falsity/personality.txt"), custom.shortPath());

		write("");
		PersonalityPrompt fallback = store().load();
		assertTrue(fallback.describe().startsWith("the built-in default personality ("), fallback.describe());
		assertTrue(fallback.describe().contains("the personality file is empty"), fallback.describe());
		assertFalse(fallback.describe().contains("Identity:"), fallback.describe());

		Files.delete(file());
		PersonalityPrompt created = store().load();
		assertTrue(created.describe().startsWith("the default personality from "), created.describe());
		assertFalse(created.describe().contains("Identity:"), created.describe());
	}

	// ---- reloading --------------------------------------------------------

	@Test
	void loadReadsTheFileAgainEveryTime() throws IOException {
		PersonalityPromptStore store = store();
		assertEquals(PersonalityPromptStore.defaultText(), store.load().text());

		write(CUSTOM);
		assertEquals(CUSTOM, store.load().text());
		assertEquals(CUSTOM, store.current().text());

		write("Another Falsity entirely.");
		assertEquals("Another Falsity entirely.", store.load().text());
	}

	@Test
	void currentDoesNotTouchTheFileOnceLoaded() throws IOException {
		PersonalityPromptStore store = store();
		PersonalityPrompt first = store.current();
		write(CUSTOM);

		assertSame(first, store.current(), "only load and refreshIfChanged look at the file");
	}

	@Test
	void aSavedChangeIsPickedUpWithoutBeingAsked() throws IOException {
		AtomicLong clock = new AtomicLong(1_000_000L);
		PersonalityPromptStore store = new PersonalityPromptStore(file(), clock::get);
		assertEquals(PersonalityPromptStore.defaultText(), store.refreshIfChanged().text());

		write(CUSTOM);
		touch(5_000L);
		assertEquals(PersonalityPromptStore.defaultText(), store.refreshIfChanged().text(),
			"the file is not looked at again within the same moment");

		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		assertEquals(CUSTOM, store.refreshIfChanged().text(), "a moment later the saved file is in use");

		String rewritten = CUSTOM.replace("stern", "merry");
		assertEquals(CUSTOM.length(), rewritten.length(), "same size, so only the time tells them apart");
		write(rewritten);
		touch(9_000L);
		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		assertEquals(rewritten, store.refreshIfChanged().text());
	}

	@Test
	void anUnchangedFileIsNotReadAgain() throws IOException {
		AtomicLong clock = new AtomicLong(1_000_000L);
		write(CUSTOM);
		PersonalityPromptStore store = new PersonalityPromptStore(file(), clock::get);
		PersonalityPrompt first = store.refreshIfChanged();

		for (int step = 0; step < 5; step++) {
			clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS * 3);
			assertSame(first, store.refreshIfChanged(), "same file, same answer, no new read");
		}
	}

	@Test
	void emptyingTheFileWhileRunningFallsBackAndFillingItRecovers() throws IOException {
		AtomicLong clock = new AtomicLong(1_000_000L);
		write(CUSTOM);
		PersonalityPromptStore store = new PersonalityPromptStore(file(), clock::get);
		assertEquals(CUSTOM, store.refreshIfChanged().text());

		write("");
		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		PersonalityPrompt emptied = store.refreshIfChanged();
		assertEquals(PersonalityPromptStore.defaultText(), emptied.text());
		assertTrue(emptied.fellBack());

		write(CUSTOM);
		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		assertEquals(CUSTOM, store.refreshIfChanged().text());
	}

	@Test
	void deletingTheFileWhileRunningBringsTheDefaultBack() throws IOException {
		AtomicLong clock = new AtomicLong(1_000_000L);
		write(CUSTOM);
		PersonalityPromptStore store = new PersonalityPromptStore(file(), clock::get);
		assertEquals(CUSTOM, store.refreshIfChanged().text());

		Files.delete(file());
		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		PersonalityPrompt restored = store.refreshIfChanged();

		assertEquals(PersonalityPromptStore.defaultText(), restored.text());
		assertTrue(Files.isRegularFile(file()), "a fresh default file is written");
		assertEquals(PersonalityPromptStore.defaultFileContents(), Files.readString(file(), StandardCharsets.UTF_8));

		clock.addAndGet(PersonalityPromptStore.CHECK_INTERVAL_MILLIS);
		assertSame(restored, store.refreshIfChanged(), "and writing it does not count as a change to read again");
	}

	@Test
	void aClockThatJumpsBackwardsDoesNotStopChangesBeingSeen() throws IOException {
		AtomicLong clock = new AtomicLong(1_000_000L);
		PersonalityPromptStore store = new PersonalityPromptStore(file(), clock::get);
		store.refreshIfChanged();

		write(CUSTOM);
		touch(5_000L);
		clock.set(10L);

		assertEquals(CUSTOM, store.refreshIfChanged().text());
	}

	@Test
	void twoStoresOnTheSameFileAgree() throws IOException {
		write(CUSTOM);

		assertEquals(store().load(), store().load(), "a restart reads the same personality");
	}

	/** Moves the file's modification time on, so a change is visible whatever the file system's resolution. */
	private void touch(long extraMillis) throws IOException {
		Files.setLastModifiedTime(file(), FileTime.fromMillis(Files.getLastModifiedTime(file()).toMillis() + extraMillis));
	}
}
