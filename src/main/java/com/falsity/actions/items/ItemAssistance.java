package com.falsity.actions.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

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
	 * Resolves category requests to conservative vanilla defaults. A full armour
	 * request deliberately returns all four pieces; other broad categories use
	 * a compact useful kit. Explicit material words are honoured where possible.
	 */
	public static List<Item> resolveSelection(String rawName) {
		String text = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT).trim();
		String material = text.contains("diamond") ? "diamond" : text.contains("iron") ? "iron"
			: text.contains("gold") ? "golden" : text.contains("stone") ? "stone" : "iron";
		List<String> ids;
		if (text.matches(".*\\b(full set of )?(armour|armor)\\b.*")) {
			String armourMaterial = material.equals("stone") ? "iron" : material;
			ids = List.of(armourMaterial + "_helmet", armourMaterial + "_chestplate", armourMaterial + "_leggings", armourMaterial + "_boots");
		} else if (text.matches(".*\\b(food|rations|meal)\\b.*")) ids = List.of("cooked_beef");
		else if (text.matches(".*\\b(weapons|weapon)\\b.*")) ids = List.of(material + "_sword", "bow", "arrow");
		else if (text.matches(".*\\b(tools|tool|mining equipment)\\b.*")) ids = List.of(material + "_pickaxe", material + "_axe", material + "_shovel");
		else if (text.matches(".*\\b(building (blocks|materials)|building blocks)\\b.*")) ids = List.of(text.contains("stone") ? "stone" : "oak_planks");
		else if (text.matches(".*\\b(lighting|lights)\\b.*")) ids = List.of("torch");
		else if (text.matches(".*\\b(transportation|transport)\\b.*")) ids = List.of("minecart");
		else if (text.matches(".*\\b(farming supplies|farm supplies)\\b.*")) ids = List.of("iron_hoe", "wheat_seeds", "water_bucket");
		else if (text.matches(".*\\b(redstone supplies|redstone kit)\\b.*")) ids = List.of("redstone", "repeater", "comparator");
		else if (text.matches(".*\\b(survival supplies|survival kit)\\b.*")) ids = List.of("cooked_beef", "torch", "oak_planks");
		else if (text.matches(".*\\bwood\\b.*")) ids = List.of("oak_log");
		else return resolve(rawName).map(List::of).orElseGet(List::of);

		List<Item> result = new ArrayList<>();
		for (String id : ids) lookup(id).ifPresent(result::add);
		return List.copyOf(result);
	}

	/** Resolves a name like "32 diamonds" (after the number is stripped), "oak logs" or "diamond pickaxe". */
	public static Optional<Item> resolve(String rawName) {
		for (String candidate : candidates(rawName)) {
			Optional<Item> item = lookup(candidate);
			if (item.isPresent()) {
				return item;
			}
		}
		return Optional.empty();
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
