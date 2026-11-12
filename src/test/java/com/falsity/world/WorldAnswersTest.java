package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The sentences Falsity says about the world, built from values the server read. */
class WorldAnswersTest {
	private static final StructureCatalog.Entry VILLAGE = StructureCatalog.byKey("village").orElseThrow();
	private static final StructureCatalog.Entry FORTRESS = StructureCatalog.byKey("fortress").orElseThrow();
	private static final OreCatalog.Entry DIAMOND = OreCatalog.byKey("diamond").orElseThrow();
	private static final OreCatalog.Entry IRON = OreCatalog.byKey("iron").orElseThrow();

	@Test
	void idsAreSaidAsWords() {
		assertEquals("dark forest", WorldAnswers.words("dark_forest"));
		assertEquals("plains", WorldAnswers.words("Plains"));
		assertEquals("unknown", WorldAnswers.words(null));
		assertEquals("unknown", WorldAnswers.words(" "));
		assertEquals("the Overworld", WorldAnswers.dimensionName("overworld"));
		assertEquals("the Nether", WorldAnswers.dimensionName("the_nether"));
		assertEquals("the End", WorldAnswers.dimensionName("the_end"));
		assertEquals("the twilight forest dimension", WorldAnswers.dimensionName("twilight_forest"));
		assertEquals("an unknown dimension", WorldAnswers.dimensionName(null));
	}

	@Test
	void positionIsExactOrWithheld() {
		assertEquals("We're at x=120, y=64, z=-30 in the Overworld.", WorldAnswers.position(120, 64, -30, "overworld", true));
		assertEquals(
			"We're in the Nether. I'm not allowed to share exact coordinates here.",
			WorldAnswers.position(120, 64, -30, "the_nether", false));
	}

	@Test
	void biomeAndDimension() {
		assertEquals("This is a plains biome.", WorldAnswers.biome("plains"));
		assertEquals("This is an old growth birch forest biome.", WorldAnswers.biome("old_growth_birch_forest"));
		assertEquals("I can't tell which biome this is.", WorldAnswers.biome(""));
		assertEquals("I can't tell which biome this is.", WorldAnswers.biome(null));
		assertEquals("We're in the End.", WorldAnswers.dimension("the_end"));
	}

	@Test
	void theClockStartsAtSixInTheMorning() {
		assertEquals("06:00", WorldAnswers.clock(0));
		assertEquals("12:00", WorldAnswers.clock(6_000));
		assertEquals("18:00", WorldAnswers.clock(12_000));
		assertEquals("00:00", WorldAnswers.clock(18_000));
		assertEquals("05:59", WorldAnswers.clock(23_999));
		assertEquals("12:00", WorldAnswers.clock(24_000 + 6_000), "later days read the same");
		assertEquals("05:59", WorldAnswers.clock(-1));
	}

	@Test
	void thePartsOfTheDay() {
		assertEquals("early morning", WorldAnswers.phase(0));
		assertEquals("early morning", WorldAnswers.phase(999));
		assertEquals("morning", WorldAnswers.phase(1_000));
		assertEquals("midday", WorldAnswers.phase(6_000));
		assertEquals("afternoon", WorldAnswers.phase(6_500));
		assertEquals("dusk", WorldAnswers.phase(12_000));
		assertEquals("night", WorldAnswers.phase(13_000));
		assertEquals("midnight", WorldAnswers.phase(18_000));
		assertEquals("late night", WorldAnswers.phase(18_500));
		assertEquals("dawn", WorldAnswers.phase(23_000));
		assertEquals("dawn", WorldAnswers.phase(-1));
		assertEquals("midday", WorldAnswers.phase(5 * 24_000L + 6_000));
	}

	@Test
	void durationsAreInRealMinutes() {
		assertEquals("less than a minute", WorldAnswers.duration(0));
		assertEquals("less than a minute", WorldAnswers.duration(1_199));
		assertEquals("about 1 minute", WorldAnswers.duration(1_200));
		assertEquals("about 2 minutes", WorldAnswers.duration(2_400));
		assertEquals("about 10 minutes", WorldAnswers.duration(12_000));
		assertEquals("less than a minute", WorldAnswers.duration(-50));
	}

