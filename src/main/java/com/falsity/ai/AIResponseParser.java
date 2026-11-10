package com.falsity.ai;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw Groq output into dialogue plus an optional behaviour suggestion.
 *
 * <p>Groq only ever produces dialogue and a high-level suggestion. The
 * suggestion is a strict tag ({@code [action:sit]} etc.) validated against
 * an allowlist by {@link ActionValidator}: anything else the model invents
 * (commands, code, unknown actions) is rejected and the dialogue is used
 * without it. AI text can never execute game actions directly.
 */
public final class AIResponseParser {
	/** Longest dialogue accepted, in characters. Longer replies are cut. */
	public static final int MAX_DIALOGUE_CHARS = 300;

	private static final Pattern ACTION_PATTERN = Pattern.compile("\\[action:([a-z_]+)\\]");

	private AIResponseParser() {
	}

	/** Behaviour suggestions the server is willing to act on. */
	public enum SuggestedAction {
		NONE,
		SIT,
		FOLLOW,
		GREET
	}

	/** Parsed result: clean dialogue plus a validated suggestion. */
	public record ParsedResponse(String dialogue, SuggestedAction action) {
	}

	/**
	 * Parses model output. Returns empty when there is no usable dialogue
	 * (blank, or only an action tag), so callers fall back offline.
	 */
	public static Optional<ParsedResponse> parse(String rawContent) {
		if (rawContent == null) {
			return Optional.empty();
		}

		String text = rawContent.trim();
		if (text.isEmpty()) {
			return Optional.empty();
		}

		SuggestedAction action = SuggestedAction.NONE;
		Matcher matcher = ACTION_PATTERN.matcher(text);
		if (matcher.find()) {
			action = ActionValidator.validate(matcher.group(1)).orElse(SuggestedAction.NONE);
			text = matcher.replaceAll("").trim();
		}

		// Strip surrounding quotes the model sometimes adds.
		if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
			text = text.substring(1, text.length() - 1).trim();
		}

		if (text.isEmpty()) {
			return Optional.empty();
		}
		if (text.length() > MAX_DIALOGUE_CHARS) {
			text = text.substring(0, MAX_DIALOGUE_CHARS).trim();
		}

		return Optional.of(new ParsedResponse(text, action));
	}
}

/**
 * Allowlist for AI-suggested actions.
 *
 * <p>Only the three harmless actions Falsity already supports are allowed.
 * Everything else (including anything that looks like a command, a path or
 * code) is rejected. The server maps validated actions to its own methods;
 * the model never touches game logic directly.
 */
final class ActionValidator {
	private ActionValidator() {
	}

	static Optional<AIResponseParser.SuggestedAction> validate(String rawAction) {
		if (rawAction == null) {
			return Optional.empty();
		}
		return switch (rawAction.trim().toLowerCase()) {
			case "sit" -> Optional.of(AIResponseParser.SuggestedAction.SIT);
			case "follow" -> Optional.of(AIResponseParser.SuggestedAction.FOLLOW);
			case "greet" -> Optional.of(AIResponseParser.SuggestedAction.GREET);
			default -> Optional.empty();
		};
	}
}
