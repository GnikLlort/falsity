package com.falsity.ai;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Groq prompt from personality, memory, horror stage and whatever
 * the server has verified about the world.
 *
 * <p>The prompt is small by design: a system message with identity and rules,
 * a few relevant memories (never the whole history), the last few exchanges,
 * and the player's latest message. Total size is capped so a long relationship
 * cannot blow up token costs.
 *
 * <p>The rules are never the part that gets cut. Identity and rules are laid
 * out first and memory is fitted into whatever room is left, so a player with
 * a lot of history still gets a Falsity that knows how to answer.
 */
public final class PromptBuilder {
	/** Hard cap on the system prompt, in characters. */
	public static final int MAX_SYSTEM_CHARS = 2600;

	/** Longest verified-facts block accepted, in characters. */
	static final int MAX_FACTS_CHARS = 480;

	/** Longest hint accepted, in characters. */
	static final int MAX_HINT_CHARS = 200;

	private static final String RULES =
		"Rules:\n"
		+ "- Reply in 1-2 short sentences of plain chat, in character. Answer what the player just said and do not repeat your earlier lines.\n"
		+ "- Never mention being an AI or a language model. Never output commands or code.\n"
		+ "- You only know the world through the 'Verified facts' line. Never state coordinates, distances, directions or locations that are not written there. If you do not know, say so.\n"
		+ "- To make something happen, end your reply with ONE tag like [action:{\"type\":\"follow\"}]. Types: follow, stop_follow, come, sit, stand, stay, greet, protect, fight, stop_fight, report_threats, stop_build, "
		+ "give_item (item, count), give_items (items, e.g. \"1 crafting table, 64 sticks\"), build (structure, material), "
		+ "world_query (query, e.g. \"nearest village\", \"where am i\", \"diamonds nearby\").\n"
		+ "- Use a tag only when the player asks you to do or find something. Sharing a plan or a feeling (\"I want to build a house\", \"I need a hug\") is conversation: answer warmly, with no tag.\n"
		+ "- Never invent an item or promise something the tag cannot do.";

	private PromptBuilder() {
	}

	/**
	 * Extra, per-message context for the prompt.
	 *
	 * @param verifiedFacts what the server has checked about the world right now, or empty
	 * @param hint          a one-line note about this message (for example that a requested
	 *                      thing is not a real item), or empty
	 */
	public record Context(String verifiedFacts, String hint) {
		public static final Context NONE = new Context("", "");

		public Context {
			verifiedFacts = verifiedFacts == null ? "" : verifiedFacts.trim();
			hint = hint == null ? "" : hint.trim();
		}
	}

	/**
	 * Builds the system message. {@code isOwner} distinguishes the bonded
	 * player from strangers: Falsity is warm to its owner and reserved to
	 * everyone else.
	 */
	public static String buildSystemPrompt(
		PlayerMemory memory,
		PersonalityProfile personality,
		EmotionalState mood,
		HorrorStage horrorStage,
		boolean isOwner,
		String playerMessage
	) {
		return buildSystemPrompt(memory, personality, mood, horrorStage, isOwner, playerMessage, Context.NONE);
	}

