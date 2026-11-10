package com.falsity.building;

/**
 * Progress reporting for an in-progress build.
 *
 * <p>Kept separate from the executor so the wording of progress updates can be
 * changed (or localised) without touching the placement logic. Updates are
 * deliberately sparse: the player is told when a build starts, occasionally
 * while it runs, and when it finishes or fails — never once per block.
 */
public final class BuildProgressTracker {
	private BuildProgressTracker() {
	}

	/** A short "N of M" fragment. */
	public static String progress(int placed, int total) {
		return placed + " of " + total;
	}

	/** The line spoken when a build finishes. */
	public static String completed(String structure) {
		return "I've finished your " + structure + ". What do you think?";
	}

	/** The line spoken when a build fails part-way through. */
	public static String failed(String structure, int placed, int total) {
		return "I had trouble finishing your " + structure + "; I stopped after " + placed + " of " + total + " blocks.";
	}

	/** The line spoken when a build is cancelled. */
	public static String cancelled(String structure) {
		return "I've stopped building your " + structure + ".";
	}

	/** The line spoken when a build starts. */
	public static String started(String structure) {
		return "I've started building your " + structure + ".";
	}
}
