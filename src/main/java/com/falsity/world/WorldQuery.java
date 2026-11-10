package com.falsity.world;

/**
 * A question about the world, in a form the server can answer by looking.
 *
 * @param kind    what is being asked
 * @param subject what it is about: a structure key for {@link Kind#STRUCTURE},
 *                an ore name for {@link Kind#ORE}, otherwise empty
 * @param spoken  how the player said it, for use in the reply
 */
public record WorldQuery(Kind kind, String subject, String spoken) {
	/** The kinds of question Falsity can answer from the game itself. */
	public enum Kind {
		/** "Where am I?", "what are my coordinates?" */
		POSITION,
		/** "What biome is this?" */
		BIOME,
		/** "Where is the nearest village?" */
		STRUCTURE,
		/** "Any diamonds nearby?" */
		ORE,
		/** "Is there a cave near here?" */
		CAVE,
		/** "What dimension is this?" */
		DIMENSION,
		/** "What time is it?", "is it night?" */
		TIME,
		/** "Is it raining?" */
		WEATHER,
		/** "What are you standing on?", "what block is that?" */
		BLOCK_BELOW,
		/** "Where is spawn?" */
		SPAWN,
		/** "What do you see?", "what's around us?" */
		SURROUNDINGS
	}

	public WorldQuery {
		subject = subject == null ? "" : subject;
		spoken = spoken == null ? "" : spoken;
	}

	public static WorldQuery of(Kind kind) {
		return new WorldQuery(kind, "", "");
	}

	/**
	 * A compact text form ("position", "structure:village", "ore:diamond")
	 * that {@link WorldQueryRecognizer#fromActionQuery} reads back. This is
	 * what travels in a {@code world_query} action.
	 */
	public String toActionQuery() {
		return switch (this.kind) {
			case POSITION -> "position";
			case BIOME -> "biome";
			case STRUCTURE -> "structure:" + this.subject;
			case ORE -> this.subject.isEmpty() ? "ores" : "ore:" + this.subject;
			case CAVE -> "cave";
			case DIMENSION -> "dimension";
			case TIME -> "time";
			case WEATHER -> "weather";
			case BLOCK_BELOW -> "block below";
			case SPAWN -> "spawn";
			case SURROUNDINGS -> "surroundings";
		};
	}
}
