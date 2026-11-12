package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A reply may only quote a position or distance that the server supplied for the same message. */
class FactGuardTest {
	private static final String FACTS = "You and the player are in the Overworld at x=120, y=64, z=-30, biome plains.";

	private static void assertInvented(String reply) {
		assertTrue(FactGuard.hasUnverifiedLocation(reply, ""), "should be stopped: " + reply);
	}

	private static void assertHarmless(String reply) {
		assertFalse(FactGuard.hasUnverifiedLocation(reply, ""), "should be let through: " + reply);
	}

	@Test
	void inventedCoordinatesAreStopped() {
		for (String reply : List.of(
			"The village is at 120, 64, -30.",
			"Head to (300, -450) and you will find it.",
			"It's at x=512, z=-88.",
			"Try X: 100 Y: 12 Z: 40.",
			"Go to x 250 z 90.",
			"The stronghold is at 1200 / 30 / -800.",
			"The coordinates 100 64 200 should do.",
			"It is located at -340 72 1190."
		)) {
			assertInvented(reply);
		}
	}

	@Test
	void inventedDistancesAndBearingsAreStopped() {
		for (String reply : List.of(
			"The village is about 200 blocks north of here.",
			"There's a cave 30 blocks away.",
			"Diamonds are 12 blocks down.",
			"It's 3 chunks to the east.",
			"Dig 40 blocks straight down.",
			"There is iron 15 blocks below us.",
			"The fortress is 500 meters from here."
		)) {
			assertInvented(reply);
		}
	}

	@Test
	void inventedHeightsAreStopped() {
		assertInvented("Diamonds are at Y level -59.");
		assertInvented("Dig down to y-level 11.");
		assertInvented("Try layer 12.");
		assertInvented("We are at height 70.");
	}

	@Test
	void ordinaryNumbersAreLetThrough() {
		for (String reply : List.of(
			"I have 3 buttons for eyes.",
			"I'm with you 24/7.",
			"That build is a 10/10.",
			"It's 50/50 whether the creeper noticed us.",
			"I waited 1,000 years for a friend.",
			"There are 12,500 stitches in me. I counted.",
			"You need level 30 for the best enchantments.",
			"Here are 64 blocks of cobblestone. Hurry up!",
			"I gave you torches x32.",
			"I can carry 5 stacks, so calm down.",
			"You slept for 8 hours.",
			"That's 2 creepers and 1 zombie.",
			"Ready? 1, 2, 3, jump!",
			"3, 2, 1... boo!",
			"Day 3 in this world, and I still like you."
		)) {
			assertHarmless(reply);
		}
	}

	@Test
	void aBareListOfNumbersIsTreatedAsAPosition() {
		assertInvented("Try 100, 200.");
		assertInvented("Maybe 12, 40, 7?");
		assertInvented("Look around (120,640).");
		assertInvented("It's near -5, -6.");
	}

	@Test
	void numbersTheServerSuppliedMayBeRepeated() {
		assertFalse(FactGuard.hasUnverifiedLocation("We're at x=120, y=64, z=-30.", FACTS));
		assertFalse(FactGuard.hasUnverifiedLocation("We are at 120, 64, -30 in the plains.", FACTS));
		assertFalse(FactGuard.hasUnverifiedLocation("You're at height 64.", FACTS));
		assertFalse(FactGuard.hasUnverifiedLocation("Spawn is 30 blocks north.", FACTS), "the size of a verified number may be quoted without its sign");
	}

	@Test
	void oneWrongNumberSpoilsTheClaim() {
		assertTrue(FactGuard.hasUnverifiedLocation("We're at x=120, y=64, z=-31.", FACTS));
		assertTrue(FactGuard.hasUnverifiedLocation("We are at 120, 65, -30.", FACTS));
		assertTrue(FactGuard.hasUnverifiedLocation("The village is 200 blocks north.", FACTS));
	}

	@Test
	void factsWithSeparatorsStillCount() {
		String facts = "The nearest village is about 1,240 blocks to the north, around x=3,100, z=-1,240.";

		assertFalse(FactGuard.hasUnverifiedLocation("The village is 1240 blocks north, around x=3100, z=-1240.", facts));
		assertFalse(FactGuard.hasUnverifiedLocation("The village is 1,240 blocks north.", facts));
		assertTrue(FactGuard.hasUnverifiedLocation("The village is 1,250 blocks north.", facts));
	}

	@Test
	void nothingSaidIsNothingInvented() {
		assertFalse(FactGuard.hasUnverifiedLocation("", FACTS));
		assertFalse(FactGuard.hasUnverifiedLocation(null, FACTS));
		assertFalse(FactGuard.hasUnverifiedLocation("   ", ""));
		assertTrue(FactGuard.hasUnverifiedLocation("It's at 5, 60, 7.", null), "no facts means nothing is verified");
	}

	@Test
	void theHonestReplyStatesNoNumbers() {
		String honest = FactGuard.honestReply();

		assertFalse(honest.isBlank());
		assertFalse(honest.matches(".*\\d.*"), "the replacement must not contain a number itself");
		assertFalse(FactGuard.hasUnverifiedLocation(honest, ""));
	}
}
