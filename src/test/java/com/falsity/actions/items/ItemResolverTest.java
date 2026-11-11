package com.falsity.actions.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.items.ItemResolver.Kind;
import com.falsity.actions.items.ItemResolver.Resolution;
import com.falsity.testing.VanillaIds;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resolving what a player called an item against the real vanilla item list. */
class ItemResolverTest {
	private static final ItemCatalog CATALOG = VanillaIds.itemCatalog();

	private static String id(String name) {
		Resolution resolution = ItemResolver.resolve(name, CATALOG);
		assertEquals(Kind.ITEM, resolution.kind(), name + " -> " + resolution);
		return resolution.ids().get(0);
	}

	@Test
	void exactNamesAndPlurals() {
		assertEquals("crafting_table", id("crafting table"));
		assertEquals("stick", id("sticks"));
		assertEquals("torch", id("torches"));
		assertEquals("diamond", id("diamonds"));
		assertEquals("oak_planks", id("oak planks"));
		assertEquals("diamond_pickaxe", id("Diamond Pickaxe!"));
		assertEquals("oak_log", id("minecraft:oak_log"));
		assertEquals("potato", id("potatoes"));
		assertEquals("bookshelf", id("bookshelves"));
		assertEquals("flint_and_steel", id("flint and steel"));
	}

	@Test
	void everydayNames() {
		assertEquals("oak_log", id("wood"));
		assertEquals("oak_planks", id("planks"));
		assertEquals("cooked_beef", id("food"));
		assertEquals("cooked_beef", id("steak"));
		assertEquals("water_bucket", id("bucket of water"));
		assertEquals("iron_ingot", id("iron"));
		assertEquals("iron_block", id("block of iron"));
		assertEquals("ender_eye", id("eye of ender"));
		assertEquals("experience_bottle", id("xp bottles"));
		assertEquals("torch", id("light"));
	}

	@Test
	void thingsThatComeInKindsGetTheCommonOne() {
		assertEquals("oak_door", id("door"));
		assertEquals("oak_door", id("wooden door"));
		assertEquals("iron_door", id("iron door"));
		assertEquals("oak_boat", id("boat"));
		assertEquals("red_bed", id("bed"));
		assertEquals("white_wool", id("wool"));
		assertEquals("red_wool", id("red wool"));
		assertEquals("oak_sapling", id("saplings"));
	}

	@Test
	void bareEquipmentDefaultsToIronAndStatedMaterialIsKept() {
		assertEquals("iron_pickaxe", id("pickaxe"));
		assertEquals("iron_pickaxe", id("pick"));
		assertEquals("iron_sword", id("sword"));
		assertEquals("diamond_pickaxe", id("diamond pickaxe"));
		assertEquals("golden_sword", id("gold sword"));
		assertEquals("wooden_pickaxe", id("wood pickaxe"));
		assertEquals("stone_pickaxe", id("stone pickaxe"));
		assertEquals("leather_helmet", id("leather cap"));
		assertEquals("chainmail_helmet", id("chain helmet"));
		assertEquals("golden_apple", id("gold apple"));
	}

	@Test
	void aMaterialThatDoesNotExistIsRefusedNotSwapped() {
		Resolution armour = ItemResolver.resolve("stone armor", CATALOG);
		assertEquals(Kind.UNKNOWN, armour.kind(), "stone armour must not quietly become iron");
		assertTrue(armour.ids().isEmpty());
		assertFalse(armour.reason().isEmpty(), "the player should be told why");

		Resolution helmet = ItemResolver.resolve("stone helmet", CATALOG);
		assertEquals(Kind.UNKNOWN, helmet.kind());
		assertTrue(helmet.reason().contains("stone helmet"));
	}

