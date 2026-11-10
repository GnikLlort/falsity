package com.falsity.world;

/**
 * Compass directions and distances between two points, for telling a player
 * where something is.
 *
 * <p>In Minecraft north is negative Z and east is positive X.
 */
public final class Directions {
	private static final String[] NAMES = {
		"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"
	};

	private Directions() {
	}

	/**
	 * The compass direction of a horizontal offset, in eight steps. An offset
	 * of zero has no direction and returns an empty string.
	 */
	public static String compass(double dx, double dz) {
		if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) {
			return "";
		}
		// atan2 of (east, north): 0 is north, a quarter turn clockwise is east.
		double degrees = Math.toDegrees(Math.atan2(dx, -dz));
		if (degrees < 0) {
			degrees += 360.0;
		}
		int index = (int) Math.round(degrees / 45.0) % 8;
		return NAMES[index];
	}

	/** Straight-line horizontal distance, rounded to whole blocks. */
	public static int horizontalDistance(double dx, double dz) {
		return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
	}

	/** Straight-line distance in three dimensions, rounded to whole blocks. */
	public static int distance(double dx, double dy, double dz) {
		return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
	}

	/**
	 * "about 340 blocks to the north-east", "right here", "1 block to the
	 * west". Vertical offsets of three blocks or more are mentioned too.
	 */
	public static String describe(double dx, double dy, double dz) {
		int flat = horizontalDistance(dx, dz);
		String compass = compass(dx, dz);
		StringBuilder builder = new StringBuilder();

		if (flat <= 1 || compass.isEmpty()) {
			builder.append("right here");
		} else {
			builder.append("about ").append(flat).append(flat == 1 ? " block" : " blocks").append(" to the ").append(compass);
		}

		long vertical = Math.round(dy);
		if (Math.abs(vertical) >= 3) {
			builder.append(flat <= 1 ? ", " : " and ")
				.append(Math.abs(vertical)).append(" blocks ").append(vertical > 0 ? "up" : "down");
		}
		return builder.toString();
	}

	/** "x=120, y=64, z=-30". */
	public static String coordinates(int x, int y, int z) {
		return "x=" + x + ", y=" + y + ", z=" + z;
	}

	/** "x=120, z=-30", for places whose height is not known. */
	public static String coordinates(int x, int z) {
		return "x=" + x + ", z=" + z;
	}
}
