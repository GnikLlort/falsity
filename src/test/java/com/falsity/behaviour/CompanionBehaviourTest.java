package com.falsity.behaviour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.config.FalsityConfig;
import org.junit.jupiter.api.Test;

/** The follow/teleport rules, checked without a game running. */
class CompanionBehaviourTest {
	private static FalsityConfig.Companion companion() {
		FalsityConfig.Companion companion = new FalsityConfig.Companion();
		companion.followStartDistance = 6.0;
		companion.teleportDistance = 24.0;
		companion.followSpeed = 1.1;
		return companion;
	}

	@Test
	void doesNotFollowWhileCloseEnough() {
		assertFalse(CompanionBehaviour.shouldFollow(2.0, companion()));
		assertFalse(CompanionBehaviour.shouldFollow(6.0, companion()));
	}

	@Test
	void followsWhenTheOwnerGetsAway() {
		assertTrue(CompanionBehaviour.shouldFollow(6.5, companion()));
	}

	@Test
	void keepsFollowingUntilItIsNearlyHome() {
		FalsityConfig.Companion companion = companion();

		// Hysteresis: inside the start distance but outside the stop distance.
		assertTrue(CompanionBehaviour.shouldKeepFollowing(4.0, companion));
		assertFalse(CompanionBehaviour.shouldKeepFollowing(3.0, companion));
	}

	@Test
	void disabledFollowingSilencesBothChecks() {
		FalsityConfig.Companion companion = companion();
		companion.followOwner = false;

		assertFalse(CompanionBehaviour.shouldFollow(30.0, companion));
		assertFalse(CompanionBehaviour.shouldKeepFollowing(30.0, companion));
	}

	@Test
	void teleportsOnlyBeyondTheConfiguredDistance() {
		FalsityConfig.Companion companion = companion();

		assertFalse(CompanionBehaviour.shouldTeleport(24.0, companion));
		assertTrue(CompanionBehaviour.shouldTeleport(24.5, companion));
	}

	@Test
	void followSpeedIsClampedToSomethingAbleToWalk() {
		FalsityConfig.Companion companion = companion();
		companion.followSpeed = 99.0;
		assertEquals(2.0, CompanionBehaviour.followSpeed(companion));

		companion.followSpeed = 0.0;
		assertEquals(0.1, CompanionBehaviour.followSpeed(companion));
	}
}
