package com.falsity.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ores Falsity can look for nearby, and the words players use for them.
 *
 * <p>Like {@link StructureCatalog}, this is a closed list. Each entry names the
 * exact vanilla block ids that count, so a report of "diamond ore" means one of
 * those blocks was actually found.
 */
public final class OreCatalog {
	/**
	 * One kind of ore.
	 *
	 * @param key      stable short name ("diamond")
	 * @param label    how to say it ("diamond ore")
	 * @param blockIds vanilla block id paths that count as this ore
	 * @param aliases  player wording that means this ore
	 */
	public record Entry(String key, String label, List<String> blockIds, List<String> aliases) {
	}

	/**
	 * Where a piece of text mentions an ore.
	 *
	 * @param entry  the ore
	 * @param alias  the wording that matched
	 * @param before the words in front of it, lower-case, for context checks
	 */
	public record Match(Entry entry, String alias, String before) {
	}

	/**
	 * Words that turn a material into a made thing. "Iron" is an ore to look
	 * for; "iron pickaxe", "iron door" and "iron golem" are not.
	 */
	private static final Set<String> MADE_THINGS = Set.of(
		"axe", "pickaxe", "pick", "shovel", "hoe", "sword", "spear", "helmet", "chestplate", "leggings", "boots", "armor",
		"armour", "tool", "tools", "gear", "set", "horse", "nautilus", "block", "blocks", "ingot", "ingots", "nugget",
		"nuggets", "bars", "chain", "chains", "door", "doors", "trapdoor", "trapdoors", "golem", "golems", "apple",
		"apples", "carrot", "carrots", "bulb", "chest", "grate", "lantern", "torch", "torches", "wall", "lamp", "lamps",
		"wire", "dust", "repeater", "comparator", "bricks", "pillar", "slab", "slabs", "stairs", "scrap", "upgrade",
		"farm", "farms", "shears", "bucket", "rod", "plate", "rail", "rails"
	);

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

	static {
		add("diamond", "diamond ore", List.of("diamond_ore", "deepslate_diamond_ore"), "diamond", "diamonds");
		add("iron", "iron ore", List.of("iron_ore", "deepslate_iron_ore", "raw_iron_block"), "iron");
		add("gold", "gold ore", List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore", "raw_gold_block"), "gold");
		add("coal", "coal ore", List.of("coal_ore", "deepslate_coal_ore"), "coal");
		add("copper", "copper ore", List.of("copper_ore", "deepslate_copper_ore", "raw_copper_block"), "copper");
		add("redstone", "redstone ore", List.of("redstone_ore", "deepslate_redstone_ore"), "redstone");
		add("lapis", "lapis ore", List.of("lapis_ore", "deepslate_lapis_ore"), "lapis", "lapis lazuli");
		add("emerald", "emerald ore", List.of("emerald_ore", "deepslate_emerald_ore"), "emerald", "emeralds");
		add("quartz", "nether quartz ore", List.of("nether_quartz_ore"), "quartz", "nether quartz");
		add("ancient_debris", "ancient debris", List.of("ancient_debris"), "ancient debris", "netherite", "debris");
	}

	private OreCatalog() {
	}

	private static void add(String key, String label, List<String> blockIds, String... aliases) {
		ENTRIES.put(key, new Entry(key, label, List.copyOf(blockIds), List.of(aliases)));
	}

	/** Every ore Falsity can look for. */
	public static List<Entry> all() {
		return List.copyOf(ENTRIES.values());
	}

	/** Looks an entry up by its stable key. */
	public static Optional<Entry> byKey(String key) {
		if (key == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(ENTRIES.get(key.trim().toLowerCase(Locale.ROOT)));
	}

	/** The ore a block id belongs to, if it is one. */
	public static Optional<Entry> byBlockId(String blockId) {
		if (blockId == null) {
			return Optional.empty();
		}
		for (Entry entry : ENTRIES.values()) {
			if (entry.blockIds().contains(blockId)) {
				return Optional.of(entry);
			}
		}
		return Optional.empty();
	}

	/** Finds the ore a piece of text talks about. Whole words, longest wording first. */
	public static Optional<Entry> find(String text) {
		return match(text).map(Match::entry);
	}

	/**
	 * Finds where a piece of text mentions an ore, skipping made things: in
	 * "where is my iron pickaxe" nothing is an ore.
	 */
	public static Optional<Match> match(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String lower = " " + text.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
		Match best = null;
		for (Entry entry : ENTRIES.values()) {
			for (String alias : entry.aliases()) {
				if (best != null && alias.length() <= best.alias().length()) {
					continue;
				}
				String needle = " " + alias + " ";
				int from = 0;
				while (true) {
					int index = lower.indexOf(needle, from);
					if (index < 0) {
						break;
					}
					String after = lower.substring(index + needle.length());
					int space = after.indexOf(' ');
					String next = space < 0 ? after : after.substring(0, space);
					if (!MADE_THINGS.contains(next)) {
						best = new Match(entry, alias, lower.substring(0, index).trim());
						break;
					}
					from = index + 1;
				}
			}
		}
		return Optional.ofNullable(best);
	}
}