	@Test
	void theTimeAnswerSaysWhatComesNext() {
		assertEquals(
			"It's midday, about 12:00 game time. Night falls in about 5 minutes.",
			WorldAnswers.time(6_000, false, "overworld"));
		assertEquals(
			"It's night, about 19:00 game time. You can sleep now; morning is about 9 minutes away.",
			WorldAnswers.time(13_000, false, "overworld"));
		assertEquals(
			"It's dawn, about 05:30 game time. The sun is coming up.",
			WorldAnswers.time(23_500, false, "overworld"));
		assertEquals(
			"It's midday, about 12:00 game time. Night falls in about 5 minutes. There's a thunderstorm, so you can sleep now.",
			WorldAnswers.time(6_000, true, "overworld"));
		assertEquals(WorldAnswers.time(6_000, false, "overworld"), WorldAnswers.time(3 * 24_000L + 6_000, false, "overworld"));
	}

	@Test
	void bedsWorkExactlyWhenTheAnswerSaysSo() {
		assertFalse(WorldAnswers.time(WorldAnswers.SLEEP_START - 1, false, "overworld").contains("You can sleep now"));
		assertTrue(WorldAnswers.time(WorldAnswers.SLEEP_START, false, "overworld").contains("You can sleep now"));
		assertTrue(WorldAnswers.time(WorldAnswers.SLEEP_END, false, "overworld").contains("You can sleep now"));
		assertFalse(WorldAnswers.time(WorldAnswers.SLEEP_END + 1, false, "overworld").contains("You can sleep now"));
	}

	@Test
	void theNetherAndTheEndHaveNoTimeOrWeather() {
		assertEquals("There is no day or night in the Nether.", WorldAnswers.time(6_000, false, "the_nether"));
		assertEquals("There is no day or night in the End.", WorldAnswers.time(6_000, false, "the_end"));
		assertEquals("There is no weather in the Nether.", WorldAnswers.weather(true, true, "the_nether"));
	}

	@Test
	void weather() {
		assertEquals("The sky is clear.", WorldAnswers.weather(false, false, "overworld"));
		assertEquals("It's raining (or snowing, where it's cold).", WorldAnswers.weather(true, false, "overworld"));
		assertEquals("There's a thunderstorm.", WorldAnswers.weather(true, true, "overworld"));
	}

	@Test
	void theBlockUnderfoot() {
		assertEquals("You're standing on grass block.", WorldAnswers.blockBelow("grass_block"));
		assertEquals("There's nothing solid right under you.", WorldAnswers.blockBelow("air"));
		assertEquals("There's nothing solid right under you.", WorldAnswers.blockBelow(null));
	}

	@Test
	void spawnIsDescribedFromWhereThePlayerStands() {
		assertEquals(
			"World spawn is about 100 blocks to the east, around x=100, z=0.",
			WorldAnswers.spawn(0, 0, 100, 0, "overworld", true));
		assertEquals("World spawn is about 100 blocks to the east.", WorldAnswers.spawn(0, 0, 100, 0, "overworld", false));
		assertEquals("World spawn is right here, around x=8, z=8.", WorldAnswers.spawn(8, 8, 8, 8, "overworld", true));
		assertEquals("World spawn is in the Overworld, at x=100, z=0.", WorldAnswers.spawn(0, 0, 100, 0, "the_nether", true));
		assertEquals("World spawn is in the Overworld.", WorldAnswers.spawn(0, 0, 100, 0, "the_end", false));
	}

