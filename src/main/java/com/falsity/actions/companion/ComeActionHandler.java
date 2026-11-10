package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Come here." — arrive on open ground near the player. */
public final class ComeActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.COME;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().comeTo(context.player());
		return ActionResult.ok("I'm right here.");
	}
}
