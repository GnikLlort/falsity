package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import net.minecraft.world.phys.Vec3;

/** "Walk to that spot." — path to a coordinate, standing up first. */
public final class WalkToActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.WALK_TO;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		Vec3 target = new Vec3(
			request.argDouble("x", context.player().getX()),
			request.argDouble("y", context.player().getY()),
			request.argDouble("z", context.player().getZ())
		);
		context.falsity().walkTo(target);
		return ActionResult.ok("On my way.");
	}
}
