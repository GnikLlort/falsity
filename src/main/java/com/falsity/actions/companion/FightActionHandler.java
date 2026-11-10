package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import net.minecraft.world.entity.LivingEntity;

/** "Help me fight these zombies." — arm combat and attack the nearest hostile. */
public final class FightActionHandler implements ActionHandler {
	private static final double RADAR_RADIUS = 32.0;

	@Override
	public ActionType type() {
		return ActionType.FIGHT;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		if (!context.config().autonomy.combatAssistance) {
			return ActionResult.failure("I'm not fighting for you right now.");
		}

		context.falsity().setCombatEnabled(true);
		LivingEntity target = HostileScanner.nearestHostile(context.level(), context.player(), RADAR_RADIUS);
		if (target == null) {
			return ActionResult.ok("I don't see anything to fight.");
		}

		context.falsity().setTarget(target);
		return ActionResult.ok("I'll help you fight them.");
	}
}
