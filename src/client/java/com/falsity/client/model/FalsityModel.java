package com.falsity.client.model;

import com.falsity.client.animation.FalsityAnimations;
import com.falsity.client.state.FalsityRenderState;
import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Falsity's first-form model.
 *
 * <p>Animation is split in two on purpose. Hand-authored keyframes ({@link
 * FalsityAnimations}) cover looping idle motion and the one-shot greeting,
 * because that kind of motion is easier to read and tune as keyframes.
 * Motion that depends on the world — walking speed, head tracking, blinking,
 * sitting — is computed here, because it has to react every frame.
 */
public class FalsityModel extends EntityModel<FalsityRenderState> {
	private final ModelPart root;
	private final ModelPart body;
	private final ModelPart head;
	private final ModelPart eyeLeft;
	private final ModelPart eyeRight;
	private final ModelPart armLeft;
	private final ModelPart armRight;
	private final ModelPart legLeft;
	private final ModelPart legRight;
	private final KeyframeAnimation idleAnimation;
	private final KeyframeAnimation greetAnimation;

	public FalsityModel(ModelPart rootPart) {
		super(rootPart);
		this.root = rootPart;
		this.body = rootPart.getChild(FalsityModelGeometry.BODY);
		this.head = this.body.getChild(FalsityModelGeometry.HEAD);
		this.eyeLeft = this.head.getChild(FalsityModelGeometry.EYE_LEFT);
		this.eyeRight = this.head.getChild(FalsityModelGeometry.EYE_RIGHT);
		this.armLeft = this.body.getChild(FalsityModelGeometry.ARM_LEFT);
		this.armRight = this.body.getChild(FalsityModelGeometry.ARM_RIGHT);
		this.legLeft = this.body.getChild(FalsityModelGeometry.LEG_LEFT);
		this.legRight = this.body.getChild(FalsityModelGeometry.LEG_RIGHT);

		this.idleAnimation = FalsityAnimations.IDLE.bake(rootPart);
		this.greetAnimation = FalsityAnimations.GREET.bake(rootPart);
	}

	@Override
	public void setupAnim(FalsityRenderState state) {
		super.setupAnim(state);

		// Start from the rest pose every frame, then layer on top of it.
		this.root.getAllParts().forEach(ModelPart::resetPose);

		this.idleAnimation.apply(state.idleAnimationState, state.ageInTicks);
		this.greetAnimation.apply(state.greetAnimationState, state.ageInTicks);

		applyHeadTracking(state);
		applyWalkCycle(state);
		applyBlink(state);
		applySitting(state);
	}

	private void applyHeadTracking(FalsityRenderState state) {
		// LivingEntityRenderState carries head yaw and pitch in degrees.
		this.head.yRot += state.yRot * Mth.DEG_TO_RAD;
		this.head.xRot += state.xRot * Mth.DEG_TO_RAD;
	}

	private void applyWalkCycle(FalsityRenderState state) {
		float limbSwing = state.walkAnimationPos * 0.6662F;
		float limbAmount = Math.min(state.walkAnimationSpeed, 1.0F);

		if (limbAmount <= 0.0F) {
			return;
		}

		this.legLeft.xRot += Mth.cos(limbSwing) * 0.9F * limbAmount;
		this.legRight.xRot += Mth.cos(limbSwing + Mth.PI) * 0.9F * limbAmount;
		this.armLeft.xRot += Mth.cos(limbSwing + Mth.PI) * 0.7F * limbAmount;
		this.armRight.xRot += Mth.cos(limbSwing) * 0.7F * limbAmount;
		// A little counter-rotation in the torso sells the weight of each step.
		this.body.zRot += Mth.cos(limbSwing) * 0.03F * limbAmount;
	}

	private void applyBlink(FalsityRenderState state) {
		float openness = 1.0F - FalsityAnimations.blinkAmount(state.ageInTicks, state.blinkPhase);

		if (openness < 0.0F) {
			openness = 0.0F;
		}

		this.eyeLeft.yScale = openness;
		this.eyeRight.yScale = openness;
	}

	private void applySitting(FalsityRenderState state) {
		if (!state.sitting) {
			return;
		}

		// Settle onto the ground and let the legs splay out instead of standing.
		this.body.y += 1.5F;
		this.legLeft.xRot -= 1.3F;
		this.legRight.xRot -= 1.3F;
		this.armLeft.zRot += 0.25F;
		this.armRight.zRot -= 0.25F;
	}
}
