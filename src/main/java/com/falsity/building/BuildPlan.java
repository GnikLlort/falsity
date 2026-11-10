package com.falsity.building;

import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * An approved set of block placements, with its bounding region and size.
 *
 * <p>A plan is the contract between the planner and the executor. The executor
 * will only ever place the blocks listed here, and the validator has already
 * checked the region, the block count and the materials before the plan is
 * handed over. An AI response can never widen a plan after it is built.
 */
public final class BuildPlan {
	private final List<BlockPlacement> placements;
	private final AABB region;
	private final int width;
	private final int length;
	private final int height;
	private final String structure;
	private final String material;

	public BuildPlan(
		List<BlockPlacement> placements,
		AABB region,
		int width,
		int length,
		int height,
		String structure,
		String material
	) {
		this.placements = List.copyOf(placements);
		this.region = region;
		this.width = width;
		this.length = length;
		this.height = height;
		this.structure = structure;
		this.material = material;
	}

	public List<BlockPlacement> placements() {
		return this.placements;
	}

	public AABB region() {
		return this.region;
	}

	public int width() {
		return this.width;
	}

	public int length() {
		return this.length;
	}

	public int height() {
		return this.height;
	}

	public int totalBlocks() {
		return this.placements.size();
	}

	public String structure() {
		return this.structure;
	}

	public String material() {
		return this.material;
	}

	/** A short description for the confirmation prompt. */
	public String describe() {
		return "a " + this.material + " " + this.structure
			+ " (" + this.width + "x" + this.length + "x" + this.height
			+ ", " + this.totalBlocks() + " blocks)";
	}
}
