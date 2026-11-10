package com.falsity.building;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.actions.ConfirmationManager;
import java.util.Optional;

/**
 * "Build me a house."
 *
 * <p>The handler plans, validates and then either starts the build or asks for
 * confirmation. The AI never places a block: it supplies a structure name and a
 * material, and everything else is resolved and checked here.
 */
public final class BuildActionHandler implements ActionHandler {
	private static final long CONFIRMATION_TTL_MILLIS = 180_000L;

	@Override
	public ActionType type() {
		return ActionType.BUILD;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		var config = context.config();
		if (!config.building.enabled) {
			return ActionResult.failure("I'm not allowed to build right now.");
		}

		String structure = request.arg("structure", "house");
		String material = request.arg("material", "oak");

		BuildRequest buildRequest = new BuildRequest(
			structure, material,
			positiveOrNull(request, "width"),
			positiveOrNull(request, "length"),
			positiveOrNull(request, "height")
		);

		Optional<BuildPlan> maybePlan = BuildPlanner.plan(buildRequest, context.player(), config);
		if (maybePlan.isEmpty()) {
			return ActionResult.failure("I don't know how to build a " + structure + ".");
		}
		BuildPlan plan = maybePlan.get();

		Optional<String> error = BuildValidator.validate(plan, context.level(), context.player(), config);
		if (error.isPresent()) {
			return ActionResult.failure(error.get());
		}

		if (config.building.requireConfirmation && plan.totalBlocks() > config.building.confirmAboveBlocks) {
			ConfirmationManager confirmations = FalsityMod.confirmations();
			if (confirmations == null) {
				return ActionResult.failure("I'm not sure I should do that.");
			}
			String question = "I'll build " + plan.describe() + " here. Should I start?";
			confirmations.request(context.player().getUUID(), question, () -> {
				BuildManager builds = FalsityMod.builds();
				if (builds == null) {
					return ActionResult.failure("I'm not sure I should do that.");
				}
				return ActionResult.ok(builds.start(context.player(), context.falsity(), plan));
			}, CONFIRMATION_TTL_MILLIS);
			return ActionResult.pending(question);
		}

		BuildManager builds = FalsityMod.builds();
		if (builds == null) {
			return ActionResult.failure("I'm not sure I should do that.");
		}
		return ActionResult.ok(builds.start(context.player(), context.falsity(), plan));
	}

	private static Integer positiveOrNull(ActionRequest request, String key) {
		int value = request.argInt(key, -1);
		return value > 0 ? value : null;
	}
}
