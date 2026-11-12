package com.falsity.personality;

import java.nio.file.Path;

/**
 * The personality text Falsity is running with, and where it came from.
 *
 * <p>This is the configurable layer of the prompt: who Falsity is and how it
 * talks. It is read from {@code config/falsity/personality.txt} by
 * {@link PersonalityPromptStore}; when that file is missing, empty or
 * unreadable the built-in default is used instead, so there is always a
 * personality.
 *
 * @param text           the personality text as it is sent, never blank
 * @param origin         whether the text came from the file or is the built-in default
 * @param detail         one plain sentence about the load: its size, or why the default is in use
 * @param file           the file that was looked at, or null when no file is configured
 * @param matchesDefault whether the text is word for word the built-in default
 */
public record PersonalityPrompt(String text, Origin origin, String detail, Path file, boolean matchesDefault) {
	/** Where a personality text came from. */
	public enum Origin {
		/** Read from the personality file. */
		FILE,
		/** The built-in default, because the file could not supply a text. */
		DEFAULT
	}

	public PersonalityPrompt {
		text = text == null ? "" : text;
		detail = detail == null ? "" : detail;
	}

	/** Whether the server owner's own text is in use, rather than the default wording. */
	public boolean custom() {
		return this.origin == Origin.FILE && !this.matchesDefault;
	}

	/** Whether the file was unusable and the built-in default took its place. */
	public boolean fellBack() {
		return this.origin == Origin.DEFAULT && this.file != null;
	}

	/**
	 * The file as a short path for chat ({@code config/falsity/personality.txt}),
	 * or empty when there is none.
	 */
	public String shortPath() {
		if (this.file == null) {
			return "";
		}
		int count = this.file.getNameCount();
		Path shown = count <= 3 ? this.file : this.file.subpath(count - 3, count);
		return shown.toString().replace('\\', '/');
	}

	/**
	 * One line for logs and the status command. It names the file and the size
	 * of the text and never includes the text itself.
	 */
	public String describe() {
		StringBuilder line = new StringBuilder();
		if (this.origin == Origin.FILE) {
			line.append(this.matchesDefault ? "the default personality" : "a custom personality");
			line.append(" from ").append(shortPath());
		} else {
			line.append("the built-in default personality");
		}
		line.append(" (").append(this.text.length()).append(" characters");
		if (!this.detail.isEmpty()) {
			line.append("; ").append(this.detail);
		}
		line.append(')');
		return line.toString();
	}
}
