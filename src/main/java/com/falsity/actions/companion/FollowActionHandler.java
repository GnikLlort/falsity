package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Follow me." — stand up and let the follow goal take over. */
public final class FollowActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.FOLLOW;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().setSitting(false);
		return ActionResult.ok("I'll follow you.");
	}
}
