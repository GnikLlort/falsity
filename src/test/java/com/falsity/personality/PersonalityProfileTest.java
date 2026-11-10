package com.falsity.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.config.FalsityConfig;
import org.junit.jupiter.api.Test;

/** Personality evolution: gradual, clamped, persistent, consistent. */
class PersonalityProfileTest {
	@Test
	void startsFromConfigDefaults() {
		FalsityConfig.Personality config = new FalsityConfig.Personality();
		config.curiosity = 33;
		config.affection = 44;

		PersonalityProfile profile = PersonalityProfile.fromConfig(config);

		assertEquals(33.0, profile.curiosity);
		assertEquals(44.0, profile.affection);
	}

	@Test
	void pettingIncreasesAffectionGradually() {
		PersonalityProfile profile = new PersonalityProfile();
		double before = profile.affection;

		profile.evolve(PersonalityProfile.Event.PETTED, 1.0);

		assertTrue(profile.affection > before, "Petting should warm Falsity slightly");
		assertTrue(profile.affection - before < 5.0, "One pet must not flip personality");
	}

	@Test
	void bondingAttachesStrongly() {
		PersonalityProfile profile = new PersonalityProfile();
		double before = profile.attachment;

		profile.evolve(PersonalityProfile.Event.BONDED, 1.0);

		assertTrue(profile.attachment > before + 5.0);
	}

	@Test
	void valuesNeverLeaveZeroToHundred() {
		PersonalityProfile profile = new PersonalityProfile();
		for (int i = 0; i < 500; i++) {
			profile.evolve(PersonalityProfile.Event.PETTED, 5.0);
			profile.evolve(PersonalityProfile.Event.BONDED, 5.0);
			profile.evolve(PersonalityProfile.Event.STARTLED, 5.0);
		}

		assertTrue(profile.affection <= 100.0);
		assertTrue(profile.attachment <= 100.0);
		assertTrue(profile.fear <= 100.0);
		assertTrue(profile.curiosity >= 0.0);
	}

	@Test
	void zeroEvolutionRateFreezesPersonality() {
		PersonalityProfile profile = new PersonalityProfile();
		double affection = profile.affection;

		profile.evolve(PersonalityProfile.Event.PETTED, 0.0);

		assertEquals(affection, profile.affection);
	}

	@Test
	void describeIsStableForSameValues() {
		PersonalityProfile profile = new PersonalityProfile();
		String first = profile.describe();
		String second = profile.describe();

		assertEquals(first, second, "The same personality must describe itself the same way every time");
		assertTrue(first.contains("curious"), "Default curiosity should show in the description, was: " + first);
	}

	@Test
	void dominantTraitsAreStrongestFirst() {
		PersonalityProfile profile = new PersonalityProfile();
		profile.curiosity = 99.0;
		profile.affection = 10.0;
		profile.attachment = 5.0;

		assertEquals("curiosity", profile.dominantTraits().get(0));
	}

	@Test
	void moodDerivesFromPersonality() {
		PersonalityProfile profile = new PersonalityProfile();
		profile.fear = 95.0;
		assertEquals(EmotionalState.FRIGHTENED, EmotionalState.from(profile));

		profile.fear = 0.0;
		profile.loneliness = 95.0;
		assertEquals(EmotionalState.LONELY, EmotionalState.from(profile));
	}
}
