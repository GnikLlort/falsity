package com.falsity.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Words for what the server found. Every method takes plain numbers and names
 * that were read from the game, and turns them into a sentence; none of them
 * looks anything up or fills a gap with a guess.
 *
 * <p>When coordinates may not be shared ({@code world.shareCoordinates} is
 * off), answers keep the direction and distance and leave the numbers out.
 */
public final class WorldAnswers {
	/** Ticks in one Minecraft day. */
	public static final long TICKS_PER_DAY = 24_000L;

	/** Beds can be used between these times of day in clear weather. */
	static final long SLEEP_START = 12_542L;
	static final long SLEEP_END = 23_459L;

	private WorldAnswers() {
	}

	/** "plains", "dark forest" from an id path such as {@code dark_forest}. */
	public static String words(String idPath) {
		if (idPath == null || idPath.isBlank()) {
			return "unknown";
		}
		return idPath.toLowerCase(Locale.ROOT).replace('_', ' ').replace('/', ' ').trim();
	}

	/** "the Overworld", "the Nether", "the End", or the id's words for anything else. */
	public static String dimensionName(String dimensionPath) {
		if (dimensionPath == null) {
			return "an unknown dimension";
		}
		return switch (dimensionPath) {
			case "overworld" -> "the Overworld";
			case "the_nether" -> "the Nether";
			case "the_end" -> "the End";
			default -> "the " + words(dimensionPath) + " dimension";
		};
	}

	public static String position(int x, int y, int z, String dimensionPath, boolean shareCoordinates) {
		if (!shareCoordinates) {
			return "We're in " + dimensionName(dimensionPath) + ". I'm not allowed to share exact coordinates here.";
		}
		return "We're at " + Directions.coordinates(x, y, z) + " in " + dimensionName(dimensionPath) + ".";
	}

	public static String biome(String biomePath) {
		if (biomePath == null || biomePath.isBlank()) {
			return "I can't tell which biome this is.";
		}
		String name = words(biomePath);
		return "This is " + article(name) + " " + name + " biome.";
	}

	public static String dimension(String dimensionPath) {
		return "We're in " + dimensionName(dimensionPath) + ".";
	}

	/** The time of day in words, with the clock time and what comes next. */
	public static String time(long clockTicks, boolean thundering, String dimensionPath) {
		if ("the_nether".equals(dimensionPath) || "the_end".equals(dimensionPath)) {
			return "There is no day or night in " + dimensionName(dimensionPath) + ".";
		}
		long ticks = Math.floorMod(clockTicks, TICKS_PER_DAY);
		StringBuilder builder = new StringBuilder();
		builder.append("It's ").append(phase(ticks)).append(", about ").append(clock(ticks)).append(" game time.");

		if (ticks < SLEEP_START) {
			builder.append(" Night falls in ").append(duration(SLEEP_START - ticks)).append('.');
			if (thundering) {
				builder.append(" There's a thunderstorm, so you can sleep now.");
			}
		} else if (ticks <= SLEEP_END) {
			builder.append(" You can sleep now; morning is ").append(duration(TICKS_PER_DAY - ticks)).append(" away.");
		} else {
			builder.append(" The sun is coming up.");
		}
		return builder.toString();
	}

	/** "morning", "night" and so on for a time of day in ticks (0 is 06:00). */
	public static String phase(long dayTicks) {
		long ticks = Math.floorMod(dayTicks, TICKS_PER_DAY);
		if (ticks < 1_000L) {
			return "early morning";
		}
		if (ticks < 5_500L) {
			return "morning";
		}
		if (ticks < 6_500L) {
			return "midday";
		}
		if (ticks < 11_500L) {
			return "afternoon";
		}
		if (ticks < 13_000L) {
			return "dusk";
		}
		if (ticks < 17_500L) {
			return "night";
		}
		if (ticks < 18_500L) {
			return "midnight";
		}
		if (ticks < 23_000L) {
			return "late night";
		}
		return "dawn";
	}

