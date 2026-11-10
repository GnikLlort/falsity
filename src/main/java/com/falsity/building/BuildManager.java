package com.falsity.building;

import com.falsity.FalsityMod;
import com.falsity.ai.ConversationManager;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Runs approved builds on the server thread, gradually, and tracks their state.
 *
 * <p>Only one build runs per player at a time. Each tick the manager places a
 * small number of blocks from the plan, consuming materials as it goes, and
 * reports progress at a configured cadence. A build that cannot place a block
 * is reported as failed rather than silently skipped, and a cancelled build
 * stops at the next tick boundary without leaving the world half-changed.
 */
public final class BuildManager {
	private final Map<UUID, ActiveBuild> active = new HashMap<>();

	/** One running build. */
	private static final class ActiveBuild {
		private final ServerPlayer player;
		private final FalsityEntity falsity;
		private final BuildPlan plan;
		private final FalsityConfig config;
		private final ServerLevel level;

		private int index;
		private int progressTick;
		private boolean cancelled;
		private boolean failed;

		ActiveBuild(ServerPlayer player, FalsityEntity falsity, BuildPlan plan, FalsityConfig config, ServerLevel level) {
			this.player = player;
			this.falsity = falsity;
			this.plan = plan;
			this.config = config;
			this.level = level;
		}

		int placed() {
			return this.index;
		}

		int total() {
			return this.plan.totalBlocks();
		}
	}

	public synchronized boolean isActive(UUID player) {
		return this.active.containsKey(player);
	}

	/** The plan currently being built for this player, if any. */
	public synchronized BuildPlan activePlan(UUID player) {
		ActiveBuild build = this.active.get(player);
		return build == null ? null : build.plan;
	}

	/** Starts a build. Returns a short line for the player. */
	public synchronized String start(ServerPlayer player, FalsityEntity falsity, BuildPlan plan) {
		if (this.active.containsKey(player.getUUID())) {
			return "I'm already building something for you.";
		}
		if (!plan.placements().isEmpty() && !FalsityMod.config().building.enabled) {
			return "I'm not allowed to build right now.";
		}

		this.active.put(player.getUUID(), new ActiveBuild(player, falsity, plan, FalsityMod.config(), player.level()));
		return BuildProgressTracker.started(plan.structure());
	}

	/** Cancels the player's build, if any. Returns a short line. */
	public synchronized String cancel(UUID player) {
		ActiveBuild build = this.active.get(player);
		if (build == null) {
			return "I wasn't building anything.";
		}
		build.cancelled = true;
		this.active.remove(player);
		return BuildProgressTracker.cancelled(build.plan.structure());
	}

	/** Places a batch of blocks for the player's build. Called from the entity tick. */
	public synchronized void tick(UUID player) {
		ActiveBuild build = this.active.get(player);
		if (build == null || build.cancelled || build.failed) {
			return;
		}
		if (build.player.isRemoved() || !build.player.isAlive()) {
			this.active.remove(player);
			return;
		}

		int perTick = Math.max(1, FalsityMod.config().building.blockPlacementsPerTick);
		boolean inventoryBased = FalsityMod.config().building.inventoryBased && !FalsityMod.config().building.creativeGeneration;

		for (int i = 0; i < perTick && build.index < build.total(); i++) {
			BlockPlacement placement = build.plan.placements().get(build.index);
			BlockState state = placement.state();

			if (inventoryBased) {
				Item item = BuildMaterials.itemFor(state.getBlock());
				if (item != null && item != Items.AIR && !BuildMaterials.consume(build.player, item)) {
					build.failed = true;
					break;
				}
			}

			if (!build.level.setBlockAndUpdate(placement.pos(), state)) {
				build.failed = true;
				break;
			}
			build.index++;
		}

		if (build.cancelled) {
			this.active.remove(player);
			return;
		}

		if (build.failed) {
			this.active.remove(player);
			ConversationManager.say(build.player, build.falsity, BuildProgressTracker.failed(build.plan.structure(), build.index, build.total()));
			return;
		}

		if (build.index >= build.total()) {
			this.active.remove(player);
			ConversationManager.say(build.player, build.falsity, BuildProgressTracker.completed(build.plan.structure()));
			return;
		}

		int progressTick = ++build.progressTick;
		if (progressTick % Math.max(1, FalsityMod.config().building.progressUpdateTicks) == 0) {
			ConversationManager.say(
				build.player, build.falsity,
				"Building your " + build.plan.structure() + "... " + BuildProgressTracker.progress(build.index, build.total()) + " blocks."
			);
		}
	}

	/** Stops every build. Called on server shutdown. */
	public synchronized void stopAll() {
		this.active.clear();
	}
}
