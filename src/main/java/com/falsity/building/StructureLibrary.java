package com.falsity.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generates the block placements for the structures Falsity can build.
 *
 * <p>Every method returns a plain list of {@link BlockPlacement}s relative to
 * an origin; none of them touch the world. That keeps the geometry testable and
 * means the executor is the only thing that ever changes a block. Start with
 * reliable, simple shapes: a house is walls, a floor and a roof, nothing more.
 */
public final class StructureLibrary {
	private StructureLibrary() {
	}

	/**
	 * Builds the placement list for a structure.
	 *
	 * @param origin the block at the structure's minimum corner (floor level)
	 */
	public static Optional<List<BlockPlacement>> generate(
		String structure,
		String material,
		int width,
		int length,
		int height,
		BlockPos origin
	) {
		String kind = structure == null ? "" : structure.trim().toLowerCase(Locale.ROOT);
		BlockState block = materialBlock(material).defaultBlockState();

		return switch (kind) {
			case "house", "hut", "cabin", "cottage", "home" -> house(width, length, height, origin, block);
			case "castle" -> castle(width, length, height, origin, block);
			case "storage", "storage room", "storage chest", "chest", "room" -> room(width, length, height, origin, block);
			case "wall", "fence", "gate" -> wall(width, height, origin, block, kind);
			case "bridge" -> bridge(width, origin, block);
			case "tower", "pillar", "column" -> tower(height, origin, block);
			case "staircase", "stair", "stairs", "stairway" -> staircase(width, origin, block);
			case "farm" -> farm(width, length, origin);
			case "platform", "floor", "foundation", "slab" -> platform(width, length, origin, block);
			default -> Optional.empty();
		};
	}