	/** The 24-hour clock time for a time of day in ticks: 0 is 06:00, 6000 is 12:00. */
	public static String clock(long dayTicks) {
		long ticks = Math.floorMod(dayTicks, TICKS_PER_DAY);
		long hour = (ticks / 1_000L + 6L) % 24L;
		long minute = (ticks % 1_000L) * 60L / 1_000L;
		return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
	}

	/** Real time for a number of game ticks: "about 4 minutes", "less than a minute". */
	public static String duration(long ticks) {
		long seconds = Math.max(0L, ticks) / 20L;
		if (seconds < 60L) {
			return "less than a minute";
		}
		long minutes = Math.round(seconds / 60.0);
		return "about " + minutes + (minutes == 1L ? " minute" : " minutes");
	}

	public static String weather(boolean raining, boolean thundering, String dimensionPath) {
		if ("the_nether".equals(dimensionPath) || "the_end".equals(dimensionPath)) {
			return "There is no weather in " + dimensionName(dimensionPath) + ".";
		}
		if (thundering) {
			return "There's a thunderstorm.";
		}
		if (raining) {
			return "It's raining (or snowing, where it's cold).";
		}
		return "The sky is clear.";
	}

	public static String blockBelow(String blockPath) {
		if (blockPath == null || blockPath.isBlank() || blockPath.equals("air")) {
			return "There's nothing solid right under you.";
		}
		return "You're standing on " + words(blockPath) + ".";
	}

	public static String spawn(int playerX, int playerZ, int spawnX, int spawnZ, String dimensionPath, boolean shareCoordinates) {
		if (!"overworld".equals(dimensionPath)) {
			return shareCoordinates
				? "World spawn is in the Overworld, at " + Directions.coordinates(spawnX, spawnZ) + "."
				: "World spawn is in the Overworld.";
		}
		String where = Directions.describe(spawnX - playerX, 0, spawnZ - playerZ);
		return "World spawn is " + where + coordinateSuffix(spawnX, spawnZ, shareCoordinates) + ".";
	}

	public static String structureFound(
		StructureCatalog.Entry structure, int playerX, int playerZ, int foundX, int foundZ, boolean shareCoordinates
	) {
		String where = Directions.describe(foundX - playerX, 0, foundZ - playerZ);
		return "The nearest " + bare(structure.label()) + " is " + where + coordinateSuffix(foundX, foundZ, shareCoordinates) + ".";
	}

	public static String structureNotFound(StructureCatalog.Entry structure, int radiusBlocks) {
		return "I couldn't find " + structure.label() + " within " + radiusBlocks + " blocks of here.";
	}

	public static String structureWrongDimension(StructureCatalog.Entry structure, String currentDimensionPath) {
		return capitalise(structure.label()) + " is only found in " + dimensionName(structure.dimension())
			+ ", and we're in " + dimensionName(currentDimensionPath) + ".";
	}

	public static String structureUnknown(String spoken) {
		return "I don't know how to look for that. I can find villages, strongholds, temples, mansions, monuments and similar places.";
	}

	/**
	 * Reports an ore scan. {@code only} is the ore that was asked about, or
	 * null when the player asked for ores in general.
	 */
	public static String ores(
		List<TerrainScanner.OreFind> finds, int cx, int cy, int cz, int radius, OreCatalog.Entry only, boolean shareCoordinates
	) {
		if (finds == null || finds.isEmpty()) {
			String what = only == null ? "any ore" : "any " + only.label();
			return "I don't sense " + what + " within " + radius + " blocks.";
		}

		if (only != null || finds.size() == 1) {
			TerrainScanner.OreFind find = finds.get(0);
			TerrainScanner.Pos pos = find.nearest();
			String where = Directions.describe(pos.x() - cx, pos.y() - cy, pos.z() - cz);
			return "I sense " + find.ore().label() + " " + where
				+ coordinateSuffix(pos.x(), pos.y(), pos.z(), shareCoordinates)
				+ ". " + blocksInRange(find.blocks(), radius);
		}

		List<String> parts = new ArrayList<>();
		for (TerrainScanner.OreFind find : finds) {
			if (parts.size() >= 4) {
				break;
			}
			TerrainScanner.Pos pos = find.nearest();
			parts.add(find.ore().label() + " " + Directions.describe(pos.x() - cx, pos.y() - cy, pos.z() - cz)
				+ coordinateSuffix(pos.x(), pos.y(), pos.z(), shareCoordinates));
		}
		return "Nearest ores: " + String.join("; ", parts) + ".";
	}

