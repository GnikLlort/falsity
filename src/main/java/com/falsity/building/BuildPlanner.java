package com.falsity.building;

import com.falsity.config.FalsityConfig;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a structure request into a concrete, bounded {@link BuildPlan}.
 *
 * <p>The planner picks a location in front of the player (based on where they
 * are looking), clamps every dimension to the configured maximum, and asks
 * {@link StructureLibrary} for the placements. It never touches the world, so a
 * plan can be inspected and tested before anything is built.
 */
public final class BuildPlanner {
	private BuildPlanner() {
	}

	/** Builds a plan, or empty when the structure is unknown or would be empty. */
	public static Optional<BuildPlan> plan(BuildRequest request, ServerPlayer player, FalsityConfig config) {
		String structure = request.structureOrDefault();
		int maxDimension = Math.max(2, config.building.maxDimension);

		int width = clamp(request.width(), defaultWidth(structure), maxDimension);
		int length = clamp(request.length(), defaultLength(structure), maxDimension);
		int height = clamp(request.height(), defaultHeight(structure), maxDimension);

		BlockPos origin = originInFrontOf(player, Math.max(width, length));

		Optional<List<BlockPlacement>> placements = StructureLibrary.generate(
			structure, request.materialOrDefault(), width, length, height, origin);
		if (placements.isEmpty() || placements.get().isEmpty()) {
			return Optional.empty();
		}

		AABB region = regionOf(placements.get());
		return Optional.of(new BuildPlan(
			placements.get(), region, width, length, height, structure, request.materialOrDefault()
		));
	}

	/** The block at the structure's minimum corner, on open ground in front of the player. */
	static BlockPos originInFrontOf(ServerPlayer player, int size) {
		Vec3 look = player.getLookAngle();
		Vec3 forward = new Vec3(look.x, 0.0, look.z);
		if (forward.lengthSqr() < 1.0E-6) {
			forward = new Vec3(0.0, 0.0, 1.0);
		}
		forward = forward.normalize();

		double offset = size / 2.0 + 2.0;
		Vec3 target = player.position().add(forward.scale(offset));
		// Stand the structure on the block under the player's feet.
		return BlockPos.containing(target).below();
	}

	private static AABB regionOf(List<BlockPlacement> placements) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPlacement placement : placements) {
			BlockPos pos = placement.pos();
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
	}

	private static int clamp(Integer requested, int fallback, int maximum) {
		int value = requested == null ? fallback : requested;
		return Math.max(2, Math.min(maximum, value));
	}

	static int defaultWidth(String structure) {
		return switch (structure) {
			case "house", "castle" -> 9;
			case "wall", "bridge", "staircase", "stair", "stairs", "stairway", "fence", "gate" -> 10;
			case "farm" -> 9;
			case "storage", "storage room", "storage chest", "chest", "room" -> 5;
			case "platform", "floor", "foundation", "slab" -> 5;
			default -> 7;
		};
	}

	static int defaultLength(String structure) {
		return switch (structure) {
			case "house" -> 7;
			case "castle", "farm" -> 9;
			case "storage", "storage room", "storage chest", "chest", "room" -> 5;
			case "platform", "floor", "foundation", "slab" -> 5;
			default -> 7;
		};
	}

	static int defaultHeight(String structure) {
		return switch (structure) {
			case "house" -> 4;
			case "castle" -> 5;
			case "storage", "storage room", "storage chest", "chest", "room" -> 3;
			case "wall", "fence", "gate" -> 3;
			case "tower", "pillar", "column" -> 8;
			default -> 3;
		};
	}
}
