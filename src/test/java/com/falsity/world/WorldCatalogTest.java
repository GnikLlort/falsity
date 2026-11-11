package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.testing.VanillaIds;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The structures and ores Falsity can look for, checked against the real vanilla ids. */
class WorldCatalogTest {
	@Test
	void everyStructureIdIsARealStructure() {
		for (StructureCatalog.Entry entry : StructureCatalog.all()) {
			assertFalse(entry.ids().isEmpty(), entry.key());
			for (String id : entry.ids()) {
				assertTrue(VanillaIds.structures().contains(id), entry.key() + " lists '" + id + "', which is not a vanilla structure");
			}
		}
	}

	@Test
	void everyVanillaStructureCanBeSearchedFor() {
		Set<String> uncovered = new TreeSet<>(VanillaIds.structures());
		for (StructureCatalog.Entry entry : StructureCatalog.all()) {
			uncovered.removeAll(entry.ids());
		}
		assertTrue(uncovered.isEmpty(), "No catalog entry finds: " + uncovered);
	}

	@Test
	void structuresAreFoundByTheNamesPlayersUse() {
		assertEquals("village", key("where is the nearest village"));
		assertEquals("monument", key("ocean monument"));
		assertEquals("desert_pyramid", key("is there a desert temple around"));
		assertEquals("jungle_pyramid", key("jungle temple"));
		assertEquals("fortress", key("a Nether Fortress"));
		assertEquals("swamp_hut", key("witch hut"));
		assertEquals("stronghold", key("the end portal"));
		assertEquals("end_city", key("end city"));
		assertEquals("mansion", key("woodland mansion"));
		assertEquals("ancient_city", key("ancient_city"));
		assertEquals("trial_chambers", key("trial chambers"));
		assertEquals("mineshaft", key("abandoned mineshaft"));
		assertEquals("ruined_portal", key("ruined portal"));
	}

	private static String key(String text) {
		return StructureCatalog.find(text).map(StructureCatalog.Entry::key).orElse("");
	}

	@Test
	void theLongestWordingWins() {
		assertEquals("pillager_outpost", key("pillager outpost"));
		assertEquals("ocean_ruin", key("underwater ruins"));
		assertEquals("bastion_remnant", key("piglin bastion"));
	}

	@Test
	void wordsThatOnlyLookSimilarAreNotStructures() {
		assertEquals("", key("i like turtles"));
		assertEquals("", key("campfire"));
		assertEquals("", key("villager trade"));
		assertEquals("", key("elytra"), "an elytra is an item to hand over, not a place to search for");
		assertEquals("", key(""));
		assertEquals("", key(null));
	}

	@Test
	void structuresKnowTheirDimension() {
		assertEquals("overworld", StructureCatalog.byKey("village").orElseThrow().dimension());
		assertEquals("the_nether", StructureCatalog.byKey("fortress").orElseThrow().dimension());
		assertEquals("the_end", StructureCatalog.byKey("end_city").orElseThrow().dimension());
		assertEquals("", StructureCatalog.byKey("ruined_portal").orElseThrow().dimension(), "ruined portals are in more than one dimension");
		assertTrue(StructureCatalog.byKey("VILLAGE ").isPresent());
		assertTrue(StructureCatalog.byKey("nonsense").isEmpty());
		assertTrue(StructureCatalog.byKey(null).isEmpty());
	}

	@Test
	void aStructureMatchReportsWhatCameBeforeIt() {
		StructureCatalog.Match match = StructureCatalog.match("Where is our village?").orElseThrow();
		assertEquals("village", match.entry().key());
		assertEquals("village", match.alias());
		assertEquals("where is our", match.before());
	}

	@Test
	void everyOreBlockIsARealBlock() {
		for (OreCatalog.Entry entry : OreCatalog.all()) {
			for (String id : entry.blockIds()) {
				assertTrue(VanillaIds.blocks().contains(id), entry.key() + " lists '" + id + "', which is not a vanilla block");
			}
		}
	}

	@Test
	void everyVanillaOreIsKnown() {
		for (String block : VanillaIds.blocks()) {
			if (block.endsWith("_ore")) {
				assertTrue(OreCatalog.byBlockId(block).isPresent(), "No catalog entry for " + block);
			}
		}
		assertTrue(OreCatalog.byBlockId("ancient_debris").isPresent());
		assertTrue(OreCatalog.byBlockId("stone").isEmpty());
		assertTrue(OreCatalog.byBlockId(null).isEmpty());
	}

	@Test
	void oresAreFoundByTheNamesPlayersUse() {
		assertEquals("diamond", ore("any diamonds nearby"));
		assertEquals("iron", ore("where can I find iron"));
		assertEquals("lapis", ore("lapis lazuli"));
		assertEquals("ancient_debris", ore("netherite"));
		assertEquals("redstone", ore("is there redstone down here"));
		assertEquals("diamond", OreCatalog.byBlockId("deepslate_diamond_ore").orElseThrow().key());
		assertEquals("gold", OreCatalog.byBlockId("nether_gold_ore").orElseThrow().key());
	}

	private static String ore(String text) {
		return OreCatalog.find(text).map(OreCatalog.Entry::key).orElse("");
	}

	@Test
	void aMadeThingIsNotAnOre() {
		assertEquals("", ore("where is my iron pickaxe"));
		assertEquals("", ore("an iron golem"));
		assertEquals("", ore("iron door"));
		assertEquals("", ore("diamond sword"));
		assertEquals("", ore("redstone torch"));
		assertEquals("", ore("gold ingots"));
		assertEquals("iron", ore("iron ore"));
		assertEquals("iron", ore("my iron pickaxe broke, any iron nearby"), "a later plain mention still counts");
	}

	@Test
	void anOreMatchReportsWhatCameBeforeIt() {
		Optional<OreCatalog.Match> match = OreCatalog.match("Where did I put my diamonds?");
		assertTrue(match.isPresent());
		assertEquals("diamonds", match.get().alias());
		assertEquals("where did i put my", match.get().before());
	}
}