	@Test
	void structureAnswers() {
		assertEquals(
			"The nearest village is about 500 blocks to the north-east, around x=300, z=-400.",
			WorldAnswers.structureFound(VILLAGE, 0, 0, 300, -400, true));
		assertEquals(
			"The nearest village is about 500 blocks to the north-east.",
			WorldAnswers.structureFound(VILLAGE, 0, 0, 300, -400, false));
		assertEquals(
			"The nearest nether fortress is about 64 blocks to the south, around x=10, z=74.",
			WorldAnswers.structureFound(FORTRESS, 10, 10, 10, 74, true));
		assertEquals("I couldn't find a village within 1600 blocks of here.", WorldAnswers.structureNotFound(VILLAGE, 1600));
		assertEquals(
			"A nether fortress is only found in the Nether, and we're in the Overworld.",
			WorldAnswers.structureWrongDimension(FORTRESS, "overworld"));
		assertTrue(WorldAnswers.structureUnknown("a sky castle").contains("villages"));
	}

	@Test
	void oreAnswers() {
		TerrainScanner.OreFind diamonds = new TerrainScanner.OreFind(DIAMOND, 3, new TerrainScanner.Pos(5, 10, 0));
		TerrainScanner.OreFind oneDiamond = new TerrainScanner.OreFind(DIAMOND, 1, new TerrainScanner.Pos(0, 2, 0));
		TerrainScanner.OreFind iron = new TerrainScanner.OreFind(IRON, 7, new TerrainScanner.Pos(8, 12, 0));

		assertEquals("I don't sense any ore within 16 blocks.", WorldAnswers.ores(List.of(), 0, 12, 0, 16, null, true));
		assertEquals("I don't sense any diamond ore within 16 blocks.", WorldAnswers.ores(List.of(), 0, 12, 0, 16, DIAMOND, true));
		assertEquals("I don't sense any ore within 16 blocks.", WorldAnswers.ores(null, 0, 12, 0, 16, null, true));
		assertEquals(
			"I sense diamond ore about 5 blocks to the east, at x=5, y=10, z=0. There are 3 blocks of it within 16 blocks.",
			WorldAnswers.ores(List.of(diamonds), 0, 12, 0, 16, DIAMOND, true));
		assertEquals(
			"I sense diamond ore right here, 10 blocks down, at x=0, y=2, z=0. That's the only block of it within 16 blocks.",
			WorldAnswers.ores(List.of(oneDiamond), 0, 12, 0, 16, DIAMOND, true));
		assertEquals(
			"I sense diamond ore about 5 blocks to the east. There are 3 blocks of it within 16 blocks.",
			WorldAnswers.ores(List.of(diamonds), 0, 12, 0, 16, DIAMOND, false));
		assertEquals(
			"Nearest ores: diamond ore about 5 blocks to the east, at x=5, y=10, z=0; iron ore about 8 blocks to the east, at x=8, y=12, z=0.",
			WorldAnswers.ores(List.of(diamonds, iron), 0, 12, 0, 16, null, true));
	}

	@Test
	void aLongOreListIsCutToTheNearestFour() {
		List<TerrainScanner.OreFind> finds = List.of(
			new TerrainScanner.OreFind(OreCatalog.byKey("coal").orElseThrow(), 1, new TerrainScanner.Pos(2, 12, 0)),
			new TerrainScanner.OreFind(IRON, 1, new TerrainScanner.Pos(3, 12, 0)),
			new TerrainScanner.OreFind(OreCatalog.byKey("copper").orElseThrow(), 1, new TerrainScanner.Pos(4, 12, 0)),
			new TerrainScanner.OreFind(OreCatalog.byKey("gold").orElseThrow(), 1, new TerrainScanner.Pos(5, 12, 0)),
			new TerrainScanner.OreFind(DIAMOND, 1, new TerrainScanner.Pos(6, 12, 0)));

		String answer = WorldAnswers.ores(finds, 0, 12, 0, 16, null, false);

		assertTrue(answer.contains("coal ore") && answer.contains("gold ore"), answer);
		assertFalse(answer.contains("diamond ore"), "only the four nearest are listed: " + answer);
	}

