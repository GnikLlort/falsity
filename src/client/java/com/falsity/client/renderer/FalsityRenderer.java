package com.falsity.client.renderer;

import com.falsity.FalsityMod;
import com.falsity.client.model.FalsityModel;
import com.falsity.client.model.ModEntityModelLayers;
import com.falsity.client.state.FalsityRenderState;
import com.falsity.entity.FalsityEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

/** Draws Falsity. All of the visual variation happens in the model and texture. */
public class FalsityRenderer extends MobRenderer<FalsityEntity, FalsityRenderState, FalsityModel> {
	/** Shadow size, in blocks. Small creature, small shadow. */
	private static final float SHADOW_RADIUS = 0.4F;

	public static final Identifier TEXTURE = FalsityMod.id("textures/entity/falsity/falsity.png");

	public FalsityRenderer(EntityRendererProvider.Context context) {
		super(context, new FalsityModel(context.bakeLayer(ModEntityModelLayers.FALSITY)), SHADOW_RADIUS);
	}

	@Override
	public FalsityRenderState createRenderState() {
		return new FalsityRenderState();
	}

	@Override
	public void extractRenderState(FalsityEntity entity, FalsityRenderState state, float tickProgress) {
		super.extractRenderState(entity, state, tickProgress);
		state.idleAnimationState.copyFrom(entity.idleAnimationState);
		state.greetAnimationState.copyFrom(entity.greetAnimationState);
		state.blinkPhase = entity.getId();
		state.sitting = entity.isSitting();
	}

	@Override
	public Identifier getTextureLocation(FalsityRenderState state) {
		return TEXTURE;
	}
}
