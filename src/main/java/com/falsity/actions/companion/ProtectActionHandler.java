package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import net.minecraft.world.entity.LivingEntity;

/** "Protect me." — arm combat and target the nearest hostile mob. */
public final class ProtectActionHandler implements ActionHandler {
	private static final double RADAR_RADIUS = 32.0;

	@Override
	public ActionType type() {
		return ActionType.PROTECT;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		if (!context.config().autonomy.combatAssistance) {
			return ActionResult.failure("I'm not fighting for you right now.");
		}

		context.falsity().setCombatEnabled(true);
		LivingEntity target = HostileScanner.nearestHostile(context.level(), context.player(), RADAR_RADIUS);
		if (target == null) {
			return ActionResult.ok("I don't see anything to protect you from.");
		}

		context.falsity().setTarget(target);
		return ActionResult.ok("I see them. I'll help.");
	}
}
