package com.falsity.ai;

import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw Groq output into dialogue plus a validated action request.
 *
 * <p>Groq only ever produces dialogue and one high-level, structured request.
 * The request is either a legacy tag ({@code [action:sit]}) or a JSON object
 * ({@code [action:{"type":"give_item","item":"diamond","count":32}]}, also
 * accepted as a line starting with {@code ACTION:}). The type and every
 * argument key are checked against {@link ActionType}'s allowlist: anything the
 * model invents (commands, code, unknown actions or unknown arguments) is
 * rejected and the dialogue is used without it. AI text can never execute game
 * actions directly.
 */
public final class AIResponseParser {
	/** Longest dialogue accepted, in characters. Longer replies are cut. */
	public static final int MAX_DIALOGUE_CHARS = 300;

	private static final Gson GSON = new Gson();

	private static final Pattern JSON_ACTION_TAG =
		Pattern.compile("\\[action:\\s*(\\{[^{}]*\\})\\]", Pattern.DOTALL);
	private static final Pattern JSON_ACTION_LINE =
		Pattern.compile("(?m)^\\s*ACTION:\\s*(\\{[^{}]*\\})\\s*$", Pattern.DOTALL);
	private static final Pattern LEGACY_ACTION_TAG = Pattern.compile("\\[action:([a-z_]+)\\]");

	private AIResponseParser() {
	}

	/** Behaviour suggestions the server is willing to act on. */
	public enum SuggestedAction {
		NONE,
		SIT,
		FOLLOW,
		GREET
	}

	/**
	 * Parsed result: clean dialogue, a legacy suggestion, and an optional
	 * structured request (which takes precedence when present).
	 */
	public record ParsedResponse(String dialogue, SuggestedAction action, ActionRequest structuredAction) {
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

		ActionRequest structured = null;
		SuggestedAction action = SuggestedAction.NONE;

		Matcher jsonTag = JSON_ACTION_TAG.matcher(text);
		if (jsonTag.find()) {
			structured = parseActionJson(jsonTag.group(1)).orElse(null);
			text = jsonTag.replaceAll("").trim();
		} else {
			Matcher jsonLine = JSON_ACTION_LINE.matcher(text);
			if (jsonLine.find()) {
				structured = parseActionJson(jsonLine.group(1)).orElse(null);
				text = jsonLine.replaceAll("").trim();
			}
		}

		if (structured == null) {
			Matcher legacy = LEGACY_ACTION_TAG.matcher(text);
			if (legacy.find()) {
				action = ActionValidator.validate(legacy.group(1)).orElse(SuggestedAction.NONE);
				text = legacy.replaceAll("").trim();
			}
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

		return Optional.of(new ParsedResponse(text, action, structured));
	}

	/**
	 * Parses and validates one JSON action object. Returns empty for anything
	 * malformed, for unknown types, or for arguments the type does not allow.
	 */
	static Optional<ActionRequest> parseActionJson(String json) {
		if (json == null || json.isBlank()) {
			return Optional.empty();
		}
		try {
			JsonObject parsed = GSON.fromJson(json, JsonObject.class);
			if (parsed == null || !parsed.has("type")) {
				return Optional.empty();
			}

			String typeName = parsed.get("type").getAsString().trim();
			ActionType type = ActionType.NONE;
			for (ActionType candidate : ActionType.values()) {
				if (candidate.name().equalsIgnoreCase(typeName)) {
					type = candidate;
					break;
				}
			}
			if (type == ActionType.NONE || !type.isReal()) {
				return Optional.empty();
			}

			Map<String, Object> args = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : parsed.entrySet()) {
				String key = entry.getKey();
				if ("type".equals(key)) {
					continue;
				}
				if (!type.allowsArg(key)) {
					// An argument the action does not declare: reject the whole
					// request rather than silently dropping or acting on it.
					return Optional.empty();
				}
				JsonElement value = entry.getValue();
				if (value == null || value.isJsonNull()) {
					continue;
				}
				if (value instanceof JsonPrimitive primitive) {
					if (primitive.isNumber()) {
						args.put(key, primitive.getAsDouble());
					} else {
						args.put(key, primitive.getAsString());
					}
				}
			}

			return Optional.of(ActionRequest.of(type, args));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}
}

/**
 * Allowlist for AI-suggested legacy actions.
 *
 * <p>Only the three harmless actions Falsity has always supported are allowed
 * here. Everything else (including anything that looks like a command, a path
 * or code) is rejected. The server maps validated actions to its own methods;
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
