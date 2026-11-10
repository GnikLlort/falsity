package com.falsity.actions.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;

/**
 * Finds hostile mobs near a point.
 *
 * <p>Only hostile categories are considered and players are never candidates,
 * so Falsity can be asked to help fight without ever being aimed at a player.
 */
public final class HostileScanner {
	private HostileScanner() {
	}

	/** All live hostile mobs within the radius, nearest first. */
	public static List<LivingEntity> nearbyHostiles(ServerLevel level, LivingEntity center, double radius) {
		AABB box = new AABB(
			center.getX() - radius, center.getY() - radius, center.getZ() - radius,
			center.getX() + radius, center.getY() + radius, center.getZ() + radius
		);
		List<LivingEntity> result = new ArrayList<>();
		List<Entity> entities = level.getEntities((Entity) null, box, entity ->
			entity instanceof LivingEntity living && living.isAlive()
				&& living.getType().getCategory() == MobCategory.HOSTILE
				&& living != center);
		for (Entity entity : entities) {
			if (entity instanceof LivingEntity living) {
				result.add(living);
			}
		}
		result.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(center)));
		return result;
	}

	/** The nearest live hostile mob within the radius, or null. */
	public static LivingEntity nearestHostile(ServerLevel level, LivingEntity center, double radius) {
		List<LivingEntity> hostiles = nearbyHostiles(level, center, radius);
		return hostiles.isEmpty() ? null : hostiles.get(0);
	}
}
