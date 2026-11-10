package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Hi." — play the greeting animation. Harmless and immediate. */
public final class GreetActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.GREET;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().greet();
		return ActionResult.ok("Hello!");
	}
}
