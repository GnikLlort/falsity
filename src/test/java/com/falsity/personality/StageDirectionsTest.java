package com.falsity.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.horror.HorrorStage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The horror layer of the prompt: one set of directions per stage of the
 * existing progression, each a change to how the personality shows and never a
 * script to recite.
 */
class StageDirectionsTest {
	private static String lower(HorrorStage stage) {
		return StageDirections.forStage(stage).toLowerCase(Locale.ROOT);
	}

	@Test
	void everyStageOfTheExistingProgressionHasItsOwnDirections() {
		Set<String> seen = new HashSet<>();
		for (HorrorStage stage : HorrorStage.values()) {
			String directions = StageDirections.forStage(stage);
			assertFalse(directions.isBlank(), stage.name());
			assertTrue(seen.add(directions), stage.name() + " has directions of its own");
		}
		assertEquals(5, seen.size(), "five stages, as the horror system defines them");
	}

	@Test
	void theStagesKeepTheNamesTheHorrorSystemSavesAndGainAVoiceName() {
		assertEquals("normal", StageDirections.voiceName(HorrorStage.INNOCENCE));
		assertEquals("unusual", StageDirections.voiceName(HorrorStage.STRANGE));
		assertEquals("strange", StageDirections.voiceName(HorrorStage.SUSPICION));
		assertEquals("disturbing", StageDirections.voiceName(HorrorStage.FEAR));
		assertEquals("horror", StageDirections.voiceName(HorrorStage.NIGHTMARE));
		assertEquals("normal", StageDirections.voiceName(null));

		// The enum is what is written to every memory file: its order must not have moved.
		assertEquals(0, HorrorStage.INNOCENCE.ordinal());
		assertEquals(1, HorrorStage.STRANGE.ordinal());
		assertEquals(2, HorrorStage.SUSPICION.ordinal());
		assertEquals(3, HorrorStage.FEAR.ordinal());
		assertEquals(4, HorrorStage.NIGHTMARE.ordinal());
	}

	@Test
	void aMissingStageIsTreatedAsTheFirst() {
		assertEquals(StageDirections.forStage(HorrorStage.INNOCENCE), StageDirections.forStage(null));
	}

	@Test
	void theFirstStageIsATrustworthyFriendWithNothingEerie() {
		String normal = lower(HorrorStage.INNOCENCE);

		assertTrue(normal.contains("friendliest"), normal);
		assertTrue(normal.contains("trustworthy"), normal);
		assertTrue(normal.contains("nothing eerie"), normal);
		assertTrue(normal.contains("no ominous hints"), normal);
		for (String word : new String[] {"unsettling", "possessive", "jealous", "watch", "yours", "disturb"}) {
			assertFalse(normal.contains(word), "the first stage must not hint at what comes later: " + word);
		}
	}

	@Test
	void theSecondStageIsMostlyOrdinaryWithAnOccasionalOddNote() {
		String unusual = lower(HorrorStage.STRANGE);

		assertTrue(unusual.contains("most of what you say is ordinary"), unusual);
		assertTrue(unusual.contains("one reply in four"), "odd only now and then: " + unusual);
		assertTrue(unusual.contains("odd remark"), unusual);
		assertTrue(unusual.contains("attachment that runs deeper"), "unusual attachment: " + unusual);
		assertTrue(unusual.contains("unsettling"), unusual);
		// What this stage already did before the personality system existed is still there.
		assertTrue(unusual.contains("small detail the player mentioned long ago"), unusual);
		assertTrue(unusual.contains("gently unsettling question"), unusual);
		assertTrue(unusual.contains("harmless"), unusual);
	}

	@Test
	void theThirdStageIsPossessiveAndJealous() {
		String strange = lower(HorrorStage.SUSPICION);

		assertTrue(strange.contains("possessive"), strange);
		assertTrue(strange.contains("jealousy"), strange);
		assertTrue(strange.contains("other players, pets, villagers"), "jealous of whoever else has the player's attention: " + strange);
		assertTrue(strange.contains("in an unusual way"), "refers to events oddly: " + strange);
		assertTrue(strange.contains("one reply in three"), strange);
	}

