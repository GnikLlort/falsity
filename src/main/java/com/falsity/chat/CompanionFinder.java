package com.falsity.chat;

import com.falsity.entity.FalsityEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the Falsity bonded to a player within a radius.
 *
 * <p>Only a bonded companion is ever considered, so a player can never make an
 * unbonded Falsity answer, and a Falsity bonded to someone else is never
 * picked up by mistake. The nearest match wins.
 */
public final class CompanionFinder {
	private CompanionFinder() {
	}

	/** The nearest Falsity bonded to this player within {@code range} blocks. */
	public static Optional<FalsityEntity> findBonded(ServerLevel level, Player player, double range) {
		Vec3 position = player.position();
		AABB box = new AABB(
			position.x - range, position.y - range, position.z - range,
			position.x + range, position.y + range, position.z + range
		);
		List<Entity> nearby = level.getEntities((Entity) null, box, entity ->
			entity instanceof FalsityEntity falsity && falsity.isBondedTo(player.getUUID()));

		List<FalsityEntity> candidates = new ArrayList<>();
		for (Entity entity : nearby) {
			if (entity instanceof FalsityEntity falsity) {
				candidates.add(falsity);
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		candidates.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
		return Optional.of(candidates.get(0));
	}
}
