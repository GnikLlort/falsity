package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Stop following." — sit down where it is, which also stops the follow goal. */
public final class StopFollowActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.STOP_FOLLOW;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().setSitting(true);
		return ActionResult.ok("I'll stay here.");
	}
}
