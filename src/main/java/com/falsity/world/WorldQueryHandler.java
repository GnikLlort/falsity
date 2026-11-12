package com.falsity.world;

import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionHandler;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.config.FalsityConfig;
import com.falsity.config.ItemPermissionMode;
import com.falsity.entity.FalsityEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Answers questions about the world from the game itself.
 *
 * <p>Whether the question came from the local recogniser or from a model's
 * {@code world_query} tag, the answer is produced here, by the server, from
 * what it can read. A model only chooses what to ask.
 *
 * <p>Questions about the here and now (position, biome, time, weather) are
 * open to anyone Falsity talks to. Searches (structures, ores, caves) reveal
 * things a survival player could not otherwise see, so they follow
 * {@code world.locatePermission} and a short cooldown.
 */
public final class WorldQueryHandler implements ActionHandler {
	private final Map<UUID, Long> lastSearchMillis = new HashMap<>();

	@Override
	public ActionType type() {
		return ActionType.WORLD_QUERY;
	}

	@Override
	public synchronized ActionResult handle(ActionContext context, ActionRequest request) {
		FalsityConfig config = context.config();
		ServerPlayer player = context.player();

		if (!config.world.enabled) {
			return ActionResult.failure("I'm not allowed to look around the world for you right now.");
		}

		Optional<WorldQuery> parsed = WorldQueryRecognizer.fromActionQuery(request.arg("query", ""));
		if (parsed.isEmpty()) {
			return ActionResult.failure(
				"I don't know how to look that up. I can tell you where we are, the biome, the time, and look for villages, structures, ores and caves.");
		}
		WorldQuery query = parsed.get();

		if (WorldService.isSearch(query.kind())) {
			if (!maySearch(player, context.falsity(), config)) {
				return ActionResult.failure(permissionMessage(config.world.locatePermission));
			}
			long now = System.currentTimeMillis();
			Long last = this.lastSearchMillis.get(player.getUUID());
			long cooldownMillis = config.world.locateCooldownSeconds * 1000L;
			if (last != null && now - last < cooldownMillis) {
				long seconds = (cooldownMillis - (now - last) + 999L) / 1000L;
				return ActionResult.failure("I just looked. Give me " + seconds + (seconds == 1L ? " more second." : " more seconds."));
			}
			this.lastSearchMillis.put(player.getUUID(), now);
		}

		try {
			return ActionResult.ok(WorldService.answer(context.level(), player, context.falsity(), query, config));
		} catch (RuntimeException exception) {
			com.falsity.FalsityMod.LOGGER.warn("Falsity could not answer a world question ({}): {}", query.kind(), exception.toString());
			return ActionResult.failure("I tried to look, but something went wrong. I don't want to guess.");
		}
	}

	private static boolean maySearch(ServerPlayer player, FalsityEntity falsity, FalsityConfig config) {
		return switch (config.world.locatePermission) {
			case NONE -> false;
			case CREATIVE -> player.isCreative();
			case OPERATOR -> player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
			case OWNER -> falsity != null && falsity.isBondedTo(player.getUUID());
			case UNRESTRICTED -> true;
		};
	}

	private static String permissionMessage(ItemPermissionMode mode) {
		return switch (mode) {
			case NONE -> "I'm not allowed to search the world for things.";
			case CREATIVE -> "I only search the world for creative players.";
			case OPERATOR -> "I only search the world for operators.";
			case OWNER -> "I only search the world for the one I'm bonded to.";
			case UNRESTRICTED -> "I'm not allowed to search the world right now.";
		};
	}
}
