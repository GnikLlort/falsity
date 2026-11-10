package com.falsity.actions;

/**
 * The outcome of executing one action.
 *
 * @param success whether the action completed
 * @param pending whether the action is waiting for the player's confirmation
 *                (a pending action is neither a success nor a failure yet)
 * @param message a short, chat-safe line describing what happened
 */
public record ActionResult(boolean success, boolean pending, String message) {
	public static ActionResult ok(String message) {
		return new ActionResult(true, false, message);
	}

	public static ActionResult pending(String message) {
		return new ActionResult(false, true, message);
	}

	public static ActionResult failure(String message) {
		return new ActionResult(false, false, message);
	}

	/** Whether the action did something the player can be told about. */
	public boolean isInformative() {
		return this.success || this.pending || (this.message != null && !this.message.isBlank());
	}
}
