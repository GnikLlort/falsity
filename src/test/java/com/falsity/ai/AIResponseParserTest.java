package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** AI output parsing: dialogue in, validated suggestion out, never more. */
class AIResponseParserTest {
	@Test
	void plainDialogueHasNoAction() {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse("Hello, friend.");

		assertTrue(parsed.isPresent());
		assertEquals("Hello, friend.", parsed.get().dialogue());
		assertEquals(AIResponseParser.SuggestedAction.NONE, parsed.get().action());
	}

	@Test
	void validActionTagIsExtracted() {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse("I will sit here. [action:sit]");

		assertTrue(parsed.isPresent());
		assertEquals("I will sit here.", parsed.get().dialogue());
		assertEquals(AIResponseParser.SuggestedAction.SIT, parsed.get().action());
	}

	@Test
	void invalidActionIsRejectedButDialogueKept() {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse("Hello! [action:explode]");

		assertTrue(parsed.isPresent());
		assertEquals("Hello!", parsed.get().dialogue());
		assertEquals(AIResponseParser.SuggestedAction.NONE, parsed.get().action(), "Unknown actions must be rejected");
	}

	@Test
	void commandLikeActionsAreRejected() {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse("Hi [action:run_command]");

		assertTrue(parsed.isPresent());
		assertEquals(AIResponseParser.SuggestedAction.NONE, parsed.get().action());
	}

	@Test
	void blankOutputIsRejected() {
		assertTrue(AIResponseParser.parse(null).isEmpty());
		assertTrue(AIResponseParser.parse("").isEmpty());
		assertTrue(AIResponseParser.parse("   ").isEmpty());
		assertTrue(AIResponseParser.parse("[action:sit]").isEmpty(), "An action with no dialogue is not a reply");
	}

	@Test
	void overlongDialogueIsCutToLimit() {
		String longLine = "x".repeat(500);
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse(longLine);

		assertTrue(parsed.isPresent());
		assertEquals(AIResponseParser.MAX_DIALOGUE_CHARS, parsed.get().dialogue().length());
	}

	@Test
	void surroundingQuotesAreStripped() {
		Optional<AIResponseParser.ParsedResponse> parsed = AIResponseParser.parse("\"Hello.\"");

		assertTrue(parsed.isPresent());
		assertEquals("Hello.", parsed.get().dialogue());
	}
}
