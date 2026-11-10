package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import net.minecraft.world.phys.Vec3;

/** "Look at me" / "look at that" — turn to face a player or a coordinate. */
public final class LookAtActionHandler implements ActionHandler {
	@Override
	public ActionType type() {
		return ActionType.LOOK_AT;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		Vec3 target = context.player().position();
		if (request.has("x")) {
			target = new Vec3(
				request.argDouble("x", target.x),
				request.argDouble("y", target.y),
				request.argDouble("z", target.z)
			);
		}
		context.falsity().lookAt(target);
		return ActionResult.ok("I'm looking.");
	}
}
