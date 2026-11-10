package com.falsity.actions.items;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import java.util.List;

/**
 * Hands the player one kind of item: {@code give_item} with an item name and a
 * count.
 *
 * <p>The name is read the same way a spoken request is, so a model that puts a
 * whole list in the name ("crafting table and 64 sticks") still gets each item
 * resolved separately instead of one item that does not exist. All checking
 * and the actual hand-over are in {@link ItemService}.
 */
public final class GiveItemHandler implements ActionHandler {
	private final ItemService service;

	public GiveItemHandler() {
		this(new ItemService());
	}

	public GiveItemHandler(ItemService service) {
		this.service = service;
	}

	@Override
	public ActionType type() {
		return ActionType.GIVE_ITEM;
	}

	@Override
	public ActionResult handle(ActionContext context, ActionRequest request) {
		String requested = request.arg("item", "");
		int count = request.argInt("count", 1);
		if (count < 1) {
			return ActionResult.failure("How many would you like?");
		}

		List<ItemRequestParser.Entry> parsed = ItemRequestParser.parse(requested);
		if (parsed.size() == 1 && !parsed.get(0).countStated()) {
			// The usual case: a plain name, with the count given separately.
			parsed = List.of(ItemRequestParser.Entry.of(parsed.get(0).name(), count));
		} else if (parsed.isEmpty() && !requested.isBlank()) {
			parsed = List.of(ItemRequestParser.Entry.of(requested, count));
		}
		return this.service.give(context, parsed);
	}
}
