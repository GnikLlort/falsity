package com.falsity.actions.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves a player's description of an item against Minecraft's real registry.
 *
 * <p>Falsity never invents an item id and never substitutes a different item.
 * A name is normalised (case, plural, common synonyms, multi-word ids) and then
 * looked up in {@link BuiltInRegistries#ITEM}. If nothing matches, resolution
 * fails and the caller reports that honestly instead of guessing.
 */
public final class ItemAssistance {
	/** Minecraft namespace used for vanilla ids. */
	public static final String VANILLA_NAMESPACE = "minecraft";

	private static final Map<String, String> SYNONYMS = Map.ofEntries(
		Map.entry("wood", "planks"),
		Map.entry("wooden", "planks"),
		Map.entry("planks", "planks"),
		Map.entry("plank", "planks"),
		Map.entry("log", "log"),
		Map.entry("logs", "log"),
		Map.entry("stairs", "stairs"),
		Map.entry("stair", "stairs"),
		Map.entry("bricks", "bricks"),
		Map.entry("brick", "bricks"),
		Map.entry("diamonds", "diamond"),
		Map.entry("diamond", "diamond"),
		Map.entry("apples", "apple"),
		Map.entry("apple", "apple"),
		Map.entry("coals", "coal"),
		Map.entry("coal", "coal"),
		Map.entry("emeralds", "emerald"),
		Map.entry("emerald", "emerald"),
		Map.entry("iron", "iron"),
		Map.entry("irons", "iron"),
		Map.entry("golds", "gold"),
		Map.entry("gold", "gold"),
		Map.entry("golden", "golden"),
		Map.entry("pickaxes", "pickaxe"),
		Map.entry("pickaxe", "pickaxe"),
		Map.entry("axes", "axe"),
		Map.entry("axe", "axe"),
		Map.entry("shovels", "shovel"),
		Map.entry("shovel", "shovel"),
		Map.entry("hoes", "hoe"),
		Map.entry("hoe", "hoe"),
		Map.entry("swords", "sword"),
		Map.entry("sword", "sword"),
		Map.entry("helmets", "helmet"),
		Map.entry("helmet", "helmet"),
		Map.entry("chestplates", "chestplate"),
		Map.entry("chestplate", "chestplate"),
		Map.entry("leggings", "leggings"),
		Map.entry("boots", "boots"),
		Map.entry("bows", "bow"),
		Map.entry("bow", "bow"),
		Map.entry("arrows", "arrow"),
		Map.entry("arrow", "arrow"),
		Map.entry("buckets", "bucket"),
		Map.entry("bucket", "bucket"),
		Map.entry("torches", "torch"),
		Map.entry("torch", "torch"),
		Map.entry("doors", "door"),
		Map.entry("door", "door"),
		Map.entry("fences", "fence"),
		Map.entry("fence", "fence"),
		Map.entry("slabs", "slab"),
		Map.entry("slab", "slab"),
		Map.entry("buttons", "button"),
		Map.entry("button", "button"),
		Map.entry("eggs", "egg"),
		Map.entry("egg", "egg")
	);

	private ItemAssistance() {
	}

	/**
	 * The live item registry seen as an {@link ItemCatalog}. Only vanilla ids
	 * are looked up, so a made-up name can never match.
	 */
	public static final ItemCatalog CATALOG = new ItemCatalog() {
		@Override
		public boolean exists(String id) {
			return lookup(id).isPresent();
		}

		@Override
		public int maxStackSize(String id) {
			return lookup(id).map(item -> Math.max(1, new ItemStack(item, 1).getMaxStackSize())).orElse(64);
		}
	};

	/**
	 * Resolves a name to the items it stands for: one item, or the pieces of a
	 * named set such as "full set of armour". A stated material is honoured
	 * exactly; a name that matches nothing real resolves to nothing.
	 */
	public static List<Item> resolveSelection(String rawName) {
		ItemResolver.Resolution resolution = ItemResolver.resolve(rawName, CATALOG);
		List<Item> result = new ArrayList<>();
		for (String id : resolution.ids()) {
			lookup(id).ifPresent(result::add);
		}
		return List.copyOf(result);
	}

	/** Resolves a name like "diamonds", "oak logs" or "diamond pickaxe" to one item. */
	public static Optional<Item> resolve(String rawName) {
		ItemResolver.Resolution resolution = ItemResolver.resolve(rawName, CATALOG);
		if (resolution.kind() != ItemResolver.Kind.ITEM || resolution.ids().isEmpty()) {
			return Optional.empty();
		}
		return lookup(resolution.ids().get(0));
	}

	/** Looks a vanilla item up by its id path. */
	public static Optional<Item> byId(String id) {
		return lookup(id);
	}

	/** Produces the ordered list of id spellings to try for a name. */
	static List<String> candidates(String rawName) {
		List<String> candidates = new ArrayList<>();
		if (rawName == null) {
			return candidates;
		}

		String cleaned = rawName.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9 _+\\-]", " ")
			.replaceAll("\\s+", " ")
			.trim();
		if (cleaned.isEmpty()) {
			return candidates;
		}

		String flat = cleaned.replace(' ', '_');
		candidates.add(flat);

		// Synonym-normalised spelling, e.g. "oak logs" -> "oak_log".
		List<String> tokens = new ArrayList<>();
		for (String token : cleaned.split(" ")) {
			tokens.add(SYNONYMS.getOrDefault(token, token));
		}
		String normalised = String.join("_", tokens);
		if (!normalised.equals(flat)) {
			candidates.add(normalised);
		}

		// Singular fallback, e.g. "diamonds" -> "diamond".
		if (candidates.get(0).endsWith("s") && candidates.get(0).length() > 1) {
			candidates.add(candidates.get(0).substring(0, candidates.get(0).length() - 1));
		}

		return candidates;
	}

	private static Optional<Item> lookup(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		try {
			Identifier identifier = Identifier.fromNamespaceAndPath(VANILLA_NAMESPACE, id);
			return BuiltInRegistries.ITEM.getOptional(identifier);
		} catch (RuntimeException malformed) {
			return Optional.empty();
		}
	}
}
