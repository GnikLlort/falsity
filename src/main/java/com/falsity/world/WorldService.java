package com.falsity.world;

import com.falsity.actions.companion.HostileScanner;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Reads the answers to world questions out of the running game.
 *
 * <p>This is the only place that looks at the world on Falsity's behalf.
 * Everything it reports was read from the server at the moment of asking: the
 * player's position, the biome at that position, the structure search the
 * {@code /locate} command uses, and a bounded scan of loaded blocks. The words
 * come from {@link WorldAnswers}; nothing here is phrased by a model.
 *
 * <p>All methods must be called on the server thread.
 */
public final class WorldService {
	/** Ore and cave scans never look further than this vertically. */
	private static final int MAX_VERTICAL_RADIUS = 32;

	private WorldService() {
	}

	/** The id path of the dimension a level is ("overworld", "the_nether", "the_end"). */
	public static String dimensionPath(ServerLevel level) {
		return level.dimension().identifier().getPath();
	}

	/** The id path of the biome at a position, or empty when it has no key. */
	public static String biomePath(ServerLevel level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
	}

	/**
	 * The verified facts given to the model with each message: dimension,
	 * position (when sharing is allowed), biome, time and weather.
	 */
	public static String facts(Player player, FalsityEntity falsity, FalsityConfig config) {
		if (!(falsity.level() instanceof ServerLevel level)) {
			return "";
		}
		BlockPos pos = player.blockPosition();
		return WorldAnswers.facts(
			pos.getX(), pos.getY(), pos.getZ(),
			dimensionPath(level),
			biomePath(level, pos),
			level.getDefaultClockTime(),
			level.isRaining(),
			level.isThundering(),
			mayShareCoordinates(level, config)
		);
	}

	/**
	 * Whether an answer given in {@code level} may contain coordinates. The
	 * server's own switch decides first. On top of that, a world that hides
	 * coordinates on the debug screen (the {@code reducedDebugInfo} game rule)
	 * does not get them from Falsity either: that rule exists so players cannot
	 * read their position, and a companion that recites it would undo it.
	 */
	public static boolean mayShareCoordinates(ServerLevel level, FalsityConfig config) {
		return config.world.shareCoordinates && !level.getGameRules().get(GameRules.REDUCED_DEBUG_INFO);
	}

	/** Answers a world question for a player standing in {@code level}. */
	public static String answer(ServerLevel level, Player player, FalsityEntity falsity, WorldQuery query, FalsityConfig config) {
		BlockPos pos = player.blockPosition();
		String dimension = dimensionPath(level);
		boolean share = mayShareCoordinates(level, config);

		return switch (query.kind()) {
			case POSITION -> WorldAnswers.position(pos.getX(), pos.getY(), pos.getZ(), dimension, share);
			case BIOME -> WorldAnswers.biome(biomePath(level, pos));
			case DIMENSION -> WorldAnswers.dimension(dimension);
			case TIME -> WorldAnswers.time(level.getDefaultClockTime(), level.isThundering(), dimension);
			case WEATHER -> WorldAnswers.weather(level.isRaining(), level.isThundering(), dimension);
			case BLOCK_BELOW -> WorldAnswers.blockBelow(blockBelow(level, pos));
			case SPAWN -> {
				BlockPos spawn = level.getRespawnData().pos();
				yield WorldAnswers.spawn(pos.getX(), pos.getZ(), spawn.getX(), spawn.getZ(), dimension, share);
			}
			case STRUCTURE -> structure(level, pos, dimension, query, config);
			case ORE -> {
				OreCatalog.Entry only = OreCatalog.byKey(query.subject()).orElse(null);
				int radius = config.world.scanRadius;
				List<TerrainScanner.OreFind> finds = TerrainScanner.findOres(
					new LevelGrid(level), pos.getX(), pos.getY(), pos.getZ(), radius, Math.min(radius, MAX_VERTICAL_RADIUS), only);
				yield WorldAnswers.ores(finds, pos.getX(), pos.getY(), pos.getZ(), radius, only, share);
			}
			case CAVE -> {
				int radius = config.world.scanRadius;
				Optional<TerrainScanner.CaveFind> cave = TerrainScanner.findCave(
					new LevelGrid(level), pos.getX(), pos.getY(), pos.getZ(), radius, Math.min(radius, MAX_VERTICAL_RADIUS), true);
				yield WorldAnswers.cave(cave, pos.getX(), pos.getY(), pos.getZ(), radius, share);
			}
			case SURROUNDINGS -> WorldAnswers.surroundings(
				biomePath(level, pos),
				"the_nether".equals(dimension) || "the_end".equals(dimension)
					? ""
					: "It's " + WorldAnswers.phase(level.getDefaultClockTime()) + ".",
				WorldAnswers.weather(level.isRaining(), level.isThundering(), dimension),
				HostileScanner.nearbyHostiles(level, falsity, 16.0).size()
			);
		};
	}

