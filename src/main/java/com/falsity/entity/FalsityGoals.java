package com.falsity.entity;

import com.falsity.behaviour.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Falsity's goal priorities, in one place.
 *
 * <p>Lower numbers win. Following its owner comes first, then idle wandering,
 * then watching whoever is nearby. Everything except following is available to
 * a Falsity that has not bonded yet, which is what makes it read as a curious
 * animal before it reads as a companion.
 */
public final class FalsityGoals {
	private FalsityGoals() {
	}

	/**
	 * Adds every goal, in priority order. The selector is passed in because it is
	 * protected on {@code Mob}: only the entity itself may touch it.
	 */
	public static void addGoals(FalsityEntity falsity, GoalSelector selector) {
		selector.addGoal(0, new FollowOwnerGoal(falsity));
		selector.addGoal(1, new RandomStrollGoal(falsity, 0.7));
		selector.addGoal(2, new LookAtPlayerGoal(falsity, Player.class, 6.0F));
		selector.addGoal(3, new RandomLookAroundGoal(falsity));
	}
}
