package com.falsity.actions.items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out which real item a player's words refer to.
 *
 * <p>Falsity never invents an item id and never quietly hands over something
 * else. A name is tried as written, then in the singular, then through a table
 * of everyday names ("wood", "steak", "pick"), then with the usual default for
 * things that come in many kinds (a "door" is an oak door, a bare "pickaxe" is
 * an iron one). Every candidate is checked against the {@link ItemCatalog}; if
 * nothing real matches, the result says so and the caller asks the player
 * instead of guessing.
 *
 * <p>A material is honoured exactly. "Stone armour" does not exist, so it is
 * reported as unknown rather than swapped for iron.
 */
public final class ItemResolver {
	/** What a name turned out to be. */
	public enum Kind {
		/** One real item. */
		ITEM,
		/** A named set of several real items, such as a suit of armour. */
		KIT,
		/** Nothing real matched. */
		UNKNOWN
	}

	/**
	 * The outcome of resolving one name.
	 *
	 * @param requested the player's words
	 * @param kind      what it resolved to
	 * @param ids       the real item ids (one for ITEM, several for KIT, none for UNKNOWN)
	 * @param label     how to name it back to the player (singular, without a count)
	 * @param material  the equipment material that was stated or applied, or empty
	 * @param reason    for UNKNOWN: why, when there is something specific to say; otherwise empty
	 */
	public record Resolution(String requested, Kind kind, List<String> ids, String label, String material, String reason) {
		public Resolution {
			ids = ids == null ? List.of() : List.copyOf(ids);
			label = label == null ? "" : label;
			material = material == null ? "" : material;
			reason = reason == null ? "" : reason;
		}

		public boolean known() {
			return this.kind != Kind.UNKNOWN && !this.ids.isEmpty();
		}
	}

	private static final List<String> ARMOUR_PIECES = List.of("helmet", "chestplate", "leggings", "boots");
	private static final List<String> TOOL_PIECES = List.of("pickaxe", "axe", "shovel");
	private static final Set<String> EQUIPMENT = Set.of(
		"pickaxe", "axe", "shovel", "hoe", "sword", "helmet", "chestplate", "leggings", "boots");

	/** Player wording for an equipment material, mapped to the id prefix. */
	private static final Map<String, String> MATERIALS = Map.ofEntries(
		Map.entry("wooden", "wooden"), Map.entry("wood", "wooden"),
		Map.entry("stone", "stone"),
		Map.entry("iron", "iron"),
		Map.entry("gold", "golden"), Map.entry("golden", "golden"),
		Map.entry("diamond", "diamond"),
		Map.entry("netherite", "netherite"),
		Map.entry("copper", "copper"),
		Map.entry("leather", "leather"),
		Map.entry("chain", "chainmail"), Map.entry("chainmail", "chainmail"),
		Map.entry("turtle", "turtle")
	);

	/** Words that describe a request without naming anything. */
	private static final Set<String> FILLER = Set.of(
		"some", "any", "the", "a", "an", "my", "more", "extra", "new", "good", "nice", "spare", "fresh",
		"basic", "simple", "regular", "normal", "plain", "ordinary", "standard", "decent", "few");

