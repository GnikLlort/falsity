package com.falsity.personality;

/**
 * Falsity's current mood, derived from its personality and recent events.
 *
 * <p>The mood is never stored: it is computed on demand so it cannot go
 * stale. It only colours dialogue and offline lines; it never changes game
 * rules, and it can never make Falsity hostile.
 */
public enum EmotionalState {
	HAPPY,
	CURIOUS,
	PLAYFUL,
	ATTACHED,
	LONELY,
	WARY,
	FRIGHTENED,
	QUIET;

	/** Derives the dominant mood from a personality profile. */
	public static EmotionalState from(PersonalityProfile profile) {
		if (profile == null) {
			return QUIET;
		}
		if (profile.fear >= 70.0) {
			return FRIGHTENED;
		}
		if (profile.suspicion >= 60.0) {
			return WARY;
		}
		if (profile.loneliness >= 60.0) {
			return LONELY;
		}
		if (profile.attachment >= 75.0) {
			return ATTACHED;
		}
		if (profile.playfulness >= 70.0) {
			return PLAYFUL;
		}
		if (profile.curiosity >= 70.0) {
			return CURIOUS;
		}
		if (profile.affection >= 65.0) {
			return HAPPY;
		}
		return QUIET;
	}

	/** Short phrase for prompts, e.g. "currently curious". */
	public String phrase() {
		return switch (this) {
			case HAPPY -> "currently happy";
			case CURIOUS -> "currently curious";
			case PLAYFUL -> "currently playful";
			case ATTACHED -> "currently clingy and attached";
			case LONELY -> "currently lonely";
			case WARY -> "currently wary";
			case FRIGHTENED -> "currently frightened";
			case QUIET -> "currently quiet";
		};
	}
}
