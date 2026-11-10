package com.falsity.behaviour;

import com.falsity.config.FalsityConfig;

/**
 * Pure decisions about how close Falsity wants to be to its owner.
 *
 * <p>Keeping this arithmetic out of the entity means it can be unit tested
 * without a running game, and it keeps the goal classes free of threshold
 * literals that would otherwise be scattered through the AI code.
 */
public final class CompanionBehaviour {
	/** Once following, Falsity keeps closing in until it is this share of the start distance away. */
	private static final double CLOSE_ENOUGH_FACTOR = 0.6;

	private CompanionBehaviour() {
	}

	/** Whether Falsity should start walking after its owner. */
	public static boolean shouldFollow(double distanceToOwner, FalsityConfig.Companion companion) {
		if (!companion.followOwner) {
			return false;
		}

		return distanceToOwner > companion.followStartDistance;
	}

	/**
	 * Whether Falsity should keep following once it has started. Hysteresis stops
	 * it from stuttering in and out of the goal at the threshold.
	 */
	public static boolean shouldKeepFollowing(double distanceToOwner, FalsityConfig.Companion companion) {
		if (!companion.followOwner) {
			return false;
		}

		return distanceToOwner > companion.followStartDistance * CLOSE_ENOUGH_FACTOR;
	}

	/** Whether the owner is so far away that walking will not do. */
	public static boolean shouldTeleport(double distanceToOwner, FalsityConfig.Companion companion) {
		return distanceToOwner > companion.teleportDistance;
	}

	/** Walking speed used while following, clamped to something a mob can use. */
	public static double followSpeed(FalsityConfig.Companion companion) {
		return Math.max(0.1, Math.min(2.0, companion.followSpeed));
	}
}
