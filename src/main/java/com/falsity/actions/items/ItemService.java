package com.falsity.actions.items;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ConfirmationManager;
import com.falsity.config.FalsityConfig;
import com.falsity.config.ItemPermissionMode;
import com.falsity.entity.FalsityEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Checks an item request and hands the items over.
 *
 * <p>Security lives here, not in the AI. Before anything is created the
 * request is checked against the permission mode, the per-player cooldown and
 * the configured limits, and every name is resolved against the real item
 * registry, so a made-up name can never produce a made-up item. One request may
 * ask for several things ("a crafting table and 64 sticks"); the whole request
 * is validated first, and a request that breaks a limit creates nothing.
 *
 * <p>What is said afterwards is what happened: if only part of an order fitted
 * in the inventory, the reply says how much.
 */
public final class ItemService {
	private static final long CONFIRMATION_TTL_MILLIS = 120_000L;

	private final Map<UUID, Long> lastGivenMillis = new HashMap<>();

	/** Validates the request and delivers it, or explains why not. */
	public synchronized ActionResult give(ActionContext context, List<ItemRequestParser.Entry> entries) {
		FalsityConfig config = context.config();
		ServerPlayer player = context.player();

		if (!config.items.enabled || !config.autonomy.resourceAssistance) {
			return ActionResult.failure("I'm not allowed to conjure items right now.");
		}
		if (!mayUse(player, context.falsity(), config)) {
			return ActionResult.failure(permissionMessage(config.items.permissionMode));
		}
		if (!cooldownElapsed(player.getUUID(), config)) {
			return ActionResult.failure("I just gave you something; give me a moment.");
		}
		if (entries == null || entries.isEmpty()) {
			return ActionResult.failure("What would you like? Tell me the item and how many.");
		}
		for (ItemRequestParser.Entry entry : entries) {
			if (entry.count() < 1) {
				return ActionResult.failure("How many would you like?");
			}
		}

		ItemPlanner.Limits limits = new ItemPlanner.Limits(
			config.items.maxPerRequest,
			config.items.maxTypesPerRequest,
			config.items.confirmAboveCount,
			// Technical and creative-only items stay with operators and creative players.
			player.isCreative() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
		);
		ItemPlanner.Plan plan = ItemPlanner.plan(entries, ItemAssistance.CATALOG, limits);

		if (plan.status() == ItemPlanner.Status.REJECTED) {
			return ActionResult.failure(plan.rejection());
		}
		if (plan.status() == ItemPlanner.Status.NOTHING_KNOWN) {
			String why = ItemPlanner.unresolvedSentence(plan.unresolved());
			return ActionResult.failure(why.isEmpty() ? "I don't know what that is. Can you say it another way?" : why);
		}

		if (plan.needsConfirmation()) {
			ConfirmationManager confirmations = FalsityMod.confirmations();
			if (confirmations == null) {
				return ActionResult.failure("I'm not sure I should do that.");
			}
			String question = "That's " + plan.describe() + " (" + plan.totalItems() + " items). Should I?";
			confirmations.request(player.getUUID(), question, () -> deliver(context, plan), CONFIRMATION_TTL_MILLIS);
			return ActionResult.pending(question);
		}

		return deliver(context, plan);
	}

	private synchronized ActionResult deliver(ActionContext context, ItemPlanner.Plan plan) {
		ServerPlayer player = context.player();
		if (player.isRemoved() || !player.isAlive()) {
			return ActionResult.failure("I can't hand you anything right now.");
		}

		List<ItemPlanner.Delivery> deliveries = new ArrayList<>();
		int deliveredTotal = 0;
		for (ItemPlanner.Group group : plan.groups()) {
			int requested = 0;
			int delivered = 0;
			for (ItemPlanner.Line line : group.lines()) {
				requested += line.count();
				Optional<Item> item = ItemAssistance.byId(line.id());
				if (item.isPresent()) {
					delivered += insert(player, item.get(), line.count());
				}
			}
			deliveredTotal += delivered;
			deliveries.add(new ItemPlanner.Delivery(group, requested, delivered));
		}

		if (deliveredTotal > 0) {
			this.lastGivenMillis.put(player.getUUID(), System.currentTimeMillis());
		}

		String message = ItemPlanner.deliverySentence(deliveries);
		String unresolved = ItemPlanner.unresolvedSentence(plan.unresolved());
		if (!unresolved.isEmpty()) {
			message = message.isEmpty() ? unresolved : message + " " + unresolved;
		}
		return deliveredTotal > 0 ? ActionResult.ok(message) : ActionResult.failure(message);
	}

	/** Puts up to {@code count} of an item into the inventory; returns how many fitted. */
	private static int insert(ServerPlayer player, Item item, int count) {
		int maxStack = Math.max(1, new ItemStack(item, 1).getMaxStackSize());
		int given = 0;
		while (given < count) {
			int chunk = Math.min(count - given, maxStack);
			ItemStack stack = new ItemStack(item, chunk);
			if (!player.getInventory().add(-1, stack)) {
				break;
			}
			int placed = chunk - stack.getCount();
			if (placed <= 0) {
				// Reported success but kept the stack: no room after all.
				break;
			}
			given += placed;
			if (placed < chunk) {
				break;
			}
		}
		return given;
	}

	private boolean cooldownElapsed(UUID player, FalsityConfig config) {
		long now = System.currentTimeMillis();
		Long last = this.lastGivenMillis.get(player);
		return last == null || now - last >= config.items.cooldownSeconds * 1000L;
	}

	private static boolean mayUse(ServerPlayer player, FalsityEntity falsity, FalsityConfig config) {
		return switch (config.items.permissionMode) {
			case NONE -> false;
			case CREATIVE -> player.isCreative();
			// Vanilla's gamemaster level, which is what operator level 2 means.
			case OPERATOR -> player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
			case OWNER -> falsity != null && falsity.isBondedTo(player.getUUID());
			case UNRESTRICTED -> true;
		};
	}

	private static String permissionMessage(ItemPermissionMode mode) {
		return switch (mode) {
			case NONE -> "I'm not allowed to conjure items.";
			case CREATIVE -> "Only creative players can ask me for items.";
			case OPERATOR -> "Only operators can ask me for items.";
			case OWNER -> "Only the one I'm bonded to can ask me for items.";
			case UNRESTRICTED -> "I'm not allowed to conjure items right now.";
		};
	}
}
