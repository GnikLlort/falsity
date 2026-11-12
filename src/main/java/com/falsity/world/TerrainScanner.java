package com.falsity.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Looks through the blocks around a point for ores and caves.
 *
 * <p>The scan reads blocks through {@link Grid}, so the same code runs against
 * the live world in the game and against a small fake world in unit tests. It
 * only reports what it actually read: an ore is listed because a matching block
 * was found at a stated position, and a cave because a connected pocket of open
 * space was found underground.
 *
 * <p>The scan is bounded. It never looks further than the radius it is given
 * and never asks the grid for a position outside it, so it cannot load or
 * generate distant chunks.
 */
public final class TerrainScanner {
	/** Smallest connected open space that counts as a cave, in blocks. */
	public static final int MIN_CAVE_BLOCKS = 24;

	/** Flood fill stops counting a pocket once it is clearly a cave. */
	public static final int CAVE_FILL_LIMIT = 400;

	/** A cave must lie at least this far below the surface above it. */
	public static final int MIN_CAVE_DEPTH = 4;

	/** What the scanner can read from a world. */
	public interface Grid {
		/**
		 * The id path of the block at a position ("diamond_ore", "air"), or
		 * null when the position is not loaded and must not be read.
		 */
		String blockAt(int x, int y, int z);

		/** Whether the position is open space a player could stand in (air). */
		boolean isOpen(int x, int y, int z);

		/** The height of the highest solid ground in a column. */
		int surfaceY(int x, int z);

		/** Lowest and highest buildable heights. */
		int minY();

		int maxY();
	}

