package com.falsity.actions;

import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything an action handler needs to act on one player's request.
 *
 * <p>Context is created fresh for each request on the server thread, so a
 * handler never holds a stale reference to a player or entity. Managers are
 * reached through {@code FalsityMod} statics rather than passed here, which
 * keeps the context small and makes handlers easy to unit test with a mock
 * level.
 */
public record ActionContext(ServerPlayer player, FalsityEntity falsity, ServerLevel level, FalsityConfig config) {
}
