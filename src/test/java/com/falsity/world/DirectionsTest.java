package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Compass directions and distances, in Minecraft's axes (north is negative Z, east is positive X). */
class DirectionsTest {
	@Test
	void compassFollowsMinecraftAxes() {
		assertEquals("north", Directions.compass(0, -10));
		assertEquals("north-east", Directions.compass(10, -10));
		assertEquals("east", Directions.compass(10, 0));
		assertEquals("south-east", Directions.compass(10, 10));
		assertEquals("south", Directions.compass(0, 10));
		assertEquals("south-west", Directions.compass(-10, 10));
		assertEquals("west", Directions.compass(-10, 0));
		assertEquals("north-west", Directions.compass(-10, -10));
	}

	@Test
	void nearlyStraightCountsAsStraight() {
		assertEquals("north", Directions.compass(10, -100));
		assertEquals("north", Directions.compass(-10, -100));
		assertEquals("east", Directions.compass(100, 15));
		assertEquals("", Directions.compass(0, 0), "no offset has no direction");
		assertEquals("", Directions.compass(0.2, -0.3));
	}

	@Test
	void distancesAreStraightLines() {
		assertEquals(5, Directions.horizontalDistance(3, 4));
		assertEquals(0, Directions.horizontalDistance(0, 0));
		assertEquals(13, Directions.distance(3, 4, 12));
		assertEquals(141, Directions.horizontalDistance(100, -100));
	}

	@Test
	void describesWhereSomethingIs() {
		assertEquals("right here", Directions.describe(0, 0, 0));
		assertEquals("right here", Directions.describe(1, 0, 0));
		assertEquals("about 100 blocks to the east", Directions.describe(100, 0, 0));
		assertEquals("about 200 blocks to the north", Directions.describe(0, 0, -200));
		assertEquals("about 42 blocks to the north-east and 5 blocks up", Directions.describe(30, 5, -30));
		assertEquals("about 10 blocks to the south and 20 blocks down", Directions.describe(0, -20, 10));
		assertEquals("right here, 10 blocks down", Directions.describe(0, -10, 0));
		assertEquals("about 10 blocks to the west", Directions.describe(-10, 2, 0), "a step or two up or down is not worth mentioning");
	}

	@Test
	void coordinatesAreWrittenTheSameWayEverywhere() {
		assertEquals("x=120, y=64, z=-30", Directions.coordinates(120, 64, -30));
		assertEquals("x=120, z=-30", Directions.coordinates(120, -30));
	}
}
