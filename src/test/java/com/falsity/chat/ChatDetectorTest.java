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
		assertEquals("still here", ChatDetector.stripMention("Falsity, are you still here", NAME));
	}

	@Test
	void stripMentionWithoutNameIsUnchanged() {
		assertEquals("hello there", ChatDetector.stripMention("hello there", NAME));
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
		assertTrue(ChatDetector.isAffirmative("yeah"));
		assertTrue(ChatDetector.isAffirmative("go ahead"));
		assertTrue(ChatDetector.isAffirmative("build it"));
		assertFalse(ChatDetector.isAffirmative("no"));
		assertFalse(ChatDetector.isAffirmative("maybe"));

		assertTrue(ChatDetector.isNegative("no"));
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
		assertFalse(ChatDetector.isPlainGreeting("hi, can you help me"));
		assertFalse(ChatDetector.isPlainGreeting("falsity follow me"));
	}
}
