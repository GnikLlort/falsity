package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.EmotionalState;
import com.falsity.personality.PersonalityProfile;
import com.falsity.personality.PersonalityPromptStore;
import com.falsity.personality.RelationshipState;
import com.falsity.personality.StageDirections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The personality system gives directions, not lines. Nothing it adds is a
 * sentence for Falsity to recite, and nothing it adds makes Falsity repeat
 * itself.
 */
class NoCannedDialogueTest {
	private static final List<String> SMALL_TALK = List.of("hello", "ok", "how are you", "the weather is nice", "hi", "I found a cave");

	private static PlayerMemory memoryWith(String... facts) {
		PlayerMemory memory = new PlayerMemory("uuid-1", "Steve", 0L);
		for (String fact : facts) {
			memory.recordFact(fact, 50);
		}
		return memory;
	}

	// ---- what is sent to the model ------------------------------------------

	@Test
	void thePersonalityAndTheStagesContainNoLinesToRecite() {
		List<String> texts = new ArrayList<>();
		texts.add(PersonalityPromptStore.defaultText());
		for (HorrorStage stage : HorrorStage.values()) {
			texts.add(StageDirections.forStage(stage));
		}
		PersonalityProfile profile = new PersonalityProfile();
		texts.add(RelationshipState.describe(memoryWith(), profile, EmotionalState.from(profile), true, 3L, 0L));

		for (String text : texts) {
			assertFalse(text.contains("\"") || text.contains("\u201C") || text.contains("\u201D"), "no quoted speech in: " + text);
			assertFalse(text.matches("(?s).*\\b(?:[Ss]ay|[Rr]eply with|[Aa]nswer with)\\s*:.*"), "no scripted line in: " + text);
			String lower = text.toLowerCase(Locale.ROOT);
			assertFalse(lower.contains("for example") || lower.contains("e.g.") || lower.contains("such as:"), "no sample to parrot in: " + text);
		}
	}

	@Test
	void theDefaultPersonalityForbidsCatchphrasesRatherThanSupplyingThem() {
		String personality = PersonalityPromptStore.defaultText().toLowerCase(Locale.ROOT);

		assertTrue(personality.contains("no catchphrases, no stock greetings, no repeated reactions"), personality);
		assertFalse(personality.contains("always say") || personality.contains("always greet") || personality.contains("your catchphrase"),
			personality);
	}

	@Test
	void everyRequestTellsTheModelNotToRepeatItselfAndShowsItWhatItSaidBefore() {
		PlayerMemory memory = memoryWith();
		for (HorrorStage stage : HorrorStage.values()) {
			String system = PromptBuilder.request().stage(stage).memory(memory).build().text();
			assertTrue(system.contains("do not repeat your earlier lines"), stage.name());
		}

		List<ConversationHistory.Turn> history = List.of(
			new ConversationHistory.Turn("hello", "Oh. Hello. You are early today.", 1L),
			new ConversationHistory.Turn("am I?", "The sun is only half up. I checked.", 2L));
		List<GroqClient.ChatMessage> messages = PromptBuilder.buildMessages("SYSTEM", history, "hello again");

		assertEquals("assistant", messages.get(2).role());
		assertEquals("Oh. Hello. You are early today.", messages.get(2).content(), "its own earlier lines are in front of it");
		assertEquals("The sun is only half up. I checked.", messages.get(4).content());
	}

	@Test
	void theRulesGiveTheModelNoGreetingOrFarewellToFallBackOn() {
		String rules = PromptBuilder.request().build().rules();

		// The only quoted text in the rules is the tag syntax and examples of what a PLAYER might say.
		for (String allowed : new String[] {
			"\"type\":\"follow\"", "\"1 crafting table, 64 sticks\"", "\"nearest village\"", "\"where am i\"", "\"diamonds nearby\"",
			"\"I want to build a house\"", "\"I need a hug\""}) {
			assertTrue(rules.contains(allowed), allowed);
			rules = rules.replace(allowed, "");
		}
		assertFalse(rules.contains("\""), "nothing else is quoted: " + rules);
	}

	// ---- offline ------------------------------------------------------------

	@Test
	void theStagesAddNoLinesOfTheirOwnToTheOfflineVoice() {
		// With nothing remembered there is nothing to recall, so every stage must
		// answer from exactly the same lines as the first stage does.
		for (String message : SMALL_TALK) {
			for (long seed = 0; seed < 40; seed++) {
				String normal = new OfflineFallback(new Random(seed))
					.respond(memoryWith(), new PersonalityProfile(), HorrorStage.INNOCENCE, message, true, List.of());
				for (HorrorStage stage : HorrorStage.values()) {
					String line = new OfflineFallback(new Random(seed))
						.respond(memoryWith(), new PersonalityProfile(), stage, message, true, List.of());
					assertEquals(normal, line, stage.name() + " said something the first stage would not, for '" + message + "' and seed " + seed);
				}
			}
		}
	}

