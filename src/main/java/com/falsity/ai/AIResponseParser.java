package com.falsity.ai;

import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw Groq output into dialogue plus a validated action request.
 *
 * <p>Groq only ever produces dialogue and one high-level, structured request.
 * The request is either a bare tag ({@code [action:sit]}) or a JSON object
 * ({@code [action:{"type":"give_item","item":"diamond","count":32}]}, also
 * accepted as a line starting with {@code ACTION:}). The type and every
 * argument key are checked against {@link ActionType}'s allowlist: anything the
 * model invents (commands, code, unknown actions or unknown arguments) is
 * rejected and the dialogue is used without it. AI text can never execute game
 * actions directly.
 *
 * <p>What is left is tidied into one line of plain chat: the model's thinking,
 * every action tag (understood or not), a "Falsity:" label, markdown emphasis
 * and line breaks are removed, because a player must only ever see the words.
 */
public final class AIResponseParser {
	/** Longest dialogue accepted, in characters. Longer replies are cut. */
	public static final int MAX_DIALOGUE_CHARS = 300;

	private static final Gson GSON = new Gson();

	/**
	 * Where an action tag starts: {@code [action:} in any case and spacing
	 * (group 1), or a line that begins with {@code ACTION:}.
	 */
	private static final Pattern TAG_OPENER =
		Pattern.compile("(?:(?i:(\\[)[ \\t]*action[ \\t]*:)|(?m:^[ \\t]*)ACTION:)[ \\t]*");
	private static final Pattern THINK_BLOCK =
		Pattern.compile("<think>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	/** "Falsity:" in front of the reply; the chat line already says who is speaking. */
	private static final Pattern SPEAKER_LABEL =
		Pattern.compile("^[\\s*_]*falsity[\\s*_]*:[\\s*_]*(?=\\S)", Pattern.CASE_INSENSITIVE);

	/** Markdown bold and code marks, which chat would show as literal symbols. */
	private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");

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
	 *
	 * @param rejectedAction whether the reply carried an action tag that was
	 *     not accepted, so the words stand alone; worth a line in the log
	 */
	public record ParsedResponse(String dialogue, SuggestedAction action, ActionRequest structuredAction, boolean rejectedAction) {
	}

	/**
	 * One action tag in a reply, from {@code start} to {@code end}: its JSON
	 * object, or the bare word between the brackets. Both are null for a tag
	 * the model left unfinished.
	 */
	private record Tag(int start, int end, String json, String word) {
	}

	/** What the tags in a reply asked for. */
	private record Found(ActionRequest structured, SuggestedAction legacy, boolean tagPresent) {
		static final Found NOTHING = new Found(null, SuggestedAction.NONE, false);

		boolean accepted() {
			return this.structured != null || this.legacy != SuggestedAction.NONE;
		}

		boolean rejected() {
			return this.tagPresent && !accepted();
		}
	}

	/**
	 * Parses model output. Returns empty when there is no usable dialogue
	 * (blank, or only an action tag), so callers fall back offline.
	 */
	public static Optional<ParsedResponse> parse(String rawContent) {
		if (rawContent == null) {
			return Optional.empty();
		}

		String text = stripReasoning(rawContent).trim();
		if (text.isEmpty()) {
			return Optional.empty();
		}

		Found found = findAction(text);
		String dialogue = tidy(text);
		if (dialogue.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ParsedResponse(dialogue, found.legacy(), found.structured(), found.rejected()));
	}

	/**
	 * The structured action in a reply, even when the reply is nothing but the
	 * tag. A model that answers "follow me" with only {@code [action:{...}]}
	 * still meant the action; the handler's own message becomes the dialogue.
	 */
	public static Optional<ActionRequest> extractAction(String rawContent) {
		if (rawContent == null) {
			return Optional.empty();
		}
		Found found = findAction(stripReasoning(rawContent));
		if (found.structured() != null) {
			return Optional.of(found.structured());
		}
		return switch (found.legacy()) {
			case SIT -> Optional.of(ActionRequest.of(ActionType.SIT));
			case FOLLOW -> Optional.of(ActionRequest.of(ActionType.FOLLOW));
			case GREET -> Optional.of(ActionRequest.of(ActionType.GREET));
			case NONE -> Optional.empty();
		};
	}

	/**
	 * Reads the action a reply asks for: the first tag the allowlist accepts.
	 * A bare tag is {@code sit}, {@code follow} or {@code greet} (the legacy
	 * suggestions), or any other action that needs no arguments, such as
	 * {@code come}.
	 */
	private static Found findAction(String text) {
		List<Tag> tags = tags(text);
		for (Tag tag : tags) {
			Found found = read(tag);
			if (found.accepted()) {
				return found;
			}
		}
		return tags.isEmpty() ? Found.NOTHING : new Found(null, SuggestedAction.NONE, true);
	}

	private static Found read(Tag tag) {
		if (tag.json() != null) {
			return new Found(parseActionJson(tag.json()).orElse(null), SuggestedAction.NONE, true);
		}
		if (tag.word() != null) {
			Optional<SuggestedAction> legacy = ActionValidator.validate(tag.word());
			if (legacy.isPresent()) {
				return new Found(null, legacy.get(), true);
			}
			ActionType type = typeNamed(tag.word());
			if (type.isReal() && !type.takesArgs()) {
				return new Found(ActionRequest.of(type), SuggestedAction.NONE, true);
			}
		}
		return new Found(null, SuggestedAction.NONE, true);
	}

	/**
	 * Finds every action tag, well formed or not, so that all of them can be
	 * kept out of the dialogue. A JSON tag runs to the brace that closes it,
	 * however its contents are nested; a tag that is never closed takes the
	 * rest of its line with it.
	 */
	private static List<Tag> tags(String text) {
		List<Tag> tags = new ArrayList<>();
		Matcher opener = TAG_OPENER.matcher(text);
		int from = 0;
		while (from <= text.length() && opener.find(from)) {
			boolean bracketed = opener.group(1) != null;
			int cursor = opener.end();
			int lineEnd = endOfLine(text, cursor);
			String json = null;
			String word = null;
			int end = lineEnd;

			if (cursor < text.length() && text.charAt(cursor) == '{') {
				int close = matchingBrace(text, cursor);
				if (close >= 0) {
					json = text.substring(cursor, close + 1);
					end = close + 1;
					int after = end;
					while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
						after++;
					}
					if (bracketed && after < text.length() && text.charAt(after) == ']') {
						end = after + 1;
					} else if (!bracketed) {
						end = endOfLine(text, end);
					}
				}
			} else if (bracketed) {
				int bracket = text.indexOf(']', cursor);
				if (bracket >= 0 && bracket < lineEnd) {
					word = text.substring(cursor, bracket).replace("\"", "").replace("'", "").trim();
					end = bracket + 1;
				}
			}

			tags.add(new Tag(opener.start(), end, json, word));
			from = Math.max(end, opener.start() + 1);
		}
		return tags;
	}

