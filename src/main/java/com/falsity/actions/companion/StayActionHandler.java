package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Stay." — stop moving and remain where Falsity is. */
public final class StayActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.STAY;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().stay();
		context.falsity().setSitting(true);
		return ActionResult.ok("I'll stay put.");
	}
}