	public static String cave(Optional<TerrainScanner.CaveFind> find, int cx, int cy, int cz, int radius, boolean shareCoordinates) {
		if (find == null || find.isEmpty()) {
			return "I don't sense a cave within " + radius + " blocks.";
		}
		TerrainScanner.Pos pos = find.get().nearest();
		String where = Directions.describe(pos.x() - cx, pos.y() - cy, pos.z() - cz);
		String size = find.get().sizeCapped() ? "a large open space" : "an open space of about " + find.get().size() + " blocks";
		return "There's a cave " + where + coordinateSuffix(pos.x(), pos.y(), pos.z(), shareCoordinates) + ": " + size + ".";
	}

	public static String surroundings(String biomePath, String timeSentence, String weatherSentence, int hostileCount) {
		StringBuilder builder = new StringBuilder();
		if (biomePath != null && !biomePath.isBlank()) {
			String name = words(biomePath);
			builder.append("We're in ").append(article(name)).append(' ').append(name).append(" biome. ");
		}
		if (timeSentence != null && !timeSentence.isBlank()) {
			builder.append(timeSentence).append(' ');
		}
		if (weatherSentence != null && !weatherSentence.isBlank()) {
			builder.append(weatherSentence).append(' ');
		}
		if (hostileCount <= 0) {
			builder.append("I don't sense anything hostile close by.");
		} else {
			builder.append("I sense ").append(hostileCount).append(hostileCount == 1 ? " hostile mob" : " hostile mobs").append(" close by.");
		}
		return builder.toString().trim();
	}

	/**
	 * The line of checked facts handed to the model with every message, so it
	 * can talk about where the player is without inventing anything.
	 */
	public static String facts(
		int x, int y, int z, String dimensionPath, String biomePath, long clockTicks, boolean raining, boolean thundering,
		boolean shareCoordinates
	) {
		StringBuilder builder = new StringBuilder();
		builder.append("You and the player are in ").append(dimensionName(dimensionPath));
		if (shareCoordinates) {
			builder.append(" at ").append(Directions.coordinates(x, y, z));
		}
		if (biomePath != null && !biomePath.isBlank()) {
			builder.append(", biome ").append(words(biomePath));
		}
		builder.append('.');
		if (!"the_nether".equals(dimensionPath) && !"the_end".equals(dimensionPath)) {
			builder.append(" It is ").append(phase(clockTicks)).append(" (").append(clock(clockTicks)).append(')');
			builder.append(thundering ? ", thunderstorm." : raining ? ", raining." : ", clear sky.");
		}
		return builder.toString();
	}

	private static String blocksInRange(int blocks, int radius) {
		return blocks == 1
			? "That's the only block of it within " + radius + " blocks."
			: "There are " + blocks + " blocks of it within " + radius + " blocks.";
	}

	private static String coordinateSuffix(int x, int z, boolean shareCoordinates) {
		return shareCoordinates ? ", around " + Directions.coordinates(x, z) : "";
	}

	private static String coordinateSuffix(int x, int y, int z, boolean shareCoordinates) {
		return shareCoordinates ? ", at " + Directions.coordinates(x, y, z) : "";
	}

	/** Drops a leading article: "a village" becomes "village". */
	private static String bare(String label) {
		return label.replaceFirst("^(?:a|an|the)\\s+", "");
	}

	private static String article(String name) {
		return !name.isEmpty() && "aeiou".indexOf(name.charAt(0)) >= 0 ? "an" : "a";
	}

	private static String capitalise(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
