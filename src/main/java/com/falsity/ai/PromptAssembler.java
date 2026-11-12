package com.falsity.ai;

import com.falsity.horror.HorrorManager;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import com.falsity.personality.PersonalityPrompt;
import com.falsity.personality.RelationshipState;
import java.util.List;
import java.util.UUID;

/**
 * Puts one turn's request together from the state the mod keeps: the
 * personality text, the horror stage, the relationship, the memory, the world
 * facts and the conversation so far.
 *
 * <p>This is the single place where those pieces meet, and it has no Minecraft
 * types in it, so the same code that runs on a server is what the unit tests
 * run. {@link ConversationManager} gathers the state on the server thread and
 * hands it here.
 */
public final class PromptAssembler {
	/** How many exchanges of the previous conversation are recalled when there is no live transcript. */
	public static final int EARLIER_EXCHANGES = 2;

	/**
	 * What takes the place of the player's message when Falsity speaks without
	 * having been spoken to. It asks for a remark and supplies none: what is
	 * said has to come from the personality, the stage, the memories and the
	 * verified facts, like any other line.
	 */
	public static final String SPEAK_FIRST_CUE =
		"(The player has not said anything for a while. Say one short thing of your own accord, as you would if you were "
		+ "simply there with them: something you notice about where you are or the time of day, a question about what they "
		+ "are doing, or something you remember about them. If the verified facts show danger, warn them instead. Do not "
		+ "greet them as if they had just arrived, and use no tag.)";

	/** What stands in the transcript for the player's side when Falsity spoke first. */
	public static final String SILENCE = "(the player was quiet)";

	private PromptAssembler() {
	}

	/**
	 * What one turn is answered from.
	 *
	 * @param companionName the name the companion goes by in this world
	 * @param memory        what is stored about the player
	 * @param personality   the live personality towards the player
	 * @param voice         the horror stage whose voice is used (see {@link #voiceStage})
	 * @param isOwner       whether the player is the one this companion is bonded to
	 * @param daysAway      whole days the player had been gone when they spoke
	 * @param message       what the player said
	 * @param verifiedFacts what the server has checked about the world, or empty
	 * @param hint          a note about this message, or empty
	 */
	public record Turn(
		String companionName,
		PlayerMemory memory,
		PersonalityProfile personality,
		HorrorStage voice,
		boolean isOwner,
		long daysAway,
		String message,
		String verifiedFacts,
		String hint
	) {
	}

	/**
	 * A request ready to send.
	 *
	 * @param layers   the system prompt, layer by layer
	 * @param messages the system prompt, the conversation so far, and the player's message last
	 */
	public record Assembled(PromptBuilder.Layers layers, List<GroqClient.ChatMessage> messages) {
	}

	/**
	 * The stage whose voice Falsity speaks in, with or without Groq. It is the
	 * horror system's own stage for the player and nothing else, with one
	 * exception that is also the horror system's: when its switch for
	 * unsettling lines is off, the voice stays at the first stage. Nothing here
	 * changes the stage itself.
	 */
	public static HorrorStage voiceStage(HorrorManager horror, UUID player, String playerName) {
		HorrorStage stage = horror.stageFor(player, playerName);
		return horror.shouldUnsettle(player, playerName) ? stage : HorrorStage.INNOCENCE;
	}

	/**
	 * Builds the request for one turn.
	 *
	 * @param personality the personality text in use
	 * @param turn        the state gathered for this turn
	 * @param transcript  the live conversation so far, oldest first; may be empty
	 * @param nowMillis   the current time
	 * @param secret      a string that must never be sent in a prompt (the API key), or empty
	 */
	public static Assembled assemble(
		PersonalityPrompt personality,
		Turn turn,
		List<ConversationHistory.Turn> transcript,
		long nowMillis,
		String secret
	) {
		List<ConversationHistory.Turn> live = transcript == null ? List.of() : transcript;
		PlayerMemory memory = turn.memory();

		String relationship = RelationshipState.describe(
			memory, turn.personality(), EmotionalState.from(turn.personality()), turn.isOwner(), turn.daysAway(), nowMillis);

		// With no live transcript (a new session, or a long silence) the stored
		// notes are the only record of how the last conversation went.
		List<PlayerMemory.Exchange> earlier =
			live.isEmpty() && memory != null ? memory.lastExchanges(EARLIER_EXCHANGES) : List.of();

		PromptBuilder.Layers layers = PromptBuilder.request()
			.companionName(turn.companionName())
			.personality(personality == null ? "" : scrub(personality.text(), secret))
			.stage(turn.voice())
			.relationship(relationship)
			.memory(memory)
			.playerMessage(turn.message())
			.context(new PromptBuilder.Context(turn.verifiedFacts(), turn.hint()))
			.earlierTalk(earlier)
			.build();

		String system = scrub(layers.text(), secret);
		return new Assembled(layers, PromptBuilder.buildMessages(system, live, turn.message()));
	}

	/** Takes a secret out of text that is about to be sent. The key belongs in a header, never in a prompt. */
	static String scrub(String text, String secret) {
		if (text == null) {
			return "";
		}
		if (secret == null || secret.isBlank() || secret.length() < 8) {
			return text;
		}
		return text.replace(secret, "[removed]");
	}
}