	/** Whether a kind of question runs a search that deserves a cooldown and a permission. */
	public static boolean isSearch(WorldQuery.Kind kind) {
		return kind == WorldQuery.Kind.STRUCTURE || kind == WorldQuery.Kind.ORE || kind == WorldQuery.Kind.CAVE;
	}

	private static String blockBelow(ServerLevel level, BlockPos feet) {
		// The block under the feet, or the first solid one a few blocks down when mid-jump.
		for (int depth = 1; depth <= 4; depth++) {
			BlockPos below = feet.below(depth);
			if (!level.isLoaded(below)) {
				return "";
			}
			BlockState state = level.getBlockState(below);
			if (!state.isAir()) {
				return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			}
		}
		return "air";
	}

	private static String structure(ServerLevel level, BlockPos pos, String dimension, WorldQuery query, FalsityConfig config) {
		Optional<StructureCatalog.Entry> entry = StructureCatalog.byKey(query.subject());
		if (entry.isEmpty()) {
			return WorldAnswers.structureUnknown(query.spoken());
		}
		StructureCatalog.Entry structure = entry.get();
		if (!structure.dimension().isEmpty() && !structure.dimension().equals(dimension)) {
			return WorldAnswers.structureWrongDimension(structure, dimension);
		}

		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		List<Holder<Structure>> wanted = new ArrayList<>();
		for (String id : structure.ids()) {
			// Ids this game version does not have are skipped, never guessed at.
			registry.get(Identifier.withDefaultNamespace(id)).ifPresent(wanted::add);
		}
		int radiusChunks = config.world.structureSearchRadiusChunks;
		if (wanted.isEmpty()) {
			return WorldAnswers.structureNotFound(structure, radiusChunks * 16);
		}

		// The same search /locate runs. It reads generation data; it does not build chunks.
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
			.findNearestMapStructure(level, HolderSet.direct(wanted), pos, radiusChunks, false);
		if (found == null) {
			return WorldAnswers.structureNotFound(structure, radiusChunks * 16);
		}
		BlockPos where = found.getFirst();
		return WorldAnswers.structureFound(
			structure, pos.getX(), pos.getZ(), where.getX(), where.getZ(), mayShareCoordinates(level, config));
	}

	/**
	 * Lets {@link TerrainScanner} read the live world. Only loaded chunks are
	 * read: an unloaded position is reported as unknown so a scan can never
	 * load or generate terrain.
	 */
	private static final class LevelGrid implements TerrainScanner.Grid {
		private final ServerLevel level;
		private final Map<Long, Boolean> loadedChunks = new HashMap<>();
		private final Map<Long, Integer> surface = new HashMap<>();
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		private LevelGrid(ServerLevel level) {
			this.level = level;
		}

		private boolean loaded(int x, int z) {
			int chunkX = x >> 4;
			int chunkZ = z >> 4;
			long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
			return this.loadedChunks.computeIfAbsent(key, ignored -> this.level.hasChunk(chunkX, chunkZ));
		}

		@Override
		public String blockAt(int x, int y, int z) {
			if (!loaded(x, z)) {
				return null;
			}
			BlockState state = this.level.getBlockState(this.cursor.set(x, y, z));
			if (state.isAir()) {
				return "air";
			}
			return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		}

		@Override
		public boolean isOpen(int x, int y, int z) {
			return loaded(x, z) && this.level.getBlockState(this.cursor.set(x, y, z)).isAir();
		}

		@Override
		public int surfaceY(int x, int z) {
			if (!loaded(x, z)) {
				return this.level.getMinY();
			}
			long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
			return this.surface.computeIfAbsent(key, ignored ->
				this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
		}

		@Override
		public int minY() {
			return this.level.getMinY();
		}

		@Override
		public int maxY() {
			return this.level.getMaxY();
		}
	}
}