	/** Everyday names for one specific item. Keys are lower-case, singular where that is natural. */
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("wood", "oak_log"), Map.entry("log", "oak_log"), Map.entry("tree", "oak_log"), Map.entry("timber", "oak_log"),
		Map.entry("plank", "oak_planks"), Map.entry("planks", "oak_planks"), Map.entry("wooden plank", "oak_planks"),
		Map.entry("wooden planks", "oak_planks"), Map.entry("wood plank", "oak_planks"), Map.entry("wood planks", "oak_planks"),
		Map.entry("board", "oak_planks"),
		Map.entry("crafting bench", "crafting_table"), Map.entry("workbench", "crafting_table"), Map.entry("work bench", "crafting_table"),
		Map.entry("craft table", "crafting_table"), Map.entry("crafting", "crafting_table"), Map.entry("craftingtable", "crafting_table"),
		Map.entry("enchantment table", "enchanting_table"), Map.entry("enchant table", "enchanting_table"),
		Map.entry("bed", "red_bed"), Map.entry("wool", "white_wool"), Map.entry("carpet", "white_carpet"),
		Map.entry("banner", "white_banner"), Map.entry("concrete", "white_concrete"), Map.entry("stained glass", "white_stained_glass"),
		Map.entry("water", "water_bucket"), Map.entry("bucket of water", "water_bucket"),
		Map.entry("lava", "lava_bucket"), Map.entry("bucket of lava", "lava_bucket"),
		Map.entry("milk", "milk_bucket"), Map.entry("bucket of milk", "milk_bucket"),
		Map.entry("steak", "cooked_beef"), Map.entry("cooked steak", "cooked_beef"), Map.entry("meat", "cooked_beef"),
		Map.entry("food", "cooked_beef"), Map.entry("ration", "cooked_beef"), Map.entry("meal", "cooked_beef"),
		Map.entry("snack", "cooked_beef"), Map.entry("something to eat", "cooked_beef"), Map.entry("raw beef", "beef"),
		Map.entry("pork", "cooked_porkchop"), Map.entry("cooked pork", "cooked_porkchop"), Map.entry("raw pork", "porkchop"),
		Map.entry("fish", "cooked_cod"), Map.entry("cooked fish", "cooked_cod"), Map.entry("raw fish", "cod"),
		Map.entry("seed", "wheat_seeds"), Map.entry("seeds", "wheat_seeds"),
		Map.entry("bonemeal", "bone_meal"), Map.entry("sugarcane", "sugar_cane"), Map.entry("cocoa", "cocoa_beans"),
		Map.entry("xp bottle", "experience_bottle"), Map.entry("bottle o enchanting", "experience_bottle"),
		Map.entry("eye of ender", "ender_eye"), Map.entry("pearl", "ender_pearl"),
		Map.entry("gapple", "golden_apple"), Map.entry("god apple", "enchanted_golden_apple"),
		Map.entry("notch apple", "enchanted_golden_apple"), Map.entry("enchanted apple", "enchanted_golden_apple"),
		Map.entry("redstone dust", "redstone"), Map.entry("lapis", "lapis_lazuli"),
		Map.entry("iron", "iron_ingot"), Map.entry("gold", "gold_ingot"), Map.entry("copper", "copper_ingot"),
		Map.entry("netherite", "netherite_ingot"), Map.entry("cobble", "cobblestone"),
		Map.entry("bottle", "glass_bottle"), Map.entry("totem", "totem_of_undying"),
		Map.entry("leash", "lead"), Map.entry("nametag", "name_tag"), Map.entry("lighter", "flint_and_steel"),
		Map.entry("rod", "fishing_rod"), Map.entry("slime", "slime_ball"), Map.entry("slimeball", "slime_ball"),
		Map.entry("rocket", "firework_rocket"), Map.entry("firework", "firework_rocket"),
		Map.entry("daylight sensor", "daylight_detector"), Map.entry("fuel", "coal"),
		Map.entry("ammo", "arrow"), Map.entry("ammunition", "arrow"),
		Map.entry("berry", "sweet_berries"), Map.entry("berries", "sweet_berries"), Map.entry("sweet berry", "sweet_berries"),
		Map.entry("melon slices", "melon_slice"), Map.entry("watermelon", "melon_slice"),
		Map.entry("something to drink", "milk_bucket"), Map.entry("drink", "milk_bucket"),
		Map.entry("light", "torch"), Map.entry("lighting", "torch"), Map.entry("light source", "torch"),
		Map.entry("transport", "minecart"), Map.entry("transportation", "minecart"),
		Map.entry("building block", "oak_planks"), Map.entry("building material", "oak_planks"), Map.entry("block", "oak_planks")
	);

	/** Named sets that are not armour or tools. */
	private static final Map<String, List<String>> KITS = Map.of(
		"farming supplies", List.of("iron_hoe", "wheat_seeds", "water_bucket"),
		"farm supplies", List.of("iron_hoe", "wheat_seeds", "water_bucket"),
		"redstone supplies", List.of("redstone", "repeater", "comparator"),
		"redstone kit", List.of("redstone", "repeater", "comparator"),
		"survival supplies", List.of("cooked_beef", "torch", "oak_planks"),
		"survival kit", List.of("cooked_beef", "torch", "oak_planks"),
		"starter kit", List.of("cooked_beef", "torch", "oak_planks"),
		"supplies", List.of("cooked_beef", "torch", "oak_planks")
	);

	/** Technical or creative-only items Falsity does not hand out on request. */
	private static final Set<String> RESTRICTED = Set.of(
		"air", "barrier", "bedrock", "command_block", "chain_command_block", "repeating_command_block",
		"command_block_minecart", "structure_block", "structure_void", "jigsaw", "light", "debug_stick",
		"knowledge_book", "spawner", "trial_spawner", "vault", "end_portal_frame", "reinforced_deepslate",
		"test_block", "test_instance_block", "petrified_oak_slab");

	private ItemResolver() {
	}

	/** Resolves a name with no material carried over from an earlier item. */
	public static Resolution resolve(String name, ItemCatalog catalog) {
		return resolve(name, catalog, "");
	}

	/**
	 * Resolves a name. {@code inheritedMaterial} is the material stated for an
	 * earlier piece of equipment in the same request ("a diamond pickaxe and a
	 * shovel"), used only when this name states none.
	 */
	public static Resolution resolve(String name, ItemCatalog catalog, String inheritedMaterial) {
		String requested = name == null ? "" : name.trim();
		List<String> words = words(requested);
		if (words.isEmpty() || catalog == null) {
			return unknown(requested, "");
		}
		String phrase = String.join(" ", words);

		// 1. Exactly what was typed, then its singular: the player's own word wins.
		for (String candidate : spellings(words)) {
			if (!NEVER_DIRECT.contains(candidate) && catalog.exists(candidate)) {
				return item(requested, candidate, labelFor(words, candidate), "");
			}
		}

		// 2. Named sets: armour, tools, weapons, supplies.
		Resolution kit = resolveKit(requested, words, catalog, inheritedMaterial);
		if (kit != null) {
			return kit;
		}

		// 3. Equipment, with the stated material or the default.
		Resolution equipment = resolveEquipment(requested, words, catalog, inheritedMaterial);
		if (equipment != null) {
			return equipment;
		}

		// 4. Everyday names.
		for (String key : List.of(phrase, singular(phrase))) {
			String alias = ALIASES.get(key);
			if (alias != null && catalog.exists(alias)) {
				return item(requested, alias, alias.replace('_', ' '), "");
			}
		}

		// 5. "oak wood" means logs; "block of iron" means an iron block.
		if (words.size() >= 2 && words.get(words.size() - 1).equals("wood")) {
			String log = String.join("_", words.subList(0, words.size() - 1)) + "_log";
			if (catalog.exists(log)) {
				return item(requested, log, log.replace('_', ' '), "");
			}
		}
		if (words.size() >= 3 && (words.get(0).equals("block") || words.get(0).equals("blocks")) && words.get(1).equals("of")) {
			List<String> rest = words.subList(2, words.size());
			for (String candidate : spellings(rest)) {
				if (catalog.exists(candidate + "_block")) {
					return item(requested, candidate + "_block", (candidate + "_block").replace('_', ' '), "");
				}
			}
			for (String candidate : spellings(rest)) {
				if (!NEVER_DIRECT.contains(candidate) && catalog.exists(candidate)) {
					return item(requested, candidate, candidate.replace('_', ' '), "");
				}
			}
		}

		// 6. Things that come in many kinds get the common one.
		List<String> generic = new ArrayList<>(words);
		if (generic.size() >= 2 && (generic.get(0).equals("wooden") || generic.get(0).equals("wood"))) {
			generic.remove(0);
		}
		for (String prefix : List.of("oak_", "white_", "stone_")) {
			for (String candidate : spellings(generic)) {
				if (catalog.exists(prefix + candidate)) {
					return item(requested, prefix + candidate, (prefix + candidate).replace('_', ' '), "");
				}
			}
		}

		// 7. "gold" and "golden" are used interchangeably by players.
		for (String candidate : spellings(swapGold(words))) {
			if (!NEVER_DIRECT.contains(candidate) && catalog.exists(candidate)) {
				return item(requested, candidate, candidate.replace('_', ' '), "");
			}
		}

		return unknown(requested, "");
	}

	/** The equipment material a name states ("diamond pickaxe" gives "diamond"), or empty. */
	public static String statedMaterial(String name) {
		List<String> words = words(name);
		if (words.size() < 2) {
			return "";
		}
		String last = singularWord(words.get(words.size() - 1));
		boolean equipmentLike = EQUIPMENT.contains(last) || isArmourWord(last) || last.equals("tool") || last.equals("tools")
			|| last.equals("weapon") || last.equals("weapons") || last.equals("leggings") || last.equals("boots");
		if (!equipmentLike) {
			return "";
		}
		for (String word : words) {
			String material = MATERIALS.get(word);
			if (material != null) {
				return material;
			}
		}
		return "";
	}

	/** Whether an item is technical or creative-only and needs operator rights. */
	public static boolean isRestricted(String id) {
		return id != null && (RESTRICTED.contains(id) || id.endsWith("_spawn_egg"));
	}

	/** Adds a plural ending to a label when it needs one, for "16 oak logs". */
	public static String pluralise(String label, int count) {
		if (label == null || label.isEmpty() || count == 1) {
			return label == null ? "" : label;
		}
		int space = label.lastIndexOf(' ');
		String last = space < 0 ? label : label.substring(space + 1);
		if (last.endsWith("s") || UNCOUNTABLE.contains(last)) {
			return label;
		}
		if (last.endsWith("ch") || last.endsWith("sh") || last.endsWith("x") || last.endsWith("z") || last.endsWith("ato")) {
			return label + "es";
		}
		if (last.length() > 2 && last.endsWith("y") && "aeiou".indexOf(last.charAt(last.length() - 2)) < 0) {
			return label.substring(0, label.length() - 1) + "ies";
		}
		if (last.endsWith("f") && last.length() > 3) {
			return label.substring(0, label.length() - 1) + "ves";
		}
		return label + "s";
	}

	private static final Set<String> UNCOUNTABLE = Set.of(
		"beef", "bread", "glass", "redstone", "coal", "charcoal", "wool", "sand", "dirt", "gravel", "leather", "string",
		"gunpowder", "flint", "paper", "sugar", "wheat", "clay", "obsidian", "stone", "cobblestone", "netherrack",
		"glowstone", "quartz", "iron", "gold", "lazuli", "steel", "tnt", "snow", "ice", "meal", "dust", "wart", "kelp",
		"bamboo", "mud", "salmon", "cod", "chicken", "mutton", "porkchop", "rabbit", "cocoa", "wood", "andesite",
		"diorite", "granite", "deepslate", "tuff", "calcite", "basalt", "blackstone", "terracotta", "concrete",
		"prismarine", "sandstone", "scaffolding", "elytra", "armor", "armour", "moss", "sulfur", "podzol", "mycelium",
		"farmland", "bedrock", "sculk", "honeycomb", "seagrass", "cane", "undying", "sea", "enchanting");

	private static Resolution resolveKit(String requested, List<String> words, ItemCatalog catalog, String inheritedMaterial) {
		String stated = "";
		List<String> rest = new ArrayList<>();
		for (String word : words) {
			String material = MATERIALS.get(word);
			if (material != null && stated.isEmpty()) {
				stated = material;
			} else if (!word.equals("full") && !word.equals("set") && !word.equals("sets") && !word.equals("suit")
				&& !word.equals("of") && !word.equals("whole") && !word.equals("complete") && !word.equals("entire")
				&& !word.equals("kit")) {
				rest.add(word);
			}
		}
		String core = String.join(" ", rest);

		if (core.equals("armor")) {
			String material = pickMaterial(stated, inheritedMaterial);
			List<String> ids = prefixed(material, ARMOUR_PIECES);
			if (!allExist(ids, catalog)) {
				return unknown(requested, "Armour does not come in " + wording(material) + ".");
			}
			return new Resolution(requested, Kind.KIT, ids, wording(material) + " armour", material, "");
		}
		if (core.equals("tools") || core.equals("tool") || core.equals("mining equipment") || core.equals("toolkit")) {
			String material = pickMaterial(stated, inheritedMaterial);
			List<String> ids = prefixed(material, TOOL_PIECES);
			if (!allExist(ids, catalog)) {
				return unknown(requested, "Tools do not come in " + wording(material) + ".");
			}
			return new Resolution(requested, Kind.KIT, ids, wording(material) + " tools", material, "");
		}
		if (core.equals("weapons")) {
			String material = pickMaterial(stated, inheritedMaterial);
			List<String> ids = List.of(material + "_sword", "bow", "arrow");
			if (!allExist(ids, catalog)) {
				return unknown(requested, "Swords do not come in " + wording(material) + ".");
			}
			return new Resolution(requested, Kind.KIT, ids, "weapons", material, "");
		}
		if (core.equals("weapon")) {
			String material = pickMaterial(stated, inheritedMaterial);
			String id = material + "_sword";
			if (!catalog.exists(id)) {
				return unknown(requested, "Swords do not come in " + wording(material) + ".");
			}
			return item(requested, id, id.replace('_', ' '), material);
		}

		List<String> kit = KITS.get(String.join(" ", words));
		if (kit != null && allExist(kit, catalog)) {
			return new Resolution(requested, Kind.KIT, kit, String.join(" ", words), "", "");
		}
		if (core.equals("building blocks") || core.equals("building materials")) {
			String id = words.contains("stone") ? "stone" : "oak_planks";
			if (catalog.exists(id)) {
				return item(requested, id, id.replace('_', ' '), "");
			}
		}
		return null;
	}

	private static Resolution resolveEquipment(String requested, List<String> words, ItemCatalog catalog, String inheritedMaterial) {
		String piece = singularWord(words.get(words.size() - 1));
		if (piece.equals("legging")) {
			piece = "leggings";
		} else if (piece.equals("boot")) {
			piece = "boots";
		}
		if (!EQUIPMENT.contains(piece)) {
			return null;
		}

		String stated = "";
		for (int i = 0; i < words.size() - 1; i++) {
			String word = words.get(i);
			String material = MATERIALS.get(word);
			if (material != null) {
				stated = material;
			} else if (!FILLER.contains(word)) {
				// "fishing rod axe"? Not plain equipment; let other rules try.
				return null;
			}
		}

		String material = pickMaterial(stated, inheritedMaterial);
		String id = material + "_" + piece;
		if (catalog.exists(id)) {
			return item(requested, id, id.replace('_', ' '), material);
		}
		if (stated.isEmpty() && !material.equals("iron") && catalog.exists("iron_" + piece)) {
			// The carried-over material does not make this piece; use the default.
			return item(requested, "iron_" + piece, "iron " + piece, "iron");
		}
		return unknown(requested, "There is no " + wording(material) + " " + piece + ".");
	}

	private static String pickMaterial(String stated, String inherited) {
		if (stated != null && !stated.isEmpty()) {
			return stated;
		}
		if (inherited != null && !inherited.isEmpty()) {
			return inherited;
		}
		return "iron";
	}

	/** How a material is said in a sentence: the id prefix "golden" reads as "gold". */
	private static String wording(String material) {
		return switch (material) {
			case "golden" -> "gold";
			case "wooden" -> "wood";
			default -> material;
		};
	}

	private static List<String> prefixed(String material, List<String> pieces) {
		List<String> ids = new ArrayList<>();
		for (String piece : pieces) {
			ids.add(material + "_" + piece);
		}
		return ids;
	}

	private static boolean allExist(List<String> ids, ItemCatalog catalog) {
		for (String id : ids) {
			if (!catalog.exists(id)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isArmourWord(String word) {
		return word.equals("armor") || word.equals("armour");
	}

	/**
	 * A few bare words are real ids for something the player never means when
	 * talking: "light" is an invisible technical block and "air" cannot be held.
	 * They are not matched directly; "light" goes through the everyday names.
	 */
	private static final Set<String> NEVER_DIRECT = Set.of("light", "air", "cave_air", "void_air");

	private static Resolution item(String requested, String id, String label, String material) {
		return new Resolution(requested, Kind.ITEM, List.of(id), label, material, "");
	}

	private static Resolution unknown(String requested, String reason) {
		return new Resolution(requested, Kind.UNKNOWN, List.of(), requested, "", reason);
	}

	/** The player's own words when they name the item directly, otherwise the item's name. */
	private static String labelFor(List<String> words, String id) {
		String typed = String.join(" ", words);
		String flat = typed.replace(' ', '_');
		if (flat.equals(id)) {
			return typed;
		}
		return id.replace('_', ' ');
	}

	/** Lower-cases, drops punctuation and filler, and normalises spelling variants. */
	static List<String> words(String name) {
		List<String> words = new ArrayList<>();
		if (name == null) {
			return words;
		}
		String cleaned = name.toLowerCase(Locale.ROOT).trim();
		if (cleaned.startsWith("minecraft:")) {
			cleaned = cleaned.substring("minecraft:".length());
		}
		cleaned = cleaned.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
		cleaned = cleaned.replaceFirst("^(?:(?:piece|pieces|bit|bits|chunk|chunks|pile|piles|bunch|lot|lots|handful|pair|pairs) of )", "");
		if (cleaned.isEmpty()) {
			return words;
		}

		for (String word : cleaned.split(" ")) {
			String normalised = switch (word) {
				case "armour", "armors", "armours" -> "armor";
				case "grey" -> "gray";
				case "pick", "picks", "pickax", "pickaxes" -> "pickaxe";
				case "pants", "trousers", "legs" -> "leggings";
				case "tunic", "breastplate" -> "chestplate";
				case "cap" -> "helmet";
				case "swords" -> "sword";
				case "axes" -> "axe";
				case "shovels", "spade", "spades" -> "shovel";
				case "hoes" -> "hoe";
				case "helmets" -> "helmet";
				case "chestplates" -> "chestplate";
				default -> word;
			};
			words.add(normalised);
		}
		while (words.size() > 1 && FILLER.contains(words.get(0))) {
			words.remove(0);
		}
		return words;
	}

	/** The id spellings to try for some words: as written, then singular forms. */
	static List<String> spellings(List<String> words) {
		Set<String> result = new LinkedHashSet<>();
		if (words.isEmpty()) {
			return new ArrayList<>(result);
		}
		String flat = String.join("_", words);
		result.add(flat);

		String last = words.get(words.size() - 1);
		String head = words.size() > 1 ? String.join("_", words.subList(0, words.size() - 1)) + "_" : "";
		for (String singular : singulars(last)) {
			result.add(head + singular);
		}
		return new ArrayList<>(result);
	}

	private static List<String> singulars(String word) {
		List<String> result = new ArrayList<>();
		if (word.length() > 3 && word.endsWith("ies")) {
			result.add(word.substring(0, word.length() - 3) + "y");
		}
		if (word.length() > 3 && word.endsWith("ves")) {
			result.add(word.substring(0, word.length() - 3) + "f");
		}
		if (word.length() > 2 && word.endsWith("es")) {
			result.add(word.substring(0, word.length() - 2));
		}
		if (word.length() > 1 && word.endsWith("s")) {
			result.add(word.substring(0, word.length() - 1));
		}
		return result;
	}

	private static String singularWord(String word) {
		if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
			return word.substring(0, word.length() - 1);
		}
		return word;
	}

	private static String singular(String phrase) {
		int space = phrase.lastIndexOf(' ');
		String head = space < 0 ? "" : phrase.substring(0, space + 1);
		String last = space < 0 ? phrase : phrase.substring(space + 1);
		List<String> options = singulars(last);
		return options.isEmpty() ? phrase : head + options.get(options.size() - 1);
	}

	private static List<String> swapGold(List<String> words) {
		List<String> swapped = new ArrayList<>();
		for (String word : words) {
			swapped.add(switch (word) {
				case "gold" -> "golden";
				case "golden" -> "gold";
				default -> word;
			});
		}
		return swapped;
	}
}
