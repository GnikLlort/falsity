package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Sit." */
public final class SitActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.SIT;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().setSitting(true);
		return ActionResult.ok("I'll sit.");
	}
}
