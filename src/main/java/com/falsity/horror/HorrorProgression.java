package com.falsity.horror;

/**
 * Pure horror progression rules: tension in, stage out.
 *
 * <p>Tension grows with interactions and time together, scaled by the
 * configured progression speed. A speed of 0 freezes progression entirely.
 * Thresholds are spaced so the first step (Innocence to Strange) takes
 * dozens of interactions: Falsity should be loved long before anything
 * feels wrong.
 */
public final class HorrorProgression {
	/** Tension needed for each stage, indexed by stage ordinal. */
	private static final double[] THRESHOLDS = {0.0, 20.0, 50.0, 100.0, 200.0};

	private HorrorProgression() {
	}

	/**
	 * Computes the stage for a tension value, capped at the configured
	 * maximum. The cap is what keeps this milestone harmless: even with a
	 * huge tension, Falsity stays at or below the allowed stage.
	 */
	public static HorrorStage stageForTension(double tension, int maxStage) {
		int cappedMax = Math.max(0, Math.min(HorrorStage.NIGHTMARE.ordinal(), maxStage));
		int stage = 0;
		for (int i = 0; i < THRESHOLDS.length; i++) {
			if (tension >= THRESHOLDS[i]) {
				stage = i;
			}
		}
		return HorrorStage.fromOrdinal(Math.min(stage, cappedMax));
	}

	/**
	 * Tension gained from one interaction at the given speed. Kept small so
	 * progression is gradual and reversible by config.
	 */
	public static double tensionGainForInteraction(double progressionSpeed) {
		if (progressionSpeed <= 0.0) {
			return 0.0;
		}
		return 1.0 * progressionSpeed;
	}

	/** Whether staring behaviour is allowed at this stage. */
	public static boolean allowsStaring(HorrorStage stage) {
		return stage.ordinal() >= HorrorStage.STRANGE.ordinal();
	}

	/** Whether subtly unsettling lines are allowed at this stage. */
	public static boolean allowsUnsettlingLines(HorrorStage stage) {
		return stage.ordinal() >= HorrorStage.STRANGE.ordinal();
	}
}
