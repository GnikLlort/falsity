package com.falsity.actions.items;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.actions.ConfirmationManager;
import com.falsity.config.FalsityConfig;
import com.falsity.config.ItemPermissionMode;
import com.falsity.entity.FalsityEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Creates an item stack and hands it to the player.
 *
 * <p>Security lives here, not in the AI: the request is checked against the
 * configured permission mode, the quantity limit and a per-player cooldown
 * before anything is created, and the item is resolved against the real
 * Minecraft registry so a made-up name can never produce a made-up item. A
 * request that would overflow the inventory is split and reported honestly;
 * nothing is silently dropped or substituted.
 */
public final class GiveItemHandler implements ActionHandler {
	/** How long a confirmation stays valid, in milliseconds. */
	private static final long CONFIRMATION_TTL_MILLIS = 120_000L;

	private final Map<UUID, Long> lastGivenMillis = new HashMap<>();

	@Override
	public ActionType type() {
		return ActionType.GIVE_ITEM;
	}

	@Override
	public synchronized ActionResult handle(ActionContext context, ActionRequest request) {
		FalsityConfig config = context.config();
		ServerPlayer player = context.player();

		if (!config.items.enabled) {
			return ActionResult.failure("I'm not allowed to conjure items right now.");
		}

		if (!mayUse(player, context.falsity(), config)) {
			return ActionResult.failure(permissionMessage(config.items.permissionMode));
		}

		if (!cooldownElapsed(player.getUUID(), config)) {
			return ActionResult.failure("I just gave you something; give me a moment.");
		}

		String requested = request.arg("item", "");
		int count = request.argInt("count", 1);
		if (count < 1) {
			return ActionResult.failure("How many would you like?");
		}
		if (count > config.items.maxPerRequest) {
			return ActionResult.failure(
				"That's more than I'm allowed to make at once (" + config.items.maxPerRequest + ")."
			);
		}

		Optional<Item> resolved = ItemAssistance.resolve(requested);
		if (resolved.isEmpty()) {
			return ActionResult.failure("I don't know any item called \"" + requested + "\".");
		}

		if (count > config.items.confirmAboveCount) {
			ConfirmationManager confirmations = FalsityMod.confirmations();
			if (confirmations == null) {
				return ActionResult.failure("I'm not sure I should do that.");
			}
			String question = "That's " + count + " " + requested + ". Should I?";
			confirmations.request(player.getUUID(), question, () -> executeGive(context, resolved.get(), count, requested), CONFIRMATION_TTL_MILLIS);
			return ActionResult.pending(question);
		}

		return executeGive(context, resolved.get(), count, requested);
	}

	/** Runs the actual hand-over, marking the cooldown as used. */
	private ActionResult executeGive(ActionContext context, Item item, int count, String displayName) {
		Player player = context.player();
		FalsityConfig config = context.config();
		this.lastGivenMillis.put(player.getUUID(), System.currentTimeMillis());

		int maxStack = Math.max(1, new ItemStack(item, 1).getMaxStackSize());
		int given = 0;
		int leftover = 0;

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
				leftover = chunk - placed;
				break;
			}
		}

		if (given == 0) {
			return ActionResult.failure("Your inventory is full; I couldn't give you any.");
		}
		if (given < count) {
			return ActionResult.ok(
				"I could only fit " + given + " of the " + count + " " + displayName
					+ ". Your inventory is full (" + leftover + " left over)."
			);
		}
		return ActionResult.ok("Here you go: " + given + " " + displayName + ".");
	}

	private synchronized boolean cooldownElapsed(UUID player, FalsityConfig config) {
		long now = System.currentTimeMillis();
		Long last = this.lastGivenMillis.get(player);
		if (last != null && now - last < config.items.cooldownSeconds * 1000L) {
			return false;
		}
		return true;
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