	private static Optional<List<BlockPlacement>> house(int width, int length, int height, BlockPos origin, BlockState block) {
		int w = Math.max(3, width);
		int l = Math.max(3, length);
		int h = Math.max(3, height);
		List<BlockPlacement> placements = new ArrayList<>();

		// Floor.
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, 0, z), block));
			}
		}

		// Walls, with a two-block door gap in the front wall.
		int doorX = w / 2;
		for (int y = 1; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (x == doorX) {
					continue; // door opening
				}
				placements.add(new BlockPlacement(origin.offset(x, y, 0), block));
				placements.add(new BlockPlacement(origin.offset(x, y, l - 1), block));
			}
			for (int z = 1; z < l - 1; z++) {
				placements.add(new BlockPlacement(origin.offset(0, y, z), block));
				placements.add(new BlockPlacement(origin.offset(w - 1, y, z), block));
			}
		}

		// Roof.
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, h, z), block));
			}
		}

		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> castle(int width, int length, int height, BlockPos origin, BlockState block) {
		int w = Math.max(6, width);
		int l = Math.max(6, length);
		int h = Math.max(4, height);
		List<BlockPlacement> placements = new ArrayList<>();

		// Floor.
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, 0, z), block));
			}
		}

		// Outer walls.
		for (int y = 1; y < h; y++) {
			for (int x = 0; x < w; x++) {
				placements.add(new BlockPlacement(origin.offset(x, y, 0), block));
				placements.add(new BlockPlacement(origin.offset(x, y, l - 1), block));
			}
			for (int z = 1; z < l - 1; z++) {
				placements.add(new BlockPlacement(origin.offset(0, y, z), block));
				placements.add(new BlockPlacement(origin.offset(w - 1, y, z), block));
			}
		}

		// Corner towers, two blocks wide and a little taller than the walls.
		int towerHeight = h + 2;
		int[][] corners = {{0, 0}, {w - 2, 0}, {0, l - 2}, {w - 2, l - 2}};
		for (int[] corner : corners) {
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					for (int y = 0; y < towerHeight; y++) {
						placements.add(new BlockPlacement(origin.offset(corner[0] + dx, y, corner[1] + dz), block));
					}
				}
			}
		}

		// Roof over the walls (the towers poke through; that is fine visually).
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, h, z), block));
			}
		}

		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> room(int width, int length, int height, BlockPos origin, BlockState block) {
		int w = Math.max(3, width);
		int l = Math.max(3, length);
		int h = Math.max(3, height);
		List<BlockPlacement> placements = new ArrayList<>();

		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, 0, z), block));
			}
		}

		int doorX = w / 2;
		for (int y = 1; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (x == doorX) {
					continue;
				}
				placements.add(new BlockPlacement(origin.offset(x, y, 0), block));
				placements.add(new BlockPlacement(origin.offset(x, y, l - 1), block));
			}
			for (int z = 1; z < l - 1; z++) {
				placements.add(new BlockPlacement(origin.offset(0, y, z), block));
				placements.add(new BlockPlacement(origin.offset(w - 1, y, z), block));
			}
		}

		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, h, z), block));
			}
		}

		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> wall(int width, int height, BlockPos origin, BlockState block, String kind) {
		int length = Math.max(2, width);
		int h = Math.max(1, height);
		BlockState placed = "fence".equals(kind) || "gate".equals(kind) ? Blocks.OAK_FENCE.defaultBlockState() : block;
		List<BlockPlacement> placements = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			for (int y = 0; y < h; y++) {
				placements.add(new BlockPlacement(origin.offset(i, y, 0), placed));
			}
		}
		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> bridge(int width, BlockPos origin, BlockState block) {
		int length = Math.max(2, width);
		List<BlockPlacement> placements = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			placements.add(new BlockPlacement(origin.offset(i, 0, 0), block));
		}
		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> tower(int height, BlockPos origin, BlockState block) {
		int h = Math.max(2, height);
		List<BlockPlacement> placements = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			placements.add(new BlockPlacement(origin.offset(0, y, 0), block));
		}
		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> staircase(int width, BlockPos origin, BlockState block) {
		int steps = Math.max(2, width);
		List<BlockPlacement> placements = new ArrayList<>();
		for (int i = 0; i < steps; i++) {
			for (int y = 0; y <= i; y++) {
				placements.add(new BlockPlacement(origin.offset(i, y, 0), block));
			}
		}
		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> farm(int width, int length, BlockPos origin) {
		int size = Math.max(5, Math.min(width, length));
		int middle = size / 2;
		List<BlockPlacement> placements = new ArrayList<>();
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				BlockPos pos = origin.offset(x, 0, z);
				if (z == middle) {
					placements.add(new BlockPlacement(pos, Blocks.WATER.defaultBlockState()));
				} else {
					placements.add(new BlockPlacement(pos, Blocks.FARMLAND.defaultBlockState()));
				}
			}
		}
		return Optional.of(placements);
	}

	private static Optional<List<BlockPlacement>> platform(int width, int length, BlockPos origin, BlockState block) {
		int w = Math.max(2, width);
		int l = Math.max(2, length);
		List<BlockPlacement> placements = new ArrayList<>();
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				placements.add(new BlockPlacement(origin.offset(x, 0, z), block));
			}
		}
		return Optional.of(placements);
	}

	/** Maps a material keyword to a real block. Never invents a block id. */
	static Block materialBlock(String material) {
		if (material == null || material.isBlank()) {
			return Blocks.OAK_PLANKS;
		}
		String m = material.trim().toLowerCase(Locale.ROOT);

		if (m.contains("cobble")) {
			return Blocks.COBBLESTONE;
		}
		if (m.contains("brick")) {
			return Blocks.BRICKS;
		}
		if (m.contains("sandstone")) {
			return Blocks.SANDSTONE;
		}
		if (m.contains("diorite")) {
			return Blocks.DIORITE;
		}
		if (m.contains("granite")) {
			return Blocks.GRANITE;
		}
		if (m.contains("andesite")) {
			return Blocks.ANDESITE;
		}
		if (m.contains("prismarine")) {
			return Blocks.PRISMARINE;
		}
		if (m.contains("stone")) {
			return Blocks.STONE;
		}
		if (m.contains("glass")) {
			return Blocks.GLASS;
		}
		if (m.contains("wool")) {
			return Blocks.WOOL.white();
		}
		if (m.contains("log") || m.contains("logs")) {
			return logBlock(m);
		}
		// Everything else is treated as planks of some wood.
		return plankBlock(m);
	}

	private static Block logBlock(String m) {
		if (m.contains("dark oak") || m.contains("dark_oak")) {
			return Blocks.DARK_OAK_LOG;
		}
		if (m.contains("spruce")) {
			return Blocks.SPRUCE_LOG;
		}
		if (m.contains("birch")) {
			return Blocks.BIRCH_LOG;
		}
		if (m.contains("jungle")) {
			return Blocks.JUNGLE_LOG;
		}
		if (m.contains("acacia")) {
			return Blocks.ACACIA_LOG;
		}
		return Blocks.OAK_LOG;
	}

	private static Block plankBlock(String m) {
		if (m.contains("dark oak") || m.contains("dark_oak")) {
			return Blocks.DARK_OAK_PLANKS;
		}
		if (m.contains("spruce")) {
			return Blocks.SPRUCE_PLANKS;
		}
		if (m.contains("birch")) {
			return Blocks.BIRCH_PLANKS;
		}
		if (m.contains("jungle")) {
			return Blocks.JUNGLE_PLANKS;
		}
		if (m.contains("acacia")) {
			return Blocks.ACACIA_PLANKS;
		}
		return Blocks.OAK_PLANKS;
	}
}
