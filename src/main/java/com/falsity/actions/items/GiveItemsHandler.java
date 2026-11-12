package com.falsity.actions.items;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;

/**
 * Hands the player several kinds of item in one request: {@code give_items}
 * with a plain list such as {@code "1 crafting table, 64 sticks"}.
 *
 * <p>The list is parsed and validated by the server as one request (see
 * {@link ItemService}); nothing is created until every part has been checked.
 */
public final class GiveItemsHandler implements ActionHandler {
	private final ItemService service;

	public GiveItemsHandler(ItemService service) {
		this.service = service;
	}

	@Override
	public ActionType type() {
		return ActionType.GIVE_ITEMS;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		return this.service.give(context, ItemRequestParser.parse(request.arg("items", "")));
	}
}
