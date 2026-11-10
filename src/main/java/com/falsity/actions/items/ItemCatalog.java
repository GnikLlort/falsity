package com.falsity.actions.items;

/**
 * The items that exist, as far as item resolution is concerned.
 *
 * <p>In the game this is backed by Minecraft's item registry. Keeping it
 * behind an interface lets the whole "what did the player mean" logic be unit
 * tested against a plain list of ids, with no game running.
 */
public interface ItemCatalog {
	/** Whether a vanilla item with this id path (such as {@code oak_log}) exists. */
	boolean exists(String id);

	/** Largest stack of this item. Used to turn "a stack of" into a number. */
	default int maxStackSize(String id) {
		return 64;
	}
}
