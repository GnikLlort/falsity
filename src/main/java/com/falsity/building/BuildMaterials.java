package com.falsity.building;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Counts and consumes the materials a build needs from the player's inventory.
 *
 * <p>A block's item form is resolved through the real registries, so a plan can
 * never quietly require an item that does not exist. Blocks with no item form
 * (water, for example) cost nothing.
 */
public final class BuildMaterials {
	private BuildMaterials() {
	}

	/** The item and quantity required by every placement in the plan. */
	public static Map<Item, Integer> required(BuildPlan plan) {
		Map<Item, Integer> needed = new LinkedHashMap<>();
		for (BlockPlacement placement : plan.placements()) {
			Item item = itemFor(placement.state().getBlock());
			if (item == null || item == Items.AIR) {
				continue;
			}
			needed.merge(item, 1, Integer::sum);
		}
		return needed;
	}

	/** How many of an item the player is carrying. */
	public static int count(Player player, Item item) {
		int total = 0;
		for (ItemStack stack : player.getInventory().items()) {
			if (stack.getItem() == item) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Removes one item from the player's inventory. Returns false if they have none. */
	public static boolean consume(Player player, Item item) {
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.items().size(); slot++) {
			ItemStack stack = inventory.items().get(slot);
			if (stack.getItem() == item && stack.getCount() > 0) {
				ItemStack remainder = stack.split(1);
				inventory.setItem(slot, stack);
				return remainder.getCount() > 0;
			}
		}
		return false;
	}

	/** The item form of a block, or {@link Items#AIR} when it has none. */
	static Item itemFor(Block block) {
		try {
			Optional<Identifier> key = BuiltInRegistries.BLOCK.getKey(block);
			if (key.isEmpty()) {
				return Items.AIR;
			}
			return BuiltInRegistries.ITEM.getOptional(key.get()).orElse(Items.AIR);
		} catch (RuntimeException missing) {
			return Items.AIR;
		}
	}
}
