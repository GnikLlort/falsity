package com.falsity.ai;

import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.world.FactGuard;

/**
 * Decides what Falsity finally says once a model reply and the outcome of an
 * action are both known.
 *
 * <p>The server's own result always wins over the model's words wherever they
 * could disagree. If the model says "here are your torches" but the inventory
 * was full, the player hears what actually happened. A model's line is kept
 * only for simple orders, where it adds personality and cannot be wrong about
 * anything that matters.
 */
public final class ReplyComposer {
	private ReplyComposer() {
	}

	/**
	 * Whether an action's own message states facts (what was handed over,
	 * what is nearby, where something is) that must be reported verbatim.
	 */
	public static boolean isFactual(ActionType type) {
		return type == ActionType.GIVE_ITEM
			|| type == ActionType.GIVE_ITEMS
			|| type == ActionType.WORLD_QUERY
			|| type == ActionType.REPORT_THREATS
			|| type == ActionType.BUILD
			|| type == ActionType.STOP_BUILD;
	}

	/**
	 * Combines a model's dialogue with the result of the action it asked for.
	 *
	 * @param dialogue the model's line, possibly blank
	 * @param type     the action that ran, or null when none did
	 * @param result   the action's result, or null when none ran
	 */
	public static String compose(String dialogue, ActionType type, ActionResult result) {
		boolean hasDialogue = dialogue != null && !dialogue.isBlank();
		if (result == null) {
			return hasDialogue ? dialogue : "";
		}
		String outcome = result.message() == null ? "" : result.message();
		if (outcome.isBlank()) {
			return hasDialogue ? dialogue : "";
		}
		if (result.pending() || !result.success()) {
			// A question to confirm, or a refusal: the model's cheerful line would mislead.
			return outcome;
		}
		if (!hasDialogue || (type != null && isFactual(type))) {
			return outcome;
		}
		return dialogue;
	}

	/**
	 * Replaces a line that quotes a position or distance the server did not
	 * verify. Returns the line unchanged when it makes no such claim.
	 */
	public static String guardLocation(String dialogue, String verifiedFacts) {
		if (dialogue == null || dialogue.isBlank()) {
			return dialogue;
		}
		if (FactGuard.hasUnverifiedLocation(dialogue, verifiedFacts == null ? "" : verifiedFacts)) {
			return FactGuard.honestReply();
		}
		return dialogue;
	}
}
