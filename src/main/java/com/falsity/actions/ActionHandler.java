package com.falsity.actions;

/**
 * Executes one kind of action.
 *
 * <p>Handlers are the only place game state changes for a requested action.
 * Each declares the {@link ActionType} it owns, so the executor can route a
 * validated request without a switch statement that would grow forever. Adding
 * a capability means adding a handler and registering it, not editing the AI.
 */
public interface ActionHandler {
	/** The action type this handler executes. */
	ActionType type();

	/**
	 * Runs the action on the server thread. Must be safe to call with a request
	 * whose arguments were produced by the AI: validate them, and return a
	 * failure rather than throwing.
	 */
	ActionResult handle(ActionContext context, ActionRequest request);
}
