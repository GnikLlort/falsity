package com.falsity.client;

import com.falsity.FalsityRegistries;
import com.falsity.client.model.ModEntityModelLayers;
import com.falsity.client.renderer.FalsityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * Client entry point: bakes Falsity's model layer and registers its renderer.
 * Nothing else in the mod needs the client, which keeps the server jar clean.
 */
public class FalsityClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModEntityModelLayers.registerModelLayers();
		EntityRenderers.register(FalsityRegistries.FALSITY, FalsityRenderer::new);
	}
}
