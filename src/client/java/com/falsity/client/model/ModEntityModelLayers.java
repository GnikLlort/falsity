package com.falsity.client.model;

import com.falsity.FalsityMod;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;

/** Baked model layers owned by this mod. */
public final class ModEntityModelLayers {
	public static final ModelLayerLocation FALSITY =
		new ModelLayerLocation(FalsityMod.id("falsity"), "main");

	private ModEntityModelLayers() {
	}

	public static void registerModelLayers() {
		ModelLayerRegistry.registerModelLayer(FALSITY, FalsityModelGeometry::create);
	}
}
