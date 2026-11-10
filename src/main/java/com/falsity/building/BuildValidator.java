package com.falsity.building;

import com.falsity.config.FalsityConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Checks a plan before any block is placed.
 *
 * <p>This is the server's safety net. It enforces the configured size limits,
 * refuses dangerous blocks, requires loaded chunks, and — when inventory-based
 * building is on — checks that the player actually owns the materials. It also
 * refuses to overwrite anything that is already a solid structure. The AI never
 * gets to bypass these checks: a plan that fails them is never executed.
 */
public final class BuildValidator {
	/** Blocks that may never be placed through an ordinary building request. */
	private static final Set<String> FORBIDDEN = Set.of(
		"minecraft:command_block",
		"minecraft:chain_command_block",
		"minecraft:repeating_command_block",
		"minecraft:tnt",
		"minecraft:lava",
		"minecraft:flowing_lava",
		"minecraft:fire",
		"minecraft:end_portal",
		"minecraft:end_portal_frame",
		"minecraft:dragon_egg",
		"minecraft:spawner",
		"minecraft:lightning_rod"
	);

	/** Blocks that count as ground or undergrowth, and may be replaced. */
	private static final Set<String> REPLACEABLE = Set.of(
		"minecraft:air",
		"minecraft:grass",
		"minecraft:grass_block",
		"minecraft:dirt",
		"minecraft:coarse_dirt",
		"minecraft:podzol",
		"minecraft:rooted_dirt",
		"minecraft:tall_grass",
		"minecraft:large_grass",
		"minecraft:snow",
		"minecraft:powder_snow",
		"minecraft:leaves",
		"minecraft:vine",
		"minecraft:water",
		"minecraft:lava",
		"minecraft:seagrass",
		"minecraft:tall_seagrass",
		"minecraft:kelp",
		"minecraft:fire",
		"minecraft:small_dripleaf",
		"minecraft:big_dripleaf"
	);

	private BuildValidator() {
	}

	/**
	 * Validates a plan.
	 *
	 * @return empty when the plan may proceed, otherwise a short reason it may not
	 */
	public static Optional<String> validate(BuildPlan plan, ServerLevel level, ServerPlayer player, FalsityConfig config) {
		if (plan.totalBlocks() > config.building.maxBlocks) {
			return Optional.of("That plan is too big (" + plan.totalBlocks() + " blocks; the limit is " + config.building.maxBlocks + ").");
		}
		if (plan.width() > config.building.maxDimension || plan.length() > config.building.maxDimension || plan.height() > config.building.maxDimension) {
			return Optional.of("That structure is too large for the configured limits.");
		}

		AABB region = plan.region();
		double centreX = (region.minX + region.maxX) / 2.0;
		double centreZ = (region.minZ + region.maxZ) / 2.0;
		double distance = Math.sqrt((centreX - player.getX()) * (centreX - player.getX()) + (centreZ - player.getZ()) * (centreZ - player.getZ()));
		if (distance > config.building.maxDistanceFromPlayer) {
			return Optional.of("That is too far away to build safely.");
		}

		if (!chunksLoaded(level, region)) {
			return Optional.of("The area is not loaded yet; I can't build there.");
		}

		for (BlockPlacement placement : plan.placements()) {
			BlockState state = placement.state();
			if (isForbidden(state)) {
				return Optional.of("I won't place that kind of block.");
			}
			if (!isReplaceable(level.getBlockState(placement.pos()))) {
				return Optional.of("There is already something solid where I would need to build.");
			}
		}

		if (config.building.inventoryBased && !config.building.creativeGeneration) {
			Optional<String> missing = missingMaterials(plan, player);
			if (missing.isPresent()) {
				return missing;
			}
		}

		return Optional.empty();
	}

	private static boolean chunksLoaded(ServerLevel level, AABB region) {
		ChunkPos minChunk = ChunkPos.containing(new BlockPos(
			(int) Math.floor(region.minX), 0, (int) Math.floor(region.minZ)));
		ChunkPos maxChunk = ChunkPos.containing(new BlockPos(
			(int) Math.floor(region.maxX), 0, (int) Math.floor(region.maxZ)));
		for (int chunkX = minChunk.x(); chunkX <= maxChunk.x(); chunkX++) {
			for (int chunkZ = minChunk.z(); chunkZ <= maxChunk.z(); chunkZ++) {
				// A null chunk means it is not loaded at full status yet.
				if (level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isForbidden(BlockState state) {
		return blockId(state).map(FORBIDDEN::contains).orElse(false);
	}

	private static boolean isReplaceable(BlockState existing) {
		if (existing.isAir()) {
			return true;
		}
		return blockId(existing).map(REPLACEABLE::contains).orElse(false);
	}

	private static Optional<String> blockId(BlockState state) {
		try {
			Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
			return key == null ? Optional.empty() : Optional.of(key.toString());
		} catch (RuntimeException missing) {
			return Optional.empty();
		}
	}

	private static Optional<String> missingMaterials(BuildPlan plan, ServerPlayer player) {
		Map<Item, Integer> needed = BuildMaterials.required(plan);
		List<String> missing = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
			int have = BuildMaterials.count(player, entry.getKey());
			if (have < entry.getValue()) {
				missing.add(entry.getValue() - have + " more " + describeItem(entry.getKey()));
			}
		}
		if (missing.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of("I need " + String.join(", ", missing) + " to build that.");
	}

	private static String describeItem(Item item) {
		try {
			Identifier key = BuiltInRegistries.ITEM.getKey(item);
			String id = key == null ? "materials" : key.toString();
			int colon = id.indexOf(':');
			return colon >= 0 ? id.substring(colon + 1).replace('_', ' ') : id;
		} catch (RuntimeException missing) {
			return "materials";
		}
	}

	/** Whether a block state is one the planner is allowed to place at all. */
	public static boolean isAllowedBlock(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.AIR) {
			return false;
		}
		return blockId(state).map(id -> !FORBIDDEN.contains(id)).orElse(false);
	}
}