	@Test
	void whatALaterStageBringsUpOfflineIsAlwaysSomethingThePlayerReallySaid() {
		String[] facts = {"Player's name is Sam", "Player said: I like building towers", "Player's favourite is the colour teal"};
		for (HorrorStage stage : HorrorStage.values()) {
			OfflineFallback fallback = new OfflineFallback(new Random(7L));
			int recalled = 0;
			for (int index = 0; index < 300; index++) {
				String line = fallback.respond(memoryWith(facts), new PersonalityProfile(), stage, SMALL_TALK.get(index % SMALL_TALK.size()), true);
				if (line.startsWith("I still remember... ")) {
					recalled++;
					String quoted = line.substring("I still remember... ".length(), line.length() - 1);
					boolean real = false;
					for (String fact : facts) {
						real |= fact.equalsIgnoreCase(quoted);
					}
					assertTrue(real, stage.name() + " recalled something that was never stored: " + line);
				}
			}
			if (stage == HorrorStage.INNOCENCE) {
				assertEquals(0, recalled, "the first stage never does this");
			} else {
				assertTrue(recalled > 0, stage.name() + " should sometimes recall");
			}
		}
	}

	@Test
	void laterStagesRecallMoreOftenOffline() {
		int[] recalled = new int[HorrorStage.values().length];
		for (HorrorStage stage : HorrorStage.values()) {
			OfflineFallback fallback = new OfflineFallback(new Random(11L));
			PlayerMemory memory = memoryWith("Player's name is Sam", "Player said: I like building towers",
				"Player's favourite is the colour teal", "Player asked Falsity to remember: the base is by the river");
			for (int index = 0; index < 1_200; index++) {
				// A fresh list of what was said before each time, so a recalled fact is never held back as a repeat.
				if (fallback.respond(memory, new PersonalityProfile(), stage, "hello", true, List.of()).startsWith("I still remember")) {
					recalled[stage.ordinal()]++;
				}
			}
		}

		assertEquals(0, recalled[HorrorStage.INNOCENCE.ordinal()]);
		for (int stage = HorrorStage.STRANGE.ordinal(); stage < recalled.length; stage++) {
			assertTrue(recalled[stage] > recalled[stage - 1],
				"stage " + stage + " recalled " + recalled[stage] + " times, the stage before it " + recalled[stage - 1]);
		}
	}

	@Test
	void atStrangeTheOfflineVoiceBehavesExactlyAsItAlwaysDid() {
		// Before the personality system: at Strange, one small-talk reply in four
		// recalled a fact, drawn with nextInt(4) and then nextInt(number of facts).
		// The same seed must still open the same way.
		PlayerMemory memory = memoryWith("Player's name is Sam", "Player said: I like building towers", "Player's favourite is teal");
		int recalls = 0;
		int ordinary = 0;
		// Seeds that are close together give Random the same first small number, so they are spread out.
		Random seeds = new Random(42L);
		for (int round = 0; round < 60; round++) {
			long seed = seeds.nextLong();
			Random reference = new Random(seed);
			boolean expectRecall = reference.nextInt(4) == 0;
			String line = new OfflineFallback(new Random(seed))
				.respond(memory, new PersonalityProfile(), HorrorStage.STRANGE, "hello", true, List.of());
			if (expectRecall) {
				String fact = memory.facts.get(reference.nextInt(memory.facts.size()));
				assertEquals("I still remember... " + fact.substring(0, 1).toLowerCase(Locale.ROOT) + fact.substring(1) + ".", line, "seed " + seed);
				recalls++;
			} else {
				assertFalse(line.startsWith("I still remember"), "seed " + seed + ": " + line);
				ordinary++;
			}
		}
		assertTrue(recalls >= 5 && ordinary >= 25, "both cases were exercised: " + recalls + " recalls, " + ordinary + " ordinary");
	}

	@Test
	void theSameOfflineLineIsNeverSaidTwiceRunningAtAnyStage() {
		for (HorrorStage stage : HorrorStage.values()) {
			OfflineFallback fallback = new OfflineFallback(new Random(99L));
			PlayerMemory memory = memoryWith("Player's name is Sam", "Player said: I like building towers",
				"Player's favourite is the colour teal");
			List<String> said = new ArrayList<>();
			for (int index = 0; index < 400; index++) {
				List<String> recent = new ArrayList<>();
				for (int back = said.size() - 1; back >= Math.max(0, said.size() - 6); back--) {
					recent.add(said.get(back));
				}
				String line = fallback.respond(memory, new PersonalityProfile(), stage, SMALL_TALK.get(index % SMALL_TALK.size()), true, recent);
				assertFalse(line.isBlank());
				if (!said.isEmpty()) {
					assertNotEquals(said.get(said.size() - 1), line, stage.name() + " repeated itself at reply " + index);
				}
				said.add(line);
			}
			Set<String> distinct = new HashSet<>(said);
			assertTrue(distinct.size() >= 20, stage.name() + " has only " + distinct.size() + " different things to say");
		}
	}

	// ---- treatment ------------------------------------------------------------

	@Test
	void howAMessageTreatsFalsityIsReadFromWhatWasSaid() {
		for (String kind : new String[] {"thank you", "thanks!", "I love you", "you're so cute", "good job", "sorry", "you are my best friend", "<3"}) {
			assertEquals(PlayerMemory.KIND, OfflineFallback.treatmentOf(kind), kind);
		}
		for (String harsh : new String[] {"I hate you", "you're so stupid", "shut up", "go away", "you are useless", "you suck"}) {
			assertEquals(PlayerMemory.HARSH, OfflineFallback.treatmentOf(harsh), harsh);
		}
		for (String neither : new String[] {"hello", "follow me", "where is the nearest village", "I want to build a house", "", null, "the cave is stupid deep"}) {
			assertEquals(PlayerMemory.NEUTRAL, OfflineFallback.treatmentOf(neither), String.valueOf(neither));
		}
	}
}
