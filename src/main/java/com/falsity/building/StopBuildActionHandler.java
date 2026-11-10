package com.falsity.building;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/** "Stop building." — cancel the player's active build safely at the next tick. */
public final class StopBuildActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.STOP_BUILD;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		BuildManager builds = FalsityMod.builds();
		if (builds == null) {
			return ActionResult.failure("I wasn't building anything.");
		}
		return ActionResult.ok(builds.cancel(context.player().getUUID()));
	}
}
