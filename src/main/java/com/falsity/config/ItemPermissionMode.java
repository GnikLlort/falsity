package com.falsity.config;

/**
 * Who may ask Falsity to create an item.
 *
 * <p>This is a server-side security boundary, not a suggestion: the model's
 * request is checked against the mode before any item is created, so a
 * persuasive prompt can never grant itself permission. The modes are ordered
 * from strictest to most permissive.
 */
public enum ItemPermissionMode {
	/** Item assistance is off entirely. */
	NONE,
	/** Only players in creative mode may ask. */
	CREATIVE,
	/** Only operators may ask. */
	OPERATOR,
	/** Only the player Falsity is bonded to may ask. */
	OWNER,
	/** Any player may ask (server-authorised, use with care). */
	UNRESTRICTED
}
