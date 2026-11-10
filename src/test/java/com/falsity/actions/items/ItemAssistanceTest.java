package com.falsity.actions.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Item-name normalisation: the pure spelling logic that never touches the registry. */
class ItemAssistanceTest {
	@Test
	void candidatesFlattenAndNormalise() {
		List<String> candidates = ItemAssistance.candidates("oak logs");
		assertTrue(candidates.contains("oak_logs"), "raw flattened spelling");
		assertTrue(candidates.contains("oak_log"), "synonym-normalised singular");
	}

	@Test
	void candidatesStripPlural() {
		List<String> candidates = ItemAssistance.candidates("diamonds");
		assertTrue(candidates.contains("diamonds"));
		assertTrue(candidates.contains("diamond"), "trailing plural is removed as a fallback");
	}

	@Test
	void candidatesCleanSpecialCharacters() {
		List<String> candidates = ItemAssistance.candidates("Diamond Pickaxe!");
		assertTrue(candidates.contains("diamond_pickaxe"), "case and punctuation are cleaned");
	}

	@Test
	void candidatesRejectBlank() {
		assertTrue(ItemAssistance.candidates("").isEmpty());
		assertTrue(ItemAssistance.candidates("   ").isEmpty());
		assertTrue(ItemAssistance.candidates(null).isEmpty());
	}
}
