package com.falsity.building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One planned block placement: where, and what block.
 *
 * <p>A placement is pure data. It is produced by the planner, validated by the
 * validator and only then handed to the executor, so nothing in the pipeline
 * can reach into the world before the server has approved it.
 */
public record BlockPlacement(BlockPos pos, BlockState state) {
}
