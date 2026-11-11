package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the model is told about danger: only what the server counted, and
 * nothing at all when there is nothing to worry about.
 */
class WorldDangerFactsTest {
	@Test
	void anOrdinaryMomentAddsNothing() {
		assertEquals("", WorldAnswers.danger(20.0F, 20.0F, List.of(), 16));
		assertEquals("", WorldAnswers.danger(9.0F, 20.0F, List.of(), 16), "a little under half is not yet low");
		assertEquals("", WorldAnswers.danger(20.0F, 20.0F, null, 16));
	}

	@Test
	void lowHealthIsStatedInHearts() {
		assertEquals(" The player is hurt: 3 of 10 hearts left.", WorldAnswers.danger(6.0F, 20.0F, List.of(), 16));
		assertEquals(" The player is hurt: 4 of 10 hearts left.", WorldAnswers.danger(8.0F, 20.0F, List.of(), 16), "exactly at the line counts");
		assertEquals(" The player is hurt: 3.5 of 10 hearts left.", WorldAnswers.danger(7.0F, 20.0F, List.of(), 16));
		assertEquals(" The player is hurt: 0.5 of 10 hearts left.", WorldAnswers.danger(0.2F, 20.0F, List.of(), 16), "never shown as no hearts while alive");
		assertEquals(" The player is hurt: 2 of 20 hearts left.", WorldAnswers.danger(4.0F, 40.0F, List.of(), 16), "a raised maximum is respected");
	}

	@Test
	void aDeadOrNonsensicalReadingSaysNothing() {
		assertEquals("", WorldAnswers.danger(0.0F, 20.0F, List.of(), 16));
		assertEquals("", WorldAnswers.danger(-3.0F, 20.0F, List.of(), 16));
		assertEquals("", WorldAnswers.danger(5.0F, 0.0F, List.of(), 16));
		assertEquals("", WorldAnswers.danger(Float.NaN, 20.0F, List.of(), 16));
	}

	@Test
	void hostileMobsAreCountedAndNamedNearestFirst() {
		assertEquals(" Hostile mobs within 16 blocks: 1 (creeper).", WorldAnswers.danger(20.0F, 20.0F, List.of("creeper"), 16));
		assertEquals(" Hostile mobs within 16 blocks: 3 (creeper, zombie).",
			WorldAnswers.danger(20.0F, 20.0F, List.of("creeper", "zombie", "zombie"), 16), "each kind is named once, all are counted");
		assertEquals(" Hostile mobs within 24 blocks: 2 (zombie villager, cave spider).",
			WorldAnswers.danger(20.0F, 20.0F, List.of("zombie_villager", "cave_spider"), 24), "ids are turned into words");
	}

	@Test
	void manyKindsOfMobAreNotAllListed() {
		List<String> crowd = new ArrayList<>(List.of("creeper", "zombie", "skeleton", "spider", "witch", "enderman", "slime"));

		String facts = WorldAnswers.danger(20.0F, 20.0F, crowd, 16);

		assertEquals(" Hostile mobs within 16 blocks: 7 (creeper, zombie, skeleton, spider).", facts);
		assertFalse(facts.contains("witch"), facts);
	}

	@Test
	void aMobWithNoNameIsStillCounted() {
		List<String> mobs = new ArrayList<>();
		mobs.add(null);
		mobs.add(" ");
		mobs.add("creeper");

		assertEquals(" Hostile mobs within 16 blocks: 3 (hostile mob, creeper).", WorldAnswers.danger(20.0F, 20.0F, mobs, 16));
	}

	@Test
	void bothAreStatedTogetherAndFollowTheOtherFacts() {
		String danger = WorldAnswers.danger(4.0F, 20.0F, List.of("creeper"), 16);

		assertEquals(" The player is hurt: 2 of 10 hearts left. Hostile mobs within 16 blocks: 1 (creeper).", danger);
		String facts = WorldAnswers.facts(1, 64, 2, "overworld", "plains", 6000L, false, false, true) + danger;
		assertTrue(facts.startsWith("You and the player are in "), facts);
		assertTrue(facts.endsWith("Hostile mobs within 16 blocks: 1 (creeper)."), facts);
		assertFalse(facts.contains("  "), "one space between sentences: " + facts);
	}

	@Test
	void aReplyThatRepeatsTheseNumbersIsNotMistakenForAnInventedLocation() {
		String facts = WorldAnswers.facts(1, 64, 2, "overworld", "plains", 6000L, false, false, true)
			+ WorldAnswers.danger(6.0F, 20.0F, List.of("creeper", "zombie"), 16);

		assertFalse(FactGuard.hasUnverifiedLocation("Careful. There are 2 mobs within 16 blocks of us, and you only have 3 hearts.", facts));
		assertFalse(FactGuard.hasUnverifiedLocation("A creeper is close. Stay behind me.", facts));
		assertTrue(FactGuard.hasUnverifiedLocation("There is a creeper 40 blocks north of here.", facts), "a distance the server never gave is still caught");
	}
}
