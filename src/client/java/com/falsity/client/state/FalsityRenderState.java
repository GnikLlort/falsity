package com.falsity.client.state;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.AnimationState;

/**
 * Everything the renderer needs about one Falsity for one frame.
 *
 * <p>The draw code never touches the entity itself: the renderer copies these
 * values out of it, and the model animates from the copy.
 */
public class FalsityRenderState extends LivingEntityRenderState {
	/** Always running while Falsity exists: breathing, ear sway, tail flick. */
	public final AnimationState idleAnimationState = new AnimationState();
	/** One-shot perk played when Falsity is petted or first bonds. */
	public final AnimationState greetAnimationState = new AnimationState();
	/** Per-entity offset so two Falsities never blink in step. */
	public int blinkPhase;
	/** Whether Falsity has been told to stay. */
	public boolean sitting;
}