	@Test
	void caveAnswers() {
		TerrainScanner.CaveFind room = new TerrainScanner.CaveFind(new TerrainScanner.Pos(4, 21, 4), 48, false);
		TerrainScanner.CaveFind cavern = new TerrainScanner.CaveFind(new TerrainScanner.Pos(0, 5, -20), 400, true);

		assertEquals("I don't sense a cave within 16 blocks.", WorldAnswers.cave(Optional.empty(), 0, 21, 0, 16, true));
		assertEquals("I don't sense a cave within 16 blocks.", WorldAnswers.cave(null, 0, 21, 0, 16, true));
		assertEquals(
			"There's a cave about 6 blocks to the south-east, at x=4, y=21, z=4: an open space of about 48 blocks.",
			WorldAnswers.cave(Optional.of(room), 0, 21, 0, 16, true));
		assertEquals(
			"There's a cave about 20 blocks to the north and 16 blocks down: a large open space.",
			WorldAnswers.cave(Optional.of(cavern), 0, 21, 0, 32, false));
	}

	@Test
	void surroundings() {
		assertEquals(
			"We're in a plains biome. It's morning. The sky is clear. I don't sense anything hostile close by.",
			WorldAnswers.surroundings("plains", "It's morning.", "The sky is clear.", 0));
		assertEquals(
			"We're in an ice spikes biome. I sense 1 hostile mob close by.",
			WorldAnswers.surroundings("ice_spikes", "", null, 1));
		assertEquals("I sense 3 hostile mobs close by.", WorldAnswers.surroundings(null, null, null, 3));
	}

	@Test
	void theFactsLineForTheModel() {
		assertEquals(
			"You and the player are in the Overworld at x=120, y=64, z=-30, biome plains. It is midday (12:00), clear sky.",
			WorldAnswers.facts(120, 64, -30, "overworld", "plains", 6_000, false, false, true));
		assertEquals(
			"You and the player are in the Overworld, biome dark forest. It is night (19:00), thunderstorm.",
			WorldAnswers.facts(120, 64, -30, "overworld", "dark_forest", 13_000, true, true, false));
		assertEquals(
			"You and the player are in the Nether at x=1, y=2, z=3, biome nether wastes.",
			WorldAnswers.facts(1, 2, 3, "the_nether", "nether_wastes", 6_000, false, false, true));
		assertEquals(
			"You and the player are in the Overworld at x=0, y=70, z=0. It is morning (08:00), raining.",
			WorldAnswers.facts(0, 70, 0, "overworld", "", 2_000, true, false, true));
	}

	@Test
	void withheldCoordinatesAppearNowhere() {
		List<String> answers = List.of(
			WorldAnswers.position(120, 64, -30, "overworld", false),
			WorldAnswers.spawn(0, 0, 100, 0, "overworld", false),
			WorldAnswers.spawn(0, 0, 100, 0, "the_nether", false),
			WorldAnswers.structureFound(VILLAGE, 0, 0, 300, -400, false),
			WorldAnswers.ores(List.of(new TerrainScanner.OreFind(DIAMOND, 3, new TerrainScanner.Pos(5, 10, 0))), 0, 12, 0, 16, DIAMOND, false),
			WorldAnswers.cave(Optional.of(new TerrainScanner.CaveFind(new TerrainScanner.Pos(4, 21, 4), 48, false)), 0, 21, 0, 16, false),
			WorldAnswers.facts(120, 64, -30, "overworld", "plains", 6_000, false, false, false));
		for (String answer : answers) {
			assertFalse(answer.contains("x=") || answer.contains("y=") || answer.contains("z="), answer);
		}
	}

	@Test
	void whatFalsitySaysItselfIsBackedByTheFactsLine() {
		String facts = WorldAnswers.facts(120, 64, -30, "overworld", "plains", 6_000, false, false, true);

		assertFalse(FactGuard.hasUnverifiedLocation(WorldAnswers.position(120, 64, -30, "overworld", true), facts));
		assertTrue(FactGuard.hasUnverifiedLocation(WorldAnswers.position(121, 64, -30, "overworld", true), facts),
			"a position one block off is not the verified one");
	}
}
