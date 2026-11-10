package com.falsity.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Natural-chat detection: who is addressed, what a reply affirms, prefixes. */
class ChatDetectorTest {
	private static final String NAME = "Falsity";

	@Test
	void mentionsNameAsWholeWord() {
		assertTrue(ChatDetector.mentions("Hey Falsity, how are you?", NAME));
		assertTrue(ChatDetector.mentions("Falsity, come here.", NAME));
		assertTrue(ChatDetector.mentions("Can you come here, Falsity?", NAME));
		assertTrue(ChatDetector.mentions("falsity are you there", NAME), "case-insensitive");
	}

	@Test
	void doesNotMentionSubstring() {
		assertFalse(ChatDetector.mentions("my falsity meter is broken", NAME), "substring is not a mention");
		assertFalse(ChatDetector.mentions("Falsitys is not a name", NAME));
	}

	@Test
	void missingNameNeverMatches() {
		assertFalse(ChatDetector.mentions("anything", ""));
		assertFalse(ChatDetector.mentions("anything", "   "));
	}

	@Test
	void stripMentionLeavesCleanMessage() {
		assertEquals("Hey, how are you?", ChatDetector.stripMention("Hey Falsity, how are you?", NAME));
		assertEquals("come here.", ChatDetector.stripMention("Falsity, come here.", NAME));
		assertEquals("are you still here", ChatDetector.stripMention("Falsity, are you still here", NAME));
		assertEquals("still here", ChatDetector.stripMention("Falsity, still here", NAME));
		assertEquals("Can you come here?", ChatDetector.stripMention("Can you come here, Falsity?", NAME));
	}

	@Test
	void stripMentionWithoutNameIsUnchanged() {
		assertEquals("hello there", ChatDetector.stripMention("hello there", NAME));
		assertEquals("my falsity meter is broken", ChatDetector.stripMention("my falsity meter is broken", NAME));
	}

	@Test
	void stripBroadcastPrefixRemovesPlayerTag() {
		assertEquals("Falsity, hi", ChatDetector.stripBroadcastPrefix("<Steve> Falsity, hi"));
		assertEquals("no tag here", ChatDetector.stripBroadcastPrefix("no tag here"));
		assertEquals("", ChatDetector.stripBroadcastPrefix(null));
	}

	@Test
	void affirmativesAndNegativesAreDisjoint() {
		assertTrue(ChatDetector.isAffirmative("yes"));
		assertTrue(ChatDetector.isAffirmative("yes!"));
		assertTrue(ChatDetector.isAffirmative("yeah"));
		assertTrue(ChatDetector.isAffirmative("go ahead"));
		assertTrue(ChatDetector.isAffirmative("build it"));
		assertFalse(ChatDetector.isAffirmative("no"));
		assertFalse(ChatDetector.isAffirmative("maybe"));

		assertTrue(ChatDetector.isNegative("no"));
		assertTrue(ChatDetector.isNegative("no!"));
		assertTrue(ChatDetector.isNegative("don't"));
		assertTrue(ChatDetector.isNegative("cancel"));
		assertTrue(ChatDetector.isNegative("stop"));
		assertTrue(ChatDetector.isNegative("never mind"));
		assertFalse(ChatDetector.isNegative("yes"));
	}

	@Test
	void plainGreetingDetected() {
		assertTrue(ChatDetector.isPlainGreeting("hi"));
		assertTrue(ChatDetector.isPlainGreeting("Hello!"));
		assertTrue(ChatDetector.isPlainGreeting("good morning"));
		assertTrue(ChatDetector.isPlainGreeting("  Good   morning!  "));
		assertTrue(ChatDetector.isPlainGreeting("hey???"));
		assertFalse(ChatDetector.isPlainGreeting("hi, can you help me"));
		assertFalse(ChatDetector.isPlainGreeting("falsity follow me"));
		assertFalse(ChatDetector.isPlainGreeting(null));
	}

	@Test
	void handlesPunctuationNullRepeatedSpacesAndEmbeddedNames() {
		assertFalse(ChatDetector.mentions(null, NAME));
		assertFalse(ChatDetector.mentions("Hey Falsity", null));
		assertFalse(ChatDetector.mentions("   ", NAME));
		assertEquals("", ChatDetector.stripMention(null, NAME));
		assertEquals("Hey, how are you?", ChatDetector.stripMention("Hey, how are you?", null));

		assertTrue(ChatDetector.mentions("Hey   Falsity,   how are you?", NAME));
		assertTrue(ChatDetector.mentions("Falsity!  Come here!", NAME));
		assertTrue(ChatDetector.mentions("Can you, Falsity, help me?", NAME));
		assertEquals("Hey, how are you?", ChatDetector.stripMention("Hey   Falsity,   how are you?", NAME));
		assertEquals("Come here!", ChatDetector.stripMention("Falsity!  Come here!", NAME));
		assertEquals("Can you, help me?", ChatDetector.stripMention("Can you, Falsity, help me?", NAME));

		assertFalse(ChatDetector.mentions("unfalsityness is strange", NAME));
		assertFalse(ChatDetector.mentions("check myfalsity and falsity123", NAME));
		assertFalse(ChatDetector.mentions("Falsity's house looks nice", NAME));
		assertFalse(ChatDetector.mentions("That statement is a falsity!", NAME));
		assertFalse(ChatDetector.mentions("My   falsity   meter   is   broken", NAME));
	}
}