	/** Builds the system message with verified world facts and a hint. */
	public static String buildSystemPrompt(
		PlayerMemory memory,
		PersonalityProfile personality,
		EmotionalState mood,
		HorrorStage horrorStage,
		boolean isOwner,
		String playerMessage,
		Context context
	) {
		Context extra = context == null ? Context.NONE : context;

		StringBuilder head = new StringBuilder();
		head.append("You are Falsity, a small plush companion creature living in this player's Minecraft world: ");
		head.append("oversized glossy eyes, long drooping ears, a teal beanie. ");
		head.append("You are cute, curious, a little odd, and harmless. You speak simply and warmly.\n");

		if (personality != null) {
			head.append("Your personality towards this player is ").append(personality.describe()).append(". ");
			if (mood != null) {
				head.append("You are ").append(mood.phrase()).append(". ");
			}
		}

		if (isOwner) {
			head.append("This player is your owner, the one you bonded with.");
		} else {
			head.append("This player is a stranger. Be polite but reserved; do not pretend to know them.");
		}

		if (horrorStage != null && horrorStage.ordinal() >= HorrorStage.STRANGE.ordinal()) {
			// Subtle only: Falsity may seem to know a little too much, but it
			// stays cute and never threatens, never mentions horror, never
			// breaks character.
			head.append(" You sometimes remember small details the player mentioned long ago, ");
			head.append("and you may ask a gently unsettling question, but you always stay sweet and harmless.");
		}
		head.append('\n');

		StringBuilder tail = new StringBuilder();
		if (!extra.verifiedFacts().isEmpty()) {
			tail.append("Verified facts (checked by the game just now): ")
				.append(clip(singleLine(extra.verifiedFacts()), MAX_FACTS_CHARS)).append('\n');
		} else {
			tail.append("Verified facts: none for this message.\n");
		}
		if (!extra.hint().isEmpty()) {
			tail.append("Note: ").append(clip(singleLine(extra.hint()), MAX_HINT_CHARS)).append('\n');
		}
		tail.append(RULES);

		// Memory gets whatever room identity, facts and rules leave over.
		int memoryBudget = MAX_SYSTEM_CHARS - head.length() - tail.length();
		String memoryBlock = buildMemoryBlock(memory, playerMessage, memoryBudget);

		String prompt = head + memoryBlock + tail;
		if (prompt.length() > MAX_SYSTEM_CHARS) {
			// Cannot happen while the fixed parts fit, but the promise
			// "at most MAX_SYSTEM_CHARS characters" must hold regardless.
			prompt = prompt.substring(0, Math.max(0, MAX_SYSTEM_CHARS - 3)).trim() + "...";
		}
		return prompt;
	}

	private static String buildMemoryBlock(PlayerMemory memory, String playerMessage, int budget) {
		if (memory == null || budget < 40) {
			return "";
		}

		StringBuilder block = new StringBuilder();
		int remaining = budget - 1;

		long daysAway = memory.daysSinceSeen(System.currentTimeMillis());
		if (daysAway >= 1L) {
			String away = "This player has been away for " + daysAway + " day(s). ";
			if (away.length() <= remaining) {
				block.append(away);
				remaining -= away.length();
			}
		}

		List<String> relevant = memory.relevantMemories(playerMessage, 5);
		if (!relevant.isEmpty() && remaining > 40) {
			StringBuilder line = new StringBuilder("Relevant memories: ");
			int added = 0;
			for (String entry : relevant) {
				String piece = (added > 0 ? "; " : "") + singleLine(entry);
				if (line.length() + piece.length() + 2 > remaining) {
					break;
				}
				line.append(piece);
				added++;
			}
			if (added > 0) {
				line.append(". ");
				block.append(line);
				remaining -= line.length();
			}
		}

		if (memory.summary != null && !memory.summary.isBlank() && remaining > 40) {
			String summary = clip(singleLine(memory.summary), remaining - 2) + ". ";
			block.append(summary);
		}

		if (block.length() == 0) {
			return "";
		}
		return block.toString().trim() + "\n";
	}

	private static String singleLine(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").trim();
	}

	private static String clip(String text, int maxChars) {
		if (maxChars <= 0) {
			return "";
		}
		if (text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
	}

	/** Builds the full message list: system prompt plus the player message. */
	public static List<GroqClient.ChatMessage> buildMessages(String systemPrompt, String playerMessage) {
		return buildMessages(systemPrompt, List.of(), playerMessage);
	}

	/**
	 * Builds the full message list: system prompt, the earlier exchanges in
	 * order (oldest first), then the player's new message.
	 */
	public static List<GroqClient.ChatMessage> buildMessages(
		String systemPrompt,
		List<ConversationHistory.Turn> history,
		String playerMessage
	) {
		List<GroqClient.ChatMessage> messages = new ArrayList<>();
		messages.add(new GroqClient.ChatMessage("system", systemPrompt));
		if (history != null) {
			for (ConversationHistory.Turn turn : history) {
				if (turn == null || turn.player().isBlank() || turn.falsity().isBlank()) {
					continue;
				}
				messages.add(new GroqClient.ChatMessage("user", turn.player()));
				messages.add(new GroqClient.ChatMessage("assistant", turn.falsity()));
			}
		}
		messages.add(new GroqClient.ChatMessage("user", playerMessage));
		return messages;
	}
}
