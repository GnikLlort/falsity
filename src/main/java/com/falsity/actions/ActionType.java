package com.falsity.actions;

import java.util.Map;
import java.util.Set;

/**
 * The actions Falsity may request and the server may execute.
 *
 * <p>This enum is the allowlist. A structured action from the AI is only ever
 * executed if its type is declared here, and only with the argument keys that
 * type declares: unknown types and unknown keys are rejected before any game
 * state is touched. The AI can therefore ask for things, but it can never ask
 * for a command, a code path or a block it is not allowed to place.
 */
public enum ActionType {
	/** Nothing to do; dialogue only. */
	NONE,
	/** Walk after the player. */
	FOLLOW,
	/** Stop walking and stay where Falsity is. */
	STOP_FOLLOW,
	/** Come to the player's position. */
	COME,
	/** Sit down. */
	SIT,
	/** Stand up. */
	STAND,
	/** Play the greeting animation. */
	GREET,
	/** Turn to look at a player or a location. */
	LOOK_AT("x", "y", "z"),
	/** Walk to a location. */
	WALK_TO("x", "y", "z"),
	/** Stop and remain at a location. */
	STAY,
	/** Defend the owner from hostile mobs. */
	PROTECT,
	/** Attack nearby hostile mobs. */
	FIGHT,
	/** Stop attacking. */
	STOP_FIGHT,
	/** Report nearby hostile mobs and dangers. */
	REPORT_THREATS,
	/** Create an item stack and hand it to the player. */
	GIVE_ITEM("item", "count"),
	/** Build a structure in the world. */
	BUILD("structure", "material", "width", "length", "height", "x", "y", "z"),
	/** Cancel the active build for the player. */
	STOP_BUILD;

	private final Set<String> allowedArgs;

	ActionType(String... allowedArgs) {
		this.allowedArgs = Set.of(allowedArgs);
	}

	/** Whether the action may carry the given argument key. */
	public boolean allowsArg(String key) {
		return this.allowedArgs.contains(key);
	}

	/** Whether every argument key in the request is allowed for this type. */
	public boolean allowsArgs(Map<String, ?> args) {
		if (args == null) {
			return true;
		}
		return args.keySet().stream().allMatch(this::allowsArg);
	}

	/** Whether this type is a real action rather than the "nothing" placeholder. */
	public boolean isReal() {
		return this != NONE;
	}
}
