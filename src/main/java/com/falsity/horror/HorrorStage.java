package com.falsity.horror;

/**
 * Horror progression stages, from harmless companion to nightmare.
 *
 * <p>This milestone only ever reaches {@link #STRANGE}: the architecture
 * knows about all five stages, but the configured maximum stage is clamped
 * to 1 until a later milestone raises it. Nothing at any stage in this
 * milestone can damage, trap or frighten the player beyond a stare and an
 * odd sentence.
 *
 * <p>The declaration order is the progression order, so the built-in
 * {@link Enum#ordinal()} is the stage number: Innocence is 0, Nightmare is 4.
 */
public enum HorrorStage {
	INNOCENCE("Innocence"),
	STRANGE("Strange"),
	SUSPICION("Suspicion"),
	FEAR("Fear"),
	NIGHTMARE("Nightmare");

	private final String displayName;

	HorrorStage(String displayName) {
		this.displayName = displayName;
	}

	/** The human-readable stage name. */
	public String displayName() {
		return this.displayName;
	}

	/** Returns the stage for an ordinal, clamped into range. */
	public static HorrorStage fromOrdinal(int ordinal) {
		HorrorStage[] stages = values();
		if (ordinal < 0) {
			return stages[0];
		}
		if (ordinal >= stages.length) {
			return stages[stages.length - 1];
		}
		return stages[ordinal];
	}
}
