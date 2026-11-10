package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import org.junit.jupiter.api.Test;

/** Prompt building: personality and memory in, bounded size out. */
class PromptBuilderTest {
	@Test
	void promptIncludesPersonalityAndMemory() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		memory.recordFact("Player's favourite block is diamonds", 50);
		PersonalityProfile personality = new PersonalityProfile();

		String prompt = PromptBuilder.buildSystemPrompt(memory, personality, EmotionalState.CURIOUS, HorrorStage.INNOCENCE, true, "Tell me about diamonds");

		assertTrue(prompt.contains("Falsity"), "Prompt must name Falsity");
		assertTrue(prompt.contains("curious"), "Prompt must describe personality, was: " + prompt);
		assertTrue(prompt.contains("diamonds"), "Prompt must include relevant memory, was: " + prompt);
		assertTrue(prompt.contains("owner"), "Owner prompt must say so");
	}

	@Test
	void strangerPromptIsReserved() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Alex", 0L);
		String prompt = PromptBuilder.buildSystemPrompt(memory, new PersonalityProfile(), EmotionalState.QUIET, HorrorStage.INNOCENCE, false, "Hi");

		assertTrue(prompt.contains("stranger"), "Stranger prompt must say so, was: " + prompt);
	}

	@Test
	void promptIsBounded() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		for (int i = 0; i < 50; i++) {
			memory.recordFact("Fact number " + i + " with some extra words to make it longer", 50);
		}

		String prompt = PromptBuilder.buildSystemPrompt(memory, new PersonalityProfile(), EmotionalState.QUIET, HorrorStage.INNOCENCE, true, "Hello");

		assertTrue(prompt.length() <= PromptBuilder.MAX_SYSTEM_CHARS, "Prompt must stay under the cap, was " + prompt.length());
	}

	@Test
	void horrorHintOnlyAtStrangeOrBeyond() {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);

		String innocent = PromptBuilder.buildSystemPrompt(memory, new PersonalityProfile(), EmotionalState.QUIET, HorrorStage.INNOCENCE, true, "Hi");
		assertTrue(!innocent.contains("unsettling"), "Innocence prompt must not hint at horror");

		String strange = PromptBuilder.buildSystemPrompt(memory, new PersonalityProfile(), EmotionalState.QUIET, HorrorStage.STRANGE, true, "Hi");
		assertTrue(strange.contains("unsettling"), "Strange prompt should allow subtle oddness, was: " + strange);
	}
}
