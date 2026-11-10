package com.falsity.entity;

import com.falsity.behaviour.FollowOwnerGoal;
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

	public static void registerGoals(FalsityEntity falsity) {
		falsity.goalSelector.addGoal(0, new FollowOwnerGoal(falsity));
		falsity.goalSelector.addGoal(1, new RandomStrollGoal(falsity, 0.7));
		falsity.goalSelector.addGoal(2, new LookAtPlayerGoal(falsity, Player.class, 6.0F));
		falsity.goalSelector.addGoal(3, new RandomLookAroundGoal(falsity));
	}
}
