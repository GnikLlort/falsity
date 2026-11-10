package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Stand up." */
public final class StandActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.STAND;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().setSitting(false);
		return ActionResult.ok("On my feet.");
	}
}
