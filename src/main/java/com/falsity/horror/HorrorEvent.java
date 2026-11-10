package com.falsity.horror;

/**
 * A significant event in Falsity's relationship with a player.
 *
 * <p>Events are the raw material horror progression is built from: first
 * bonding, long absences, stage changes. They are stored as short notes in
 * the player's memory so Falsity can later refer to them ("You were gone for
 * three days. I counted.").
 */
public record HorrorEvent(String type, long epochMillis, String description) {
	/** The moment Falsity bonded with this player. */
	public static HorrorEvent bonded(long nowEpochMillis) {
		return new HorrorEvent("bonded", nowEpochMillis, "Falsity chose this player.");
	}

	/** The player returned after a long absence. */
	public static HorrorEvent returned(long nowEpochMillis, long daysAway) {
		return new HorrorEvent("returned", nowEpochMillis, "Player returned after " + daysAway + " days away.");
	}

	/** The horror stage advanced. */
	public static HorrorEvent stageChanged(long nowEpochMillis, HorrorStage stage) {
		return new HorrorEvent("stage", nowEpochMillis, "Falsity became " + stage.displayName() + ".");
	}
}
