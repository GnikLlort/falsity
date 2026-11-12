package com.falsity.actions.companion;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/** "What's nearby?" — report hostile mobs, nearest first. */
public final class ReportThreatsActionHandler implements ActionHandler {
	private static final double RADAR_RADIUS = 32.0;

	@Override
	public ActionType type() {
		return ActionType.REPORT_THREATS;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		if (!context.config().autonomy.exploration) {
			return ActionResult.failure("I'm not keeping watch right now.");
		}

		List<LivingEntity> hostiles = HostileScanner.nearbyHostiles(context.level(), context.player(), RADAR_RADIUS);
		if (hostiles.isEmpty()) {
			return ActionResult.ok("Nothing dangerous nearby.");
		}

		StringBuilder names = new StringBuilder();
		for (int i = 0; i < Math.min(5, hostiles.size()); i++) {
			if (i > 0) {
				names.append(", ");
			}
			names.append(typeLabel(hostiles.get(i).getType()));
		}
		String suffix = hostiles.size() > 5 ? " and " + (hostiles.size() - 5) + " more" : "";
		return ActionResult.ok("There are " + hostiles.size() + " hostile mob" + plural(hostiles.size()) + " nearby: " + names + suffix + ".");
	}

	private static String plural(int count) {
		return count == 1 ? "" : "s";
	}

	private static String typeLabel(EntityType<?> type) {
		try {
			Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			String id = key == null ? "hostile mob" : key.toString();
			int colon = id.indexOf(':');
			return colon >= 0 ? id.substring(colon + 1) : id;
		} catch (RuntimeException missing) {
			return "hostile mob";
		}
	}
}
