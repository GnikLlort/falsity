package com.falsity.client.animation;

import com.falsity.client.model.FalsityModelGeometry;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.util.Mth;

/**
 * Falsity's hand-authored animations, using Minecraft's own keyframe system.
 *
 * <p>Deliberately no third-party animation library: vanilla animation
 * definitions are versioned with the game, need no extra dependency and are
 * enough for a creature this size. Procedural motion that depends on what the
 * entity is doing (walking, blinking, looking) lives in the model instead.
 */
public final class FalsityAnimations {
	private static final AnimationChannel.Interpolation SMOOTH = AnimationChannel.Interpolations.CATMULLROM;

	/**
	 * Played constantly: a slow breath that lifts the whole body, ears drifting,
	 * and a tail that cannot keep still. Four seconds is long enough that it reads
	 * as idle rather than as a loop.
	 */
	public static final AnimationDefinition IDLE = AnimationDefinition.Builder.withLength(4.0F)
		.looping()
		.addAnimation(FalsityModelGeometry.BODY, new AnimationChannel(
			AnimationChannel.Targets.POSITION,
			new Keyframe(0.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.0F, KeyframeAnimations.posVec(0.0F, -0.3F, 0.0F), SMOOTH),
			new Keyframe(2.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(3.0F, KeyframeAnimations.posVec(0.0F, -0.3F, 0.0F), SMOOTH),
			new Keyframe(4.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.HEAD, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.0F, KeyframeAnimations.degreeVec(1.0F, 0.0F, 1.5F), SMOOTH),
			new Keyframe(2.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(3.0F, KeyframeAnimations.degreeVec(-1.0F, 0.0F, -1.5F), SMOOTH),
			new Keyframe(4.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.EAR_LEFT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.5F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 5.0F), SMOOTH),
			new Keyframe(3.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, -2.0F), SMOOTH),
			new Keyframe(4.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.EAR_RIGHT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.5F, KeyframeAnimations.degreeVec(0.0F, 0.0F, -2.0F), SMOOTH),
			new Keyframe(3.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 5.0F), SMOOTH),
			new Keyframe(4.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.TAIL, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.0F, KeyframeAnimations.degreeVec(6.0F, 8.0F, 0.0F), SMOOTH),
			new Keyframe(2.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(3.0F, KeyframeAnimations.degreeVec(-6.0F, -8.0F, 0.0F), SMOOTH),
			new Keyframe(4.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.build();

	/**
	 * Played once when Falsity is petted: a small hop, a lifted head and ears that
	 * perk up. This is the animation the rest of the mod will subvert later.
	 */
	public static final AnimationDefinition GREET = AnimationDefinition.Builder.withLength(1.2F)
		.addAnimation(FalsityModelGeometry.BODY, new AnimationChannel(
			AnimationChannel.Targets.POSITION,
			new Keyframe(0.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.3F, KeyframeAnimations.posVec(0.0F, -1.5F, 0.0F), SMOOTH),
			new Keyframe(0.6F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.9F, KeyframeAnimations.posVec(0.0F, -0.6F, 0.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.HEAD, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.3F, KeyframeAnimations.degreeVec(-10.0F, 0.0F, 4.0F), SMOOTH),
			new Keyframe(0.8F, KeyframeAnimations.degreeVec(-4.0F, 0.0F, -3.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.EAR_LEFT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.25F, KeyframeAnimations.degreeVec(-6.0F, 0.0F, 12.0F), SMOOTH),
			new Keyframe(0.7F, KeyframeAnimations.degreeVec(0.0F, 0.0F, -4.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.EAR_RIGHT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.25F, KeyframeAnimations.degreeVec(-6.0F, 0.0F, -12.0F), SMOOTH),
			new Keyframe(0.7F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 4.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.ARM_LEFT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.3F, KeyframeAnimations.degreeVec(-12.0F, 0.0F, -22.0F), SMOOTH),
			new Keyframe(0.9F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.addAnimation(FalsityModelGeometry.ARM_RIGHT, new AnimationChannel(
			AnimationChannel.Targets.ROTATION,
			new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(0.3F, KeyframeAnimations.degreeVec(-12.0F, 0.0F, 22.0F), SMOOTH),
			new Keyframe(0.9F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH),
			new Keyframe(1.2F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F), SMOOTH)
		))
		.build();

	/** How long a single blink takes, in ticks. */
	private static final float BLINK_TICKS = 4.0F;

	private FalsityAnimations() {
	}

	/**
	 * How closed Falsity's eyes are, from 0 (open) to 1 (shut).
	 *
	 * <p>Blinking is derived from the entity's age and id rather than stored, so
	 * it costs nothing to keep in sync and no two Falsities blink together. The
	 * curve is a half sine, which reads as a real blink rather than a lerp.
	 */
	public static float blinkAmount(float ageInTicks, int blinkPhase) {
		int interval = 70 + Math.floorMod(blinkPhase, 55);
		float offset = Math.floorMod(blinkPhase * 37, interval);
		float phase = (ageInTicks + offset) % interval;

		if (phase >= BLINK_TICKS) {
			return 0.0F;
		}

		return Mth.sin(phase / BLINK_TICKS * Mth.PI);
	}
}