	private static int endOfLine(String text, int from) {
		int lineBreak = text.indexOf('\n', from);
		return lineBreak < 0 ? text.length() : lineBreak;
	}

	/** The index of the brace closing the one at {@code open}, ignoring braces inside quoted text; -1 if none. */
	private static int matchingBrace(String text, int open) {
		int depth = 0;
		boolean quoted = false;
		for (int index = open; index < text.length(); index++) {
			char character = text.charAt(index);
			if (quoted) {
				if (character == '\\') {
					index++;
				} else if (character == '"') {
					quoted = false;
				}
			} else if (character == '"') {
				quoted = true;
			} else if (character == '{') {
				depth++;
			} else if (character == '}') {
				depth--;
				if (depth == 0) {
					return index;
				}
			}
		}
		return -1;
	}

	/**
	 * Removes a reasoning model's thinking from the visible reply. Some models
	 * put it in the content as {@code <think>...</think>}; players must never
	 * see it.
	 */
	static String stripReasoning(String rawContent) {
		if (rawContent == null) {
			return "";
		}
		String text = THINK_BLOCK.matcher(rawContent).replaceAll(" ");
		int open = text.toLowerCase(Locale.ROOT).indexOf("<think>");
		if (open >= 0) {
			// Unterminated: the reply was cut off while still thinking.
			text = text.substring(0, open);
		}
		return text;
	}

	/** Turns what the model wrote into the single line of plain chat a player sees. */
	static String tidy(String rawText) {
		if (rawText == null) {
			return "";
		}
		StringBuilder visible = new StringBuilder();
		int from = 0;
		for (Tag tag : tags(rawText)) {
			visible.append(rawText, from, tag.start()).append(' ');
			from = tag.end();
		}
		visible.append(rawText, from, rawText.length());
		String text = visible.toString().replaceAll("\\s+", " ").trim();
		text = SPEAKER_LABEL.matcher(text).replaceFirst("");
		text = BOLD.matcher(text).replaceAll("$2").replace("`", "");
		text = unquote(text.trim());
		return shorten(text, MAX_DIALOGUE_CHARS);
	}

	/** Removes one pair of quotes around the whole reply, which models like to add. */
	private static String unquote(String text) {
		if (text.length() < 2) {
			return text;
		}
		char first = text.charAt(0);
		char last = text.charAt(text.length() - 1);
		boolean straight = first == '"' && last == '"';
		boolean curly = first == '\u201C' && last == '\u201D';
		if (!straight && !curly) {
			return text;
		}
		String inner = text.substring(1, text.length() - 1);
		if (inner.indexOf('"') >= 0 || inner.indexOf('\u201C') >= 0 || inner.indexOf('\u201D') >= 0) {
			// Several quoted pieces, not one quoted reply.
			return text;
		}
		return inner.trim();
	}

