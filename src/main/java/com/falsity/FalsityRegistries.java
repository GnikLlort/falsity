package com.falsity;

import com.falsity.entity.FalsityAttributes;
import com.falsity.entity.FalsityEntity;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Everything the game has to know about Falsity before it can exist: the entity
 * type, its default attributes, the spawn egg and the creative tab entry.
 */
public final class FalsityRegistries {
	public static final ResourceKey<EntityType<?>> FALSITY_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, FalsityMod.id("falsity"));

	/**
	 * Falsity itself. A small creature, so the hitbox stays small; clients are told
	 * about it from ten chunks away, which is enough for it to be seen watching.
	 */
	public static final EntityType<FalsityEntity> FALSITY = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		FALSITY_KEY,
		EntityType.Builder.<FalsityEntity>of(FalsityEntity::new, MobCategory.CREATURE)
			.sized(0.7F, 1.2F)
			.eyeHeight(1.0F)
			.clientTrackingRange(10)
			.build(FALSITY_KEY)
	);

	public static final ResourceKey<Item> FALSITY_SPAWN_EGG_KEY =
		ResourceKey.create(Registries.ITEM, FalsityMod.id("falsity_spawn_egg"));

	public static final Item FALSITY_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		FALSITY_SPAWN_EGG_KEY,
		new SpawnEggItem(new Item.Properties().setId(FALSITY_SPAWN_EGG_KEY).spawnEgg(FALSITY))
	);

	private static boolean initialized;

	private FalsityRegistries() {
	}

	/**
	 * Wires up the pieces that need registering rather than merely declaring:
	 * the default attribute supplier and the creative tab entry.
	 */
	public static void initialize() {
		if (initialized) {
			return;
		}

		initialized = true;

		FabricDefaultAttributeRegistry.register(FALSITY, FalsityAttributes.create());
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS)
			.register(output -> output.accept(FALSITY_SPAWN_EGG));

		FalsityMod.LOGGER.info("Registered entity type {} and its spawn egg.", FALSITY_KEY.identifier());
	}
}