	/** A position in the world. */
	public record Pos(int x, int y, int z) {
		public double distanceTo(int ox, int oy, int oz) {
			double dx = this.x - ox;
			double dy = this.y - oy;
			double dz = this.z - oz;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/**
	 * One kind of ore found nearby.
	 *
	 * @param ore     which ore
	 * @param blocks  how many matching blocks were read
	 * @param nearest the closest one
	 */
	public record OreFind(OreCatalog.Entry ore, int blocks, Pos nearest) {
	}

	/**
	 * A cave found nearby.
	 *
	 * @param nearest   the closest open block of the cave
	 * @param size      how many connected open blocks were counted
	 * @param sizeCapped whether counting stopped at {@link #CAVE_FILL_LIMIT}
	 */
	public record CaveFind(Pos nearest, int size, boolean sizeCapped) {
	}

	private TerrainScanner() {
	}

	/**
	 * Finds ores within {@code radius} blocks horizontally and
	 * {@code verticalRadius} blocks vertically of the centre. When
	 * {@code only} is non-null, just that ore is looked for. Results are sorted
	 * nearest first.
	 */
	public static List<OreFind> findOres(Grid grid, int cx, int cy, int cz, int radius, int verticalRadius, OreCatalog.Entry only) {
		Map<String, int[]> counts = new LinkedHashMap<>();
		Map<String, Pos> nearest = new LinkedHashMap<>();
		Map<String, Double> nearestDistance = new LinkedHashMap<>();

		int minY = Math.max(grid.minY(), cy - verticalRadius);
		int maxY = Math.min(grid.maxY(), cy + verticalRadius);
		for (int x = cx - radius; x <= cx + radius; x++) {
			for (int z = cz - radius; z <= cz + radius; z++) {
				for (int y = minY; y <= maxY; y++) {
					String block = grid.blockAt(x, y, z);
					if (block == null || block.equals("air") || block.equals("stone") || block.equals("deepslate")) {
						continue;
					}
					Optional<OreCatalog.Entry> ore = OreCatalog.byBlockId(block);
					if (ore.isEmpty() || (only != null && !only.key().equals(ore.get().key()))) {
						continue;
					}
					String key = ore.get().key();
					counts.computeIfAbsent(key, ignored -> new int[1])[0]++;
					double distance = new Pos(x, y, z).distanceTo(cx, cy, cz);
					Double best = nearestDistance.get(key);
					if (best == null || distance < best) {
						nearestDistance.put(key, distance);
						nearest.put(key, new Pos(x, y, z));
					}
				}
			}
		}

		List<OreFind> finds = new ArrayList<>();
		for (Map.Entry<String, int[]> entry : counts.entrySet()) {
			OreCatalog.Entry ore = OreCatalog.byKey(entry.getKey()).orElseThrow();
			finds.add(new OreFind(ore, entry.getValue()[0], nearest.get(entry.getKey())));
		}
		finds.sort(Comparator.comparingDouble(find -> find.nearest().distanceTo(cx, cy, cz)));
		return finds;
	}

	/**
	 * Finds the nearest cave: a connected pocket of at least
	 * {@link #MIN_CAVE_BLOCKS} open blocks lying underground. The pocket the
	 * centre itself stands in is ignored when {@code ignoreOwnPocket} is set,
	 * so "is there a cave nearby" asked from inside a tunnel finds another one.
	 */
	public static Optional<CaveFind> findCave(Grid grid, int cx, int cy, int cz, int radius, int verticalRadius, boolean ignoreOwnPocket) {
		int minY = Math.max(grid.minY(), cy - verticalRadius);
		int maxY = Math.min(grid.maxY(), cy + verticalRadius);
		Bounds bounds = new Bounds(cx - radius, cx + radius, minY, maxY, cz - radius, cz + radius);

		Set<Long> visited = new HashSet<>();
		if (ignoreOwnPocket) {
			// Mark the pocket around the centre as seen so it is not reported.
			for (int dy = 0; dy <= 1; dy++) {
				if (bounds.contains(cx, cy + dy, cz) && isUnderground(grid, cx, cy + dy, cz)) {
					fill(grid, bounds, cx, cy + dy, cz, visited, Integer.MAX_VALUE);
				}
			}
		}

		CaveFind best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int x = bounds.minX; x <= bounds.maxX; x++) {
			for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
				for (int y = bounds.minY; y <= bounds.maxY; y++) {
					if (visited.contains(key(x, y, z)) || !isUnderground(grid, x, y, z)) {
						continue;
					}
					Pocket pocket = fill(grid, bounds, x, y, z, visited, CAVE_FILL_LIMIT);
					if (pocket.size < MIN_CAVE_BLOCKS) {
						continue;
					}
					Pos closest = pocket.closestTo(cx, cy, cz);
					double distance = closest.distanceTo(cx, cy, cz);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new CaveFind(closest, pocket.size, pocket.size >= CAVE_FILL_LIMIT);
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean isUnderground(Grid grid, int x, int y, int z) {
		return grid.isOpen(x, y, z) && y <= grid.surfaceY(x, z) - MIN_CAVE_DEPTH;
	}

	private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		boolean contains(int x, int y, int z) {
			return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
		}
	}

	private static final class Pocket {
		private int size;
		private final List<Pos> cells = new ArrayList<>();

		Pos closestTo(int cx, int cy, int cz) {
			Pos best = this.cells.get(0);
			double bestDistance = best.distanceTo(cx, cy, cz);
			for (Pos cell : this.cells) {
				double distance = cell.distanceTo(cx, cy, cz);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = cell;
				}
			}
			return best;
		}
	}

	private static final int[][] NEIGHBOURS = {
		{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
	};

	/** Flood fills connected underground open space, stopping at {@code limit} cells. */
	private static Pocket fill(Grid grid, Bounds bounds, int startX, int startY, int startZ, Set<Long> visited, int limit) {
		Pocket pocket = new Pocket();
		ArrayDeque<Pos> queue = new ArrayDeque<>();
		if (visited.add(key(startX, startY, startZ))) {
			queue.add(new Pos(startX, startY, startZ));
		}
		while (!queue.isEmpty()) {
			Pos cell = queue.poll();
			pocket.size++;
			pocket.cells.add(cell);
			if (pocket.size >= limit) {
				break;
			}
			for (int[] step : NEIGHBOURS) {
				int x = cell.x() + step[0];
				int y = cell.y() + step[1];
				int z = cell.z() + step[2];
				if (!bounds.contains(x, y, z) || visited.contains(key(x, y, z)) || !isUnderground(grid, x, y, z)) {
					continue;
				}
				visited.add(key(x, y, z));
				queue.add(new Pos(x, y, z));
			}
		}
		return pocket;
	}

	private static long key(int x, int y, int z) {
		return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
	}
}
