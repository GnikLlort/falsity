package com.falsity.horror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Horror progression: slow, capped, and never hostile in this milestone. */
class HorrorProgressionTest {
	@Test
	void newRelationshipsStartAtInnocence() {
		assertEquals(HorrorStage.INNOCENCE, HorrorProgression.stageForTension(0.0, 1));
		assertEquals(HorrorStage.INNOCENCE, HorrorProgression.stageForTension(5.0, 1));
	}

	@Test
	void dozensOfInteractionsReachStrange() {
		assertEquals(HorrorStage.STRANGE, HorrorProgression.stageForTension(20.0, 1));
	}

	@Test
	void stageIsCappedAtConfiguredMaximum() {
		// Even with enormous tension, this milestone cannot pass Strange.
		assertEquals(HorrorStage.STRANGE, HorrorProgression.stageForTension(10000.0, 1));
		assertEquals(HorrorStage.INNOCENCE, HorrorProgression.stageForTension(10000.0, 0));
	}

	@Test
	void zeroSpeedGivesZeroGain() {
		assertEquals(0.0, HorrorProgression.tensionGainForInteraction(0.0));
		assertTrue(HorrorProgression.tensionGainForInteraction(1.0) > 0.0);
	}

	@Test
	void staringAndUnsettlingOnlyFromStrange() {
		assertFalse(HorrorProgression.allowsStaring(HorrorStage.INNOCENCE));
		assertTrue(HorrorProgression.allowsStaring(HorrorStage.STRANGE));
		assertFalse(HorrorProgression.allowsUnsettlingLines(HorrorStage.INNOCENCE));
		assertTrue(HorrorProgression.allowsUnsettlingLines(HorrorStage.STRANGE));
	}

	@Test
	void stageOrdinalsRoundTrip() {
		for (HorrorStage stage : HorrorStage.values()) {
			assertEquals(stage, HorrorStage.fromOrdinal(stage.ordinal()));
		}
		assertEquals(HorrorStage.INNOCENCE, HorrorStage.fromOrdinal(-5));
		assertEquals(HorrorStage.NIGHTMARE, HorrorStage.fromOrdinal(99));
	}
}
