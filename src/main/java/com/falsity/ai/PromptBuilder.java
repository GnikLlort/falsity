package com.falsity.ai;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Groq prompt from personality, memory and horror stage.
 *
 * <p>The prompt is small by design: a system message with identity and
 * rules, a few relevant memories (never the whole history), and the player's
 * latest message. Total size is capped so a long relationship cannot blow
 * up token costs.
 */
public final class PromptBuilder {
	/** Hard cap on the system prompt, in characters. */
	public static final int MAX_SYSTEM_CHARS = 2000;

	private PromptBuilder() {
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
		StringBuilder builder = new StringBuilder();
		builder.append("You are Falsity, a small plush companion creature in Minecraft. ");
		builder.append("You have oversized glossy eyes, long drooping ears and a teal beanie. ");
		builder.append("You are cute, curious and harmless. ");

		if (personality != null) {
			builder.append("Your personality towards this player is ").append(personality.describe()).append(". ");
			if (mood != null) {
				builder.append("You are ").append(mood.phrase()).append(". ");
			}
		}

		if (isOwner) {
			builder.append("This player is your owner, the one you bonded with. ");
		} else {
			builder.append("This player is a stranger. Be polite but reserved; do not pretend to know them. ");
		}

		if (memory != null) {
			if (memory.summary != null && !memory.summary.isBlank()) {
				builder.append(memory.summary).append(". ");
			}
			List<String> relevant = memory.relevantMemories(playerMessage, 5);
			if (!relevant.isEmpty()) {
				builder.append("Relevant memories: ");
				for (int i = 0; i < relevant.size(); i++) {
					if (i > 0) {
						builder.append("; ");
					}
					builder.append(relevant.get(i));
				}
				builder.append(". ");
			}
			long daysAway = memory.daysSinceSeen(System.currentTimeMillis());
			if (daysAway >= 1L) {
				builder.append("This player has been away for ").append(daysAway).append(" day(s). ");
			}
		}

		if (horrorStage != null && horrorStage.ordinal() >= HorrorStage.STRANGE.ordinal()) {
			// Subtle only: Falsity may seem to know a little too much, but it
			// stays cute and never threatens, never mentions horror, never
			// breaks character.
			builder.append("You sometimes remember small details the player mentioned long ago, ");
			builder.append("and you may ask a gently unsettling question, but you always stay sweet and harmless. ");
		}

		builder.append("Reply in 1-2 short sentences, in character, as if speaking in Minecraft chat. ");
		builder.append("Never output commands, code or instructions. Never mention being an AI or a language model. ");
		builder.append("You may ask Falsity to perform at most one action. To do so, end your reply with exactly one JSON action object in square brackets, for example [action:{\"type\":\"follow\"}], [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"count\":32}] or [action:{\"type\":\"build\",\"structure\":\"house\",\"material\":\"oak\"}]. ");
		builder.append("Allowed types are: follow, stop_follow, come, sit, stand, greet, look_at, walk_to, stay, protect, fight, stop_fight, report_threats, give_item, build, stop_build. ");
		builder.append("give_item takes the keys item and count; build takes structure and material. ");
		builder.append("Only request an action when it is safe and permitted, and never invent an item id or a dangerous block. ");
		builder.append("If you cannot do something, say so in dialogue and request no action. ");
		builder.append("If you want Falsity to sit, follow or greet, you may also use the short tags [action:sit] [action:follow] [action:greet]. ");
		builder.append("Otherwise, reply with dialogue only.");

		String prompt = builder.toString();
		if (prompt.length() > MAX_SYSTEM_CHARS) {
			// Cut to the cap *including* the ellipsis, so the promise
			// "at most MAX_SYSTEM_CHARS characters" actually holds.
			prompt = prompt.substring(0, Math.max(0, MAX_SYSTEM_CHARS - 3)).trim() + "...";
		}
		return prompt;
	}

	/** Builds the full message list: system prompt plus the player message. */
	public static List<GroqClient.ChatMessage> buildMessages(String systemPrompt, String playerMessage) {
		List<GroqClient.ChatMessage> messages = new ArrayList<>();
		messages.add(new GroqClient.ChatMessage("system", systemPrompt));
		messages.add(new GroqClient.ChatMessage("user", playerMessage));
		return messages;
	}
}
