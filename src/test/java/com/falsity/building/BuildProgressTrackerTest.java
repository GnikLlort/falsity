package com.falsity.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Progress wording: sparse, honest and human-readable. */
class BuildProgressTrackerTest {
	@Test
	void progressIsPlacedOfTotal() {
		assertEquals("5 of 20", BuildProgressTracker.progress(5, 20));
	}

	@Test
	void startedLineNamesTheStructure() {
		assertEquals("I've started building your house.", BuildProgressTracker.started("house"));
	}

	@Test
	void completedLineIsFriendly() {
		assertEquals("I've finished your castle. What do you think?", BuildProgressTracker.completed("castle"));
	}

	@Test
	void cancelledLineStopsTheBuild() {
		assertEquals("I've stopped building your wall.", BuildProgressTracker.cancelled("wall"));
	}

	@Test
	void failedLineReportsHonestly() {
		String line = BuildProgressTracker.failed("bridge", 7, 30);
		assertTrue(line.contains("bridge"));
		assertTrue(line.contains("7 of 30"));
		assertTrue(line.contains("trouble"), "failure is never reported as success");
	}
}