	@Test
	void namedSets() {
		Resolution armour = ItemResolver.resolve("full set of armour", CATALOG);
		assertEquals(Kind.KIT, armour.kind());
		assertEquals(List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"), armour.ids());

		Resolution diamond = ItemResolver.resolve("diamond armor", CATALOG);
		assertEquals(List.of("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"), diamond.ids());

		Resolution tools = ItemResolver.resolve("tools", CATALOG);
		assertEquals(List.of("iron_pickaxe", "iron_axe", "iron_shovel"), tools.ids());
	}

	@Test
	void aMaterialCarriesOverToLaterEquipment() {
		assertEquals("diamond", ItemResolver.statedMaterial("diamond pickaxe"));
		assertEquals("", ItemResolver.statedMaterial("diamonds"), "a material on its own is not equipment");

		Resolution shovel = ItemResolver.resolve("shovel", CATALOG, "diamond");
		assertEquals("diamond_shovel", shovel.ids().get(0));

		Resolution stated = ItemResolver.resolve("stone axe", CATALOG, "diamond");
		assertEquals("stone_axe", stated.ids().get(0), "an explicit material always wins");
	}

	@Test
	void thingsThatAreNotItemsStayUnknown() {
		for (String name : List.of("hug", "flibber", "you", "help", "love", "a break", "friend", "sandwich")) {
			Resolution resolution = ItemResolver.resolve(name, CATALOG);
			assertEquals(Kind.UNKNOWN, resolution.kind(), name);
			assertTrue(resolution.ids().isEmpty(), name);
		}
		assertEquals(Kind.UNKNOWN, ItemResolver.resolve("", CATALOG).kind());
		assertEquals(Kind.UNKNOWN, ItemResolver.resolve(null, CATALOG).kind());
	}

	@Test
	void everyIdTheResolverCanProduceIsReal() {
		// Aliases, kits and defaults are written by hand; a typo would hand out nothing.
		for (String name : List.of(
			"wood", "log", "planks", "crafting bench", "workbench", "enchantment table", "bed", "wool", "carpet", "banner",
			"concrete", "stained glass", "water", "lava", "milk", "steak", "meat", "food", "raw beef", "pork", "raw pork",
			"fish", "raw fish", "seeds", "bonemeal", "sugarcane", "cocoa", "xp bottle", "eye of ender", "pearl", "gapple",
			"god apple", "redstone dust", "lapis", "iron", "gold", "copper", "netherite", "cobble", "bottle", "totem",
			"leash", "nametag", "lighter", "rod", "slimeball", "rocket", "daylight sensor", "fuel", "ammo", "light",
			"transport", "building blocks", "berries", "watermelon", "drink", "farming supplies", "redstone supplies",
			"survival kit", "supplies", "weapons", "weapon", "tools", "armor", "leather armor", "golden armor",
			"netherite armor", "chainmail armor", "diamond tools", "stone tools", "wooden tools", "golden tools")) {
			Resolution resolution = ItemResolver.resolve(name, CATALOG);
			assertTrue(resolution.known(), name + " should resolve");
			for (String id : resolution.ids()) {
				assertTrue(VanillaIds.items().contains(id), name + " -> " + id + " is not a real item");
			}
		}
	}

	@Test
	void airAndTheInvisibleLightBlockAreNeverHandedOut() {
		for (String name : List.of("air", "some air", "block of air", "cave air", "void air", "minecraft:air")) {
			ItemResolver.Resolution resolution = ItemResolver.resolve(name, CATALOG);
			assertFalse(resolution.known(), "'" + name + "' resolved to " + resolution.ids());
		}
		assertEquals("torch", id("light"), "asking for light means a torch, not the technical light block");
		assertFalse(ItemResolver.resolve("block of light", CATALOG).known());
	}

	@Test
	void technicalItemsAreMarkedRestricted() {
		assertTrue(ItemResolver.isRestricted("bedrock"));
		assertTrue(ItemResolver.isRestricted("command_block"));
		assertTrue(ItemResolver.isRestricted("creeper_spawn_egg"));
		assertFalse(ItemResolver.isRestricted("diamond"));
		assertFalse(ItemResolver.isRestricted("torch"));
	}

	@Test
	void labelsPluraliseForReplies() {
		assertEquals("sticks", ItemResolver.pluralise("stick", 64));
		assertEquals("stick", ItemResolver.pluralise("stick", 1));
		assertEquals("torches", ItemResolver.pluralise("torch", 3));
		assertEquals("oak planks", ItemResolver.pluralise("oak planks", 12));
		assertEquals("cooked beef", ItemResolver.pluralise("cooked beef", 5));
		assertEquals("crafting tables", ItemResolver.pluralise("crafting table", 2));
		assertEquals("potatoes", ItemResolver.pluralise("potato", 2));
	}
}
