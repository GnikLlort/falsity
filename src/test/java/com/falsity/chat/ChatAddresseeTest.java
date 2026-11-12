package com.falsity.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A player who has just been talking to Falsity and then turns to another
 * player is no longer talking to Falsity.
 */
class ChatAddresseeTest {
	private static final List<String> OTHERS = List.of("Alex", "Notch_99");

	private static boolean toSomeoneElse(String message) {
		return ChatDetector.addressesSomeoneElse(message, OTHERS);
	}

	@Test
	void callingAnotherPlayerByNameIsNotForFalsity() {
		for (String message : new String[] {
			"Alex, come here", "alex come here", "ALEX!", "Alex?", "Alex: look at this", "hey Alex", "hey alex where are you",
			"thanks Alex", "thank you Alex!", "sorry alex", "where are you Alex?", "come over here, Alex", "@Alex tp to me",
			"ok Notch_99 lets go", "Notch_99 bring the pickaxe", "nice one, notch_99!", "lol @alex is that you", "@Alex is this yours?"}) {
			assertTrue(toSomeoneElse(message), message);
		}
	}

	@Test
	void talkingAboutAnotherPlayerIsStillAFollowUp() {
		for (String message : new String[] {
			"Alex is here", "alex was mining with me", "Alex has the diamonds", "Alex said you were cute", "alex just left",
			"Alex and I built a tower", "Alex can't find the base", "I saw Alex yesterday", "do you like Alex more than me",
			"that is Alex's house", "is Alex online", "Alex's dog is loud", "we should wait for Alex before going"}) {
			assertFalse(toSomeoneElse(message), message);
		}
	}

	@Test
	void ordinaryFollowUpsAreUntouched() {
		for (String message : new String[] {
			"what about the other one?", "yes", "and then?", "follow me", "give me 3 torches", "how was your day",
			"I like building towers", "alexander the great was a king", "relax a bit", "notch", "99 bottles"}) {
			assertFalse(toSomeoneElse(message), message);
		}
	}

	@Test
	void namesInsideOtherWordsDoNotCount() {
		assertFalse(ChatDetector.addressesSomeoneElse("alexandria is far", List.of("Alex")));
		assertFalse(ChatDetector.addressesSomeoneElse("topnotch_990 work", List.of("Notch_99")));
		assertTrue(ChatDetector.addressesSomeoneElse("Bob-the-builder, hi", List.of("Bob")), "a hyphen ends a name");
	}

	@Test
	void nobodyElseOnlineMeansNobodyElseAddressed() {
		assertFalse(ChatDetector.addressesSomeoneElse("Alex, come here", List.of()));
		assertFalse(ChatDetector.addressesSomeoneElse("Alex, come here", null));
		assertFalse(ChatDetector.addressesSomeoneElse(null, OTHERS));
		assertFalse(ChatDetector.addressesSomeoneElse("   ", OTHERS));

		List<String> blanks = new ArrayList<>();
		blanks.add(null);
		blanks.add("");
		blanks.add("  ");
		assertFalse(ChatDetector.addressesSomeoneElse("hello there", blanks), "a blank name matches nothing");
	}

	@Test
	void aMessageThatAlsoNamesFalsityIsStillRecognisedAsNamingFalsity() {
		// The listener checks Falsity's own name first; this only confirms both checks see what they should.
		String message = "Alex, look at Falsity";
		assertTrue(toSomeoneElse(message));
		assertTrue(ChatDetector.mentions("Falsity, tell Alex hello", "Falsity"));
		assertFalse(ChatDetector.addressesSomeoneElse("Falsity, tell Alex hello", List.of("Alex")),
			"asking Falsity to pass something on is addressed to Falsity");
	}
}
