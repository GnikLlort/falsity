package com.falsity.entity;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Falsity's default attributes, kept apart from the entity so registration and
 * later horror stages can read them without constructing a mob.
 */
public final class FalsityAttributes {
	private FalsityAttributes() {
	}

	public static AttributeSupplier.Builder create() {
		return PathfinderMob.createMobAttributes()
			// Tough enough to survive being hit once and to make hurting it a decision.
			.add(Attributes.MAX_HEALTH, 20.0)
			// Slower than a player walking, so it can be left behind on purpose.
			.add(Attributes.MOVEMENT_SPEED, 0.24)
			.add(Attributes.FOLLOW_RANGE, 24.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
	}
}
