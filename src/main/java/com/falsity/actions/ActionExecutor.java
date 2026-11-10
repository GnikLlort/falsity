package com.falsity.actions;

import com.falsity.FalsityMod;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes a validated {@link ActionRequest} to its handler.
 *
 * <p>The executor is the single gate between "the AI asked for something" and
 * "the world changed". It refuses unknown types and unknown arguments before
 * dispatching, and it turns any handler exception into a failed result instead
 * of crashing the server. Nothing here runs on a background thread: callers
 * must already be on the server thread.
 */
public final class ActionExecutor {
	private static final Logger LOGGER = LoggerFactory.getLogger(ActionExecutor.class);

	private final Map<ActionType, ActionHandler> handlers = new EnumMap<>(ActionType.class);

	public ActionExecutor() {
	}

	public ActionExecutor(Collection<ActionHandler> handlers) {
		for (ActionHandler handler : handlers) {
			register(handler);
		}
	}

	public void register(ActionHandler handler) {
		if (handler == null || handler.type() == null) {
			return;
		}
		ActionHandler previous = this.handlers.put(handler.type(), handler);
		if (previous != null) {
			LOGGER.warn("Action handler for {} was replaced.", handler.type());
		}
	}

	public boolean supports(ActionType type) {
		return type != null && this.handlers.containsKey(type);
	}

	/**
	 * Validates and executes one request. Always returns a result; never throws
	 * for a malformed request.
	 */
	public ActionResult execute(ActionContext context, ActionRequest request) {
		if (request == null || request.type() == null) {
			return ActionResult.failure("Falsity did not understand that.");
		}

		ActionType type = request.type();
		if (type == ActionType.NONE) {
			return ActionResult.failure("There was nothing to do.");
		}

		if (!type.allowsArgs(request.args())) {
			LOGGER.debug("Rejected action {} with unsupported arguments {}.", type, request.args().keySet());
			return ActionResult.failure("Falsity does not know how to do that.");
		}

		ActionHandler handler = this.handlers.get(type);
		if (handler == null) {
			LOGGER.debug("No handler registered for action {}.", type);
			return ActionResult.failure("Falsity cannot do that yet.");
		}

		try {
			return handler.handle(context, request);
		} catch (RuntimeException exception) {
			// A failed action must never take the server down, and must never be
			// reported as a success.
			LOGGER.warn("Action {} failed: {}", type, exception.toString());
			return ActionResult.failure("Falsity tried, but something went wrong.");
		}
	}

	/** A debug-only description of the registered handlers. */
	public String describeRegistered() {
		StringBuilder builder = new StringBuilder();
		for (ActionType type : ActionType.values()) {
			if (this.handlers.containsKey(type)) {
				builder.append(type).append(' ');
			}
		}
		return builder.toString().trim();
	}

	/** Whether the executor is wired into the running mod. */
	public static boolean available() {
		return FalsityMod.actions() != null;
	}
}
