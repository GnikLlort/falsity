package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Stop fighting." — disarm combat and drop the target immediately. */
public final class StopFightActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.STOP_FIGHT;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		context.falsity().setCombatEnabled(false);
		return ActionResult.ok("I'll stop fighting.");
	}
}