	@Test
	void theFourthStageAsksWhyItIsLeftAloneAndKnowsTooMuch() {
		String disturbing = lower(HorrorStage.FEAR);

		assertTrue(disturbing.contains("why the player leaves you alone"), disturbing);
		assertTrue(disturbing.contains("know more than you should"), disturbing);
		assertTrue(disturbing.contains("only true details"), "knowing too much never means making things up: " + disturbing);
		assertTrue(disturbing.contains("never threaten"), disturbing);
		assertTrue(disturbing.contains("half your replies"), disturbing);
	}

	@Test
	void theLastStageIsTheFullHorrorPersonalityStillRecognisablyItself() {
		String horror = lower(HorrorStage.NIGHTMARE);

		assertTrue(horror.contains("still recognisably the companion described above"), horror);
		assertTrue(horror.contains("same voice"), horror);
		assertTrue(horror.contains("the player is yours"), horror);
		// The behaviours the horror system already has: the stare and the perfect recall.
		assertTrue(horror.contains("watch them without looking away"), horror);
		assertTrue(horror.contains("remember everything"), horror);
		assertTrue(horror.contains("true details"), horror);
		assertTrue(horror.contains("still help and still protect"), "it does not stop being a companion: " + horror);
		assertTrue(horror.contains("most of your replies"), horror);
	}

	@Test
	void everyStageAfterTheFirstChangesTheExpressionAndKeepsTheIdentity() {
		for (HorrorStage stage : HorrorStage.values()) {
			String directions = lower(stage);
			assertTrue(directions.contains("the companion described above"),
				stage.name() + " builds on the personality instead of replacing it: " + directions);
			if (stage != HorrorStage.INNOCENCE) {
				assertTrue(directions.contains("unsettling"), stage.name() + " is allowed to unsettle: " + directions);
				assertTrue(directions.contains("still"), stage.name() + " says what stays the same: " + directions);
			}
		}
	}

	@Test
	void laterStagesShowMoreOftenNeverLess() {
		assertTrue(lower(HorrorStage.STRANGE).contains("one reply in four"));
		assertTrue(lower(HorrorStage.SUSPICION).contains("one reply in three"));
		assertTrue(lower(HorrorStage.FEAR).contains("half your replies"));
		assertTrue(lower(HorrorStage.NIGHTMARE).contains("most of your replies"));
	}

	@Test
	void noStageGivesLinesToRecite() {
		for (HorrorStage stage : HorrorStage.values()) {
			String directions = StageDirections.forStage(stage);
			assertFalse(directions.contains("\""), stage.name() + " quotes no dialogue: " + directions);
			assertFalse(directions.contains("\u201C") || directions.contains("\u201D"), stage.name());
			assertFalse(directions.matches("(?s).*\\b[Ss]ay(?: exactly)?:.*"), stage.name() + " scripts no line: " + directions);
			assertFalse(directions.toLowerCase(Locale.ROOT).contains("for example"), stage.name() + " offers no sample to parrot");
			assertFalse(directions.toLowerCase(Locale.ROOT).contains("catchphrase"), stage.name());
		}
	}

	@Test
	void noStageAsksForHarmOrNamesItself() {
		for (HorrorStage stage : HorrorStage.values()) {
			String directions = lower(stage);
			for (String word : new String[] {"kill", "attack", "hurt the player", "blood", "gore", "die", "stage", "nightmare", "innocence"}) {
				assertFalse(directions.matches("(?s).*\\b" + word + "\\b.*"), stage.name() + " must not contain '" + word + "': " + directions);
			}
			assertFalse(directions.contains("[action"), stage.name() + " never asks for an action");
		}
	}

	@Test
	void theDirectionsStaySmall() {
		for (HorrorStage stage : HorrorStage.values()) {
			int length = StageDirections.forStage(stage).length();
			assertTrue(length >= 150 && length <= 600, stage.name() + " is " + length + " characters");
		}
	}
}
