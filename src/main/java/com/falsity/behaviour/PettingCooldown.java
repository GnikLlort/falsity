package com.falsity.behaviour;

/**
 * Edge guard for server interaction packets. Minecraft may send several use
 * packets while a player holds the interaction key; only the first packet in a
 * short window is a genuine pet.
 */
public final class PettingCooldown {
	private int ticksRemaining;

	/** Accepts one interaction and starts the cooldown, or rejects a repeat. */
	public boolean tryPet(int cooldownTicks) {
		if (this.ticksRemaining > 0) {
			return false;
		}
		this.ticksRemaining = Math.max(1, cooldownTicks);
		return true;
	}

	/** Advances the server-side cooldown by one tick. */
	public void tick() {
		if (this.ticksRemaining > 0) {
			this.ticksRemaining--;
		}
	}

	public int ticksRemaining() {
		return this.ticksRemaining;
	}
}