	/**
	 * Cuts a reply that is too long: after its last whole sentence when there
	 * is one in the second half, otherwise between two words, otherwise
	 * wherever the limit falls.
	 */
	static String shorten(String text, int limit) {
		if (text.length() <= limit) {
			return text;
		}
		for (int index = limit - 1; index >= limit / 2; index--) {
			char character = text.charAt(index);
			boolean ends = character == '.' || character == '!' || character == '?' || character == '\u2026';
			if (ends && Character.isWhitespace(text.charAt(index + 1))) {
				return text.substring(0, index + 1);
			}
		}
		int space = text.lastIndexOf(' ', limit - 3);
		if (space >= limit * 3 / 4) {
			return text.substring(0, space).trim() + "...";
		}
		return text.substring(0, limit).trim();
	}

	/** The action type a model named, however it spelled it ({@code give-item}, {@code giveItem}), or NONE. */
	private static ActionType typeNamed(String name) {
		String wanted = letters(name);
		if (wanted.isEmpty()) {
			return ActionType.NONE;
		}
		for (ActionType candidate : ActionType.values()) {
			if (letters(candidate.name()).equals(wanted)) {
				return candidate;
			}
		}
		return ActionType.NONE;
	}

	private static String letters(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
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

			ActionType type = typeNamed(parsed.get("type").getAsString());
			if (!type.isReal()) {
				return Optional.empty();
			}

			Map<String, Object> args = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : parsed.entrySet()) {
				String key = entry.getKey();
				if ("type".equals(key)) {
					continue;
				}
				if (!type.allowsArg(key)) {
					key = declaredName(type, key, parsed);
				}
				if (key == null) {
					// An argument the action does not declare: reject the whole
					// request rather than silently dropping or acting on it.
					return Optional.empty();
				}
				Object value = argumentValue(entry.getValue());
				if (value != null) {
					args.put(key, value);
				}
			}

			return Optional.of(ActionRequest.of(type, args));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	/**
	 * The declared argument a model meant by an everyday synonym: "quantity"
	 * or "amount" for {@code count}, "name" for {@code item} or
	 * {@code structure}. Null when the key is not such a synonym, when the
	 * action has no such argument, or when the real name is given as well.
	 */
	private static String declaredName(ActionType type, String key, JsonObject request) {
		String[] candidates = switch (key.toLowerCase(Locale.ROOT)) {
			case "quantity", "amount", "number" -> new String[] {"count"};
			case "name" -> new String[] {"item", "structure"};
			default -> new String[0];
		};
		for (String candidate : candidates) {
			if (type.allowsArg(candidate) && !request.has(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * One argument as plain data: text, a number, or a list written out as
	 * text. {@code ["1 crafting table", "64 sticks"]} and
	 * {@code [{"item":"stick","count":64}]} both become the kind of list the
	 * item parser reads ("1 crafting table, 64 sticks"). Whole numbers stay
	 * whole, so a count of 32 is not turned into 32.0. Anything else is left
	 * out, and the action's handler asks for what is missing.
	 */
	private static Object argumentValue(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (value instanceof JsonPrimitive primitive) {
			if (!primitive.isNumber()) {
				return primitive.getAsString();
			}
			double number = primitive.getAsDouble();
			if (number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE) {
				return (int) number;
			}
			return number;
		}
		if (value instanceof JsonArray array) {
			StringBuilder joined = new StringBuilder();
			for (JsonElement element : array) {
				String piece = listEntry(element);
				if (piece.isEmpty()) {
					return null;
				}
				if (joined.length() > 0) {
					joined.append(", ");
				}
				joined.append(piece);
			}
			return joined.length() == 0 ? null : joined.toString();
		}
		return null;
	}

	/** One entry of a list argument as text: "64 sticks" as written, or an amount and a name from an object. */
	private static String listEntry(JsonElement element) {
		if (element instanceof JsonPrimitive primitive) {
			return primitive.getAsString().trim();
		}
		if (!(element instanceof JsonObject object)) {
			return "";
		}
		String name = "";
		String amount = "";
		for (Map.Entry<String, JsonElement> member : object.entrySet()) {
			if (!(member.getValue() instanceof JsonPrimitive primitive)) {
				return "";
			}
			if (primitive.isNumber()) {
				if (amount.isEmpty()) {
					amount = String.valueOf(Math.round(primitive.getAsDouble()));
				}
			} else if (name.isEmpty()) {
				name = primitive.getAsString().trim();
			}
		}
		if (name.isEmpty()) {
			return "";
		}
		return amount.isEmpty() ? name : amount + " " + name;
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
