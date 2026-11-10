package com.falsity.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String lower = " " + text.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
		Entry best = null;
		int bestLength = 0;
		for (Entry entry : ENTRIES.values()) {
			for (String alias : entry.aliases()) {
				if (alias.length() > bestLength && lower.contains(" " + alias + " ")) {
					best = entry;
					bestLength = alias.length();
				}
			}
		}
		return Optional.ofNullable(best);
	}
}
