package com.falsity.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What Falsity keeps for good out of a conversation: only things the player
 * said about themselves, in their own words, and never trivia.
 */
class FactExtractorTest {
	private static PlayerMemory memory() {
		return new PlayerMemory("uuid-1", "Steve", 0L);
	}

	@Test
	void whatWasAlwaysRememberedStillIs() {
		assertEquals("Player's name is Sam", FactExtractor.factIn("my name is Sam"));
		assertEquals("Player's name is Sam", FactExtractor.factIn("My Name Is Sam"));
		assertEquals("Player said: I like building towers", FactExtractor.factIn("I like building towers"));
		assertEquals("Player said: i love the rain", FactExtractor.factIn("i love the rain"));
		assertEquals("Player said: I hate creepers", FactExtractor.factIn("I hate creepers"));
		assertEquals("Player's favourite is block is diamond", FactExtractor.factIn("my favourite block is diamond"));
		assertEquals("Player's favourite is food is bread", FactExtractor.factIn("My favorite food is bread"));
		assertEquals("Player asked Falsity to remember: the base is by the river", FactExtractor.factIn("remember the base is by the river"));
	}

	@Test
	void anIntroductionByAnyWordingGivesTheName() {
		assertEquals("Player's name is Sam", FactExtractor.factIn("call me Sam"));
		assertEquals("Player's name is Sam", FactExtractor.factIn("you can call me Sam."));
		assertEquals("Player's name is Dr_Bones", FactExtractor.factIn("just call me Dr_Bones!"));
	}

	@Test
	void callMeLaterIsNotAnIntroduction() {
		for (String message : new String[] {
			"call me later", "call me when you find diamonds", "call me back", "call me crazy", "call me a hero",
			"call me if anything happens", "call me maybe", "call me", "call me Sam the great builder of towers"}) {
			assertEquals("", FactExtractor.factIn(message), message);
		}
	}

	@Test
	void whatThePlayerIsDoingAndWhereTheyLiveIsKept() {
		for (String message : new String[] {
			"I'm building a lighthouse on the north shore", "i am building a castle", "im building a farm",
			"I'm making a map of the whole island", "I am working on a redstone door", "I'm looking for a jungle",
			"I live in the birch forest", "i live near the village", "I'm living in a cave for now", "I'm from the mushroom island"}) {
			assertEquals("Player said: " + message, FactExtractor.factIn(message), message);
		}
	}

	@Test
	void whatThePlayerFearsIsKept() {
		for (String message : new String[] {"I'm afraid of the dark", "i am scared of creepers", "I'm terrified of the deep dark", "I fear the warden"}) {
			assertEquals("Player said: " + message, FactExtractor.factIn(message), message);
		}
	}

	@Test
	void whatBelongsToThePlayerIsKept() {
		for (String message : new String[] {
			"my base is beside the mushroom island", "My dog is called Rex", "my sword is named Sting", "my brother is joining tomorrow",
			"my new house is made of dark oak", "my farms are behind the hill", "my birthday is in June", "my horse was the fastest one"}) {
			assertEquals("Player said: " + message, FactExtractor.factIn(message), message);
		}
	}

	@Test
	void questionsOrdersAndSmallTalkAreNotFacts() {
		for (String message : new String[] {
			"hello", "how are you", "follow me", "give me 3 torches", "where is my base?", "is my dog ok?", "do you remember my name?",
			"I'm building a what?", "I want to build a house", "I need a hug", "I'm tired", "I'm back", "my legs are tired",
			"my pockets feel so empty today", "my goodness", "my base", "i live", "that is my house", "we are building a wall",
			"build me a house", "I am", "", "   ", "?"}) {
			assertEquals("", FactExtractor.factIn(message), message);
		}
		assertEquals("", FactExtractor.factIn(null));
	}

	@Test
	void longMessagesAreNotKeptAsFacts() {
		String long81 = "I like " + "x".repeat(74);
		assertEquals(81, long81.length());
		assertEquals("", FactExtractor.factIn(long81));
		assertEquals("Player said: " + long81.substring(0, 80), FactExtractor.factIn(long81.substring(0, 80)));

		assertEquals("", FactExtractor.factIn("I'm building " + "a very long thing ".repeat(8)));
		assertEquals("", FactExtractor.factIn("my base is " + "far ".repeat(30)));
		assertEquals("", FactExtractor.factIn("my name is " + "n".repeat(33)));
		assertEquals("", FactExtractor.factIn("remember " + "r".repeat(81)));
	}

	@Test
	void strayWhitespaceIsTidiedBeforeStoring() {
		assertEquals("Player said: I like building towers", FactExtractor.factIn("   I like   building\ttowers  "));
		assertEquals("Player's name is Sam", FactExtractor.factIn("  my name is   Sam "));
	}

	@Test
	void aFactGoesIntoTheMemoryOnceAndWithinTheCap() {
		PlayerMemory memory = memory();

		assertEquals("Player said: my base is by the river", FactExtractor.extract(memory, "my base is by the river", 50));
		FactExtractor.extract(memory, "my base is by the river", 50);
		assertEquals(List.of("Player said: my base is by the river"), memory.facts, "the same thing said twice is one memory");

		assertEquals("", FactExtractor.extract(memory, "hello", 50));
		assertEquals(1, memory.facts.size(), "small talk adds nothing");

		for (int index = 0; index < 10; index++) {
			FactExtractor.extract(memory, "I like thing number " + index, 3);
		}
		assertEquals(3, memory.facts.size(), "the configured cap is the one that applies");
		assertTrue(memory.facts.contains("Player said: I like thing number 9"), memory.facts.toString());

		PlayerMemory none = memory();
		FactExtractor.extract(none, "my name is Sam", 0);
		assertTrue(none.facts.isEmpty(), "a cap of zero keeps nothing");
		assertEquals("Player's name is Sam", FactExtractor.extract(null, "my name is Sam", 50), "no memory to write to is not an error");
	}
}
