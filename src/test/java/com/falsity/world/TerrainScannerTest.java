package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ore and cave scans over a small made-up world: solid stone below Y 64, air above. */
class TerrainScannerTest {
	/** Stone up to the surface, air above it, plus whatever a test places or carves. */
	private static final class FakeWorld implements TerrainScanner.Grid {
		private final Map<Long, String> placed = new HashMap<>();
		private final Set<Long> unloadedColumns = new HashSet<>();
		private final int surface = 64;
		int reads;

		void set(int x, int y, int z, String block) {
			this.placed.put(key(x, y, z), block);
		}

		void carve(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						set(x, y, z, "air");
					}
				}
			}
		}

		void unload(int x, int z) {
			this.unloadedColumns.add(key(x, 0, z));
		}

		@Override
		public String blockAt(int x, int y, int z) {
			this.reads++;
			if (this.unloadedColumns.contains(key(x, 0, z))) {
				return null;
			}
			String block = this.placed.get(key(x, y, z));
			if (block != null) {
				return block;
			}
			return y > this.surface ? "air" : "stone";
		}

		@Override
		public boolean isOpen(int x, int y, int z) {
			return "air".equals(blockAt(x, y, z));
		}

		@Override
		public int surfaceY(int x, int z) {
			return this.surface;
		}

		@Override
		public int minY() {
			return -64;
		}

		@Override
		public int maxY() {
			return 319;
		}

		private static long key(int x, int y, int z) {
			return ((long) (x + 10_000) * 20_001L + (z + 10_000)) * 1_000L + (y + 100);
		}
	}

	@Test
	void findsTheNearestBlockOfEachOre() {
		FakeWorld world = new FakeWorld();
		world.set(5, 10, 0, "diamond_ore");
		world.set(2, 12, 1, "deepslate_diamond_ore");
		world.set(8, 12, 0, "iron_ore");

		List<TerrainScanner.OreFind> finds = TerrainScanner.findOres(world, 0, 12, 0, 10, 8, null);

		assertEquals(2, finds.size());
		assertEquals("diamond", finds.get(0).ore().key(), "nearest first");
		assertEquals(2, finds.get(0).blocks(), "both kinds of diamond ore count as diamond");
		assertEquals(new TerrainScanner.Pos(2, 12, 1), finds.get(0).nearest());
		assertEquals("iron", finds.get(1).ore().key());
		assertEquals(1, finds.get(1).blocks());
		assertEquals(new TerrainScanner.Pos(8, 12, 0), finds.get(1).nearest());
	}

	@Test
	void looksOnlyForTheOreThatWasAskedAbout() {
		FakeWorld world = new FakeWorld();
		world.set(1, 12, 0, "diamond_ore");
		world.set(8, 12, 0, "iron_ore");

		List<TerrainScanner.OreFind> finds =
			TerrainScanner.findOres(world, 0, 12, 0, 10, 8, OreCatalog.byKey("iron").orElseThrow());

		assertEquals(1, finds.size());
		assertEquals("iron", finds.get(0).ore().key());
	}

	@Test
	void reportsNothingWhenThereIsNothing() {
		FakeWorld world = new FakeWorld();
		world.set(3, 12, 3, "dirt");
		world.set(4, 12, 3, "diamond_block");

		assertTrue(TerrainScanner.findOres(world, 0, 12, 0, 10, 8, null).isEmpty(), "a diamond block is not diamond ore");
	}

	@Test
	void staysWithinTheRadiusItWasGiven() {
		FakeWorld world = new FakeWorld();
		world.set(11, 12, 0, "diamond_ore");
		world.set(0, 21, 0, "gold_ore");
		world.set(0, 12, -11, "coal_ore");

		assertTrue(TerrainScanner.findOres(world, 0, 12, 0, 10, 8, null).isEmpty());

		world.set(10, 20, -10, "emerald_ore");
		assertEquals(1, TerrainScanner.findOres(world, 0, 12, 0, 10, 8, null).size(), "the corner of the box is still inside");
	}

	@Test
	void neverReportsFromChunksThatAreNotLoaded() {
		FakeWorld world = new FakeWorld();
		world.set(3, 12, 0, "diamond_ore");
		world.unload(3, 0);

		assertTrue(TerrainScanner.findOres(world, 0, 12, 0, 10, 8, null).isEmpty());
		assertTrue(TerrainScanner.findCave(world, 0, 12, 0, 6, 6, false).isEmpty());
	}

	@Test
	void theWorldEdgesLimitTheScan() {
		FakeWorld world = new FakeWorld();
		world.set(0, -64, 0, "diamond_ore");

		List<TerrainScanner.OreFind> finds = TerrainScanner.findOres(world, 0, -60, 0, 2, 32, null);

		assertEquals(1, finds.size());
		int columns = 5 * 5;
		int layers = (-60 + 32) - (-64) + 1;
		assertEquals(columns * layers, world.reads, "nothing below the bottom of the world is read");
	}

	@Test
	void aSolidWorldHasNoCave() {
		assertTrue(TerrainScanner.findCave(new FakeWorld(), 0, 30, 0, 8, 8, false).isEmpty());
	}

	@Test
	void openGroundUnderTheSkyIsNotACave() {
		FakeWorld world = new FakeWorld();

		assertTrue(TerrainScanner.findCave(world, 0, 66, 0, 8, 8, false).isEmpty(), "standing on the surface, the air around is not a cave");
	}

	@Test
	void findsAnUndergroundRoom() {
		FakeWorld world = new FakeWorld();
		world.carve(4, 20, 4, 7, 22, 7);

		Optional<TerrainScanner.CaveFind> cave = TerrainScanner.findCave(world, 0, 21, 0, 10, 8, false);

		assertTrue(cave.isPresent());
		assertEquals(48, cave.get().size());
		assertFalse(cave.get().sizeCapped());
		assertEquals(new TerrainScanner.Pos(4, 21, 4), cave.get().nearest(), "the nearest open block of the room");
	}

	@Test
	void aSmallHoleIsNotACave() {
		FakeWorld world = new FakeWorld();
		world.carve(4, 20, 4, 5, 21, 5);

		assertTrue(TerrainScanner.findCave(world, 0, 21, 0, 10, 8, false).isEmpty(), "8 open blocks is a hole, not a cave");
	}

	@Test
	void aShallowDipIsNotACave() {
		FakeWorld world = new FakeWorld();
		world.carve(-3, 62, -3, 3, 64, 3);

		assertTrue(TerrainScanner.findCave(world, 0, 64, 0, 8, 8, false).isEmpty(), "open space just under the surface is a dip in the ground");
	}

	@Test
	void fromInsideATunnelItFindsADifferentCave() {
		FakeWorld world = new FakeWorld();
		world.carve(-1, 20, -1, 1, 22, 1);
		world.carve(5, 20, 5, 8, 22, 8);

		Optional<TerrainScanner.CaveFind> own = TerrainScanner.findCave(world, 0, 20, 0, 10, 8, false);
		Optional<TerrainScanner.CaveFind> other = TerrainScanner.findCave(world, 0, 20, 0, 10, 8, true);

		assertEquals(new TerrainScanner.Pos(0, 20, 0), own.orElseThrow().nearest(), "without the flag the player's own tunnel is the nearest cave");
		assertEquals(new TerrainScanner.Pos(5, 20, 5), other.orElseThrow().nearest());
		assertEquals(48, other.get().size());
	}

	@Test
	void aVeryLargeCaveIsCountedOnlyUntilItIsClearlyLarge() {
		FakeWorld world = new FakeWorld();
		world.carve(-6, 20, -6, 6, 24, 6);

		Optional<TerrainScanner.CaveFind> cave = TerrainScanner.findCave(world, 0, 10, 0, 8, 16, false);

		assertTrue(cave.isPresent());
		assertTrue(cave.get().sizeCapped());
		assertEquals(TerrainScanner.CAVE_FILL_LIMIT, cave.get().size());
	}

	@Test
	void theNearerOfTwoCavesIsReported() {
		FakeWorld world = new FakeWorld();
		world.carve(6, 20, 0, 9, 22, 3);
		world.carve(-4, 20, 0, -2, 22, 3);

		Optional<TerrainScanner.CaveFind> cave = TerrainScanner.findCave(world, 0, 21, 0, 10, 8, false);

		assertEquals(-2, cave.orElseThrow().nearest().x());
	}
}
