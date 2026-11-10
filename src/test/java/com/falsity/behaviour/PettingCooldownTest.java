package com.falsity.behaviour;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PettingCooldownTest {
	@Test
	void oneHeldInteractionProducesOneReaction() {
		PettingCooldown guard = new PettingCooldown();
		assertTrue(guard.tryPet(4));
		assertFalse(guard.tryPet(4));
		guard.tick();
		assertFalse(guard.tryPet(4));
	}

	@Test
	void aNewInteractionIsAcceptedAfterCooldown() {
		PettingCooldown guard = new PettingCooldown();
		assertTrue(guard.tryPet(2));
		guard.tick();
		guard.tick();
		assertTrue(guard.tryPet(2));
	}
}
