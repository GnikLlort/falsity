package com.falsity.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The structures Falsity can look for, and the words players use for them.
 *
 * <p>Each entry maps everyday wording ("village", "nether fortress", "ocean
 * monument") to the vanilla structure ids the server should search for. Only
 * names listed here are ever searched: a structure Falsity has no entry for is
 * answered with "I do not know how to find that", never with a guess.
 */
public final class StructureCatalog {
	/**
	 * One findable structure.
	 *
	 * @param key        stable short name, also accepted from the model
	 * @param label      how to say it ("a village")
	 * @param dimension  the dimension id path it generates in ("overworld", "the_nether", "the_end"),
	 *                   or empty when it generates in more than one
	 * @param ids        vanilla structure id paths, any of which counts
	 * @param aliases    player wording that means this structure
	 */
	public record Entry(String key, String label, String dimension, List<String> ids, List<String> aliases) {
	}

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
	private static final List<Map.Entry<String, Entry>> ALIASES_LONGEST_FIRST = new ArrayList<>();

	static {
		add("village", "a village", "overworld",
			List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"),
			"village", "villages", "villagers", "town", "settlement");
		add("pillager_outpost", "a pillager outpost", "overworld", List.of("pillager_outpost"),
			"pillager outpost", "outpost", "pillager tower");
		add("mansion", "a woodland mansion", "overworld", List.of("mansion"),
			"woodland mansion", "mansion");
		add("monument", "an ocean monument", "overworld", List.of("monument"),
			"ocean monument", "monument", "guardian temple", "water temple", "sea temple");
		add("stronghold", "a stronghold", "overworld", List.of("stronghold"),
			"stronghold", "end portal", "portal room");
		add("desert_pyramid", "a desert pyramid", "overworld", List.of("desert_pyramid"),
			"desert pyramid", "desert temple", "pyramid", "sand temple");
		add("jungle_pyramid", "a jungle temple", "overworld", List.of("jungle_pyramid"),
			"jungle temple", "jungle pyramid");
		add("igloo", "an igloo", "overworld", List.of("igloo"), "igloo");
		add("swamp_hut", "a witch hut", "overworld", List.of("swamp_hut"),
			"witch hut", "swamp hut", "witch house", "witches hut");
		add("shipwreck", "a shipwreck", "overworld", List.of("shipwreck", "shipwreck_beached"),
			"shipwreck", "ship wreck", "sunken ship", "wrecked ship");
		add("ocean_ruin", "an ocean ruin", "overworld", List.of("ocean_ruin_cold", "ocean_ruin_warm"),
			"ocean ruin", "ocean ruins", "underwater ruin", "underwater ruins");
		add("buried_treasure", "buried treasure", "overworld", List.of("buried_treasure"),
			"buried treasure", "treasure chest", "treasure");
		add("ruined_portal", "a ruined portal", "",
			List.of("ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
				"ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_nether"),
			"ruined portal", "broken portal", "ruined nether portal");
		add("mineshaft", "a mineshaft", "overworld", List.of("mineshaft", "mineshaft_mesa"),
			"mineshaft", "mine shaft", "abandoned mineshaft", "abandoned mine");
		add("ancient_city", "an ancient city", "overworld", List.of("ancient_city"),
			"ancient city", "warden city", "deep dark city");
		add("trail_ruins", "trail ruins", "overworld", List.of("trail_ruins"),
			"trail ruins", "trail ruin");
		add("trial_chambers", "a trial chamber", "overworld", List.of("trial_chambers"),
			"trial chamber", "trial chambers", "trial spawner");
		add("abandoned_camp", "an abandoned camp", "overworld",
			List.of("abandoned_camp_bamboo_jungle", "abandoned_camp_birch_forest", "abandoned_camp_cherry_grove",
				"abandoned_camp_dappled_forest", "abandoned_camp_flower_forest", "abandoned_camp_forest",
				"abandoned_camp_meadow", "abandoned_camp_old_growth_birch_forest", "abandoned_camp_old_growth_pine_taiga",
				"abandoned_camp_old_growth_spruce_taiga", "abandoned_camp_pale_garden", "abandoned_camp_savanna",
				"abandoned_camp_snowy_taiga", "abandoned_camp_sparse_jungle", "abandoned_camp_swamp",
				"abandoned_camp_taiga", "abandoned_camp_windswept_forest", "abandoned_camp_wooded_badlands"),
			"abandoned camp", "abandoned camps", "camp", "campsite");
		add("fortress", "a nether fortress", "the_nether", List.of("fortress"),
			"nether fortress", "fortress", "blaze spawner");
		add("bastion_remnant", "a bastion", "the_nether", List.of("bastion_remnant"),
			"bastion remnant", "bastion", "piglin bastion");
		add("nether_fossil", "a nether fossil", "the_nether", List.of("nether_fossil"),
			"nether fossil");
		add("end_city", "an end city", "the_end", List.of("end_city"),
			"end city", "end ship", "elytra");

		for (Entry entry : ENTRIES.values()) {
			for (String alias : entry.aliases()) {
				ALIASES_LONGEST_FIRST.add(Map.entry(alias, entry));
			}
		}
		ALIASES_LONGEST_FIRST.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
	}

	private StructureCatalog() {
	}

	private static void add(String key, String label, String dimension, List<String> ids, String... aliases) {
		ENTRIES.put(key, new Entry(key, label, dimension, List.copyOf(ids), List.of(aliases)));
	}

	/** Every structure Falsity can search for. */
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

	/**
	 * Finds the structure a piece of text talks about, preferring the longest
	 * wording so "ocean monument" is not read as something shorter. Matches
	 * whole words only.
	 */
	public static Optional<Entry> find(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String lower = " " + text.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
		Optional<Entry> direct = byKey(lower.trim().replace(' ', '_'));
		if (direct.isPresent()) {
			return direct;
		}
		for (Map.Entry<String, Entry> alias : ALIASES_LONGEST_FIRST) {
			if (lower.contains(" " + alias.getKey() + " ")) {
				return Optional.of(alias.getValue());
			}
		}
		return Optional.empty();
	}
}
