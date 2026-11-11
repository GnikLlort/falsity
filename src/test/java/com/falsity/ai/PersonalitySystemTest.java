package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.config.FalsityConfig;
import com.falsity.horror.HorrorManager;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemoryStorage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityManager;
import com.falsity.personality.PersonalityProfile;
import com.falsity.personality.PersonalityPrompt;
import com.falsity.personality.PersonalityPromptStore;
import com.falsity.personality.StageDirections;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The personality system from end to end, without a game: the real file store,
 * the real memory, personality and horror managers over real files, the real
 * prompt assembly and the real Groq client. Only the network is replaced, and
 * what it is replaced with records exactly what would have gone to Groq.
 */
class PersonalitySystemTest {
	private static final String KEY = "gsk_unittest0123456789abcdefghijklmnop";
	private static final String CUSTOM =
		"You are Falsity, a stern little guardian who speaks in clipped sentences and counts everything twice.";
	private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final long DAY = 24L * 60L * 60L * 1000L;

	@TempDir
	Path gameDir;

	@TempDir
	Path configDir;

	/** Records every request body instead of sending it, and answers with one short line. */
	private static final class Wire implements GroqClient.Transport {
		final List<String> bodies = new ArrayList<>();
		final List<String> keys = new ArrayList<>();

		@Override
		public synchronized CompletableFuture<GroqClient.HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout) {
			this.bodies.add(jsonBody);
			this.keys.add(apiKey);
			return CompletableFuture.completedFuture(new GroqClient.HttpResult(200,
				"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Mm.\"}}]}"));
		}

		JsonArray messages(int request) {
			return JsonParser.parseString(this.bodies.get(request)).getAsJsonObject().getAsJsonArray("messages");
		}

		String system(int request) {
			JsonObject first = messages(request).get(0).getAsJsonObject();
			assertEquals("system", first.get("role").getAsString());
			return first.get("content").getAsString();
		}

		String lastSystem() {
			return system(this.bodies.size() - 1);
		}
	}

	/** One run of the mod: everything it builds at start-up, over the two directories it uses. */
	private final class Session {
		final FalsityConfig config = new FalsityConfig();
		final MemoryManager memories = new MemoryManager(new MemoryStorage(PersonalitySystemTest.this.gameDir));
		final PersonalityManager personalities = new PersonalityManager(this.memories, this.config);
		final HorrorManager horror = new HorrorManager(this.memories, this.config);
		final PersonalityPromptStore prompts =
			new PersonalityPromptStore(PersonalityPromptStore.fileIn(PersonalitySystemTest.this.configDir));
		final ConversationHistory history = new ConversationHistory();
		final Wire wire = new Wire();
		final GroqClient groq = new GroqClient(this.wire, new GroqClient.GroqRateLimiter());

		Session() {
			this.prompts.load();
		}

		PlayerMemory memory() {
			return this.memories.getOrCreate(STEVE, "Steve");
		}

		/** Sends one message the way a conversation turn does, and returns the layers that were built for it. */
		PromptBuilder.Layers say(String message, long now) {
			PlayerMemory memory = memory();
			long daysAway = memory.daysSinceSeen(now);
			memory.touch(now);
			PersonalityProfile profile = this.personalities.profileFor(STEVE, "Steve");
			PromptAssembler.Turn turn = new PromptAssembler.Turn(
				"Falsity", memory, profile, PromptAssembler.voiceStage(this.horror, STEVE, "Steve"), true, daysAway, message,
				"You and the player are in the Overworld at x=1, y=2, z=3.", "");
			PromptAssembler.Assembled assembled = PromptAssembler.assemble(
				this.prompts.refreshIfChanged(), turn, this.history.recent(STEVE, this.config.groq.historyTurns, now), now, KEY);

			GroqClient.Exchange exchange = this.groq.exchange(new GroqClient.Request(
				this.config.groq.endpoint, KEY, this.config.groq.model, assembled.messages(), this.config.groq.temperature,
				this.config.groq.maxTokens, this.config.groq.timeoutSeconds, Integer.MAX_VALUE,
				this.config.groq.reasoningEffort, this.config.groq.reasoningHeadroomTokens)).join();
			assertTrue(exchange.diagnostics().success(), exchange.diagnostics().summary());

			String reply = ((GroqClient.Result.Success) exchange.result()).content();
			this.history.record(STEVE, message, reply, now);
			memory.recordConversation(message, reply, this.config.memory.maxRecentInteractions);
			memory.recordTreatment(OfflineFallback.treatmentOf(message));
			this.memories.save(STEVE);
			this.personalities.applyEvent(STEVE, "Steve", PersonalityProfile.Event.CONVERSATION);
			this.horror.recordInteraction(STEVE, "Steve");
			return assembled.layers();
		}
	}

	private Path personalityFile() {
		return PersonalityPromptStore.fileIn(this.configDir);
	}

	private void writePersonality(String contents) throws IOException {
		Files.createDirectories(personalityFile().getParent());
		Files.writeString(personalityFile(), contents, StandardCharsets.UTF_8);
	}

	private byte[] memoryFile() throws IOException {
		return Files.readAllBytes(this.gameDir.resolve("falsity-memory").resolve(STEVE + ".json"));
	}

	// ---- the personality reaches Groq ---------------------------------------

	@Test
	void theDefaultFileIsCreatedAndItsTextIsWhatGroqIsSent() throws IOException {
		assertFalse(Files.exists(personalityFile()));

		Session session = new Session();
		session.say("hello", 5L * DAY);

		assertTrue(Files.isRegularFile(personalityFile()), "starting up writes the default file");
		assertEquals(PersonalityPromptStore.defaultFileContents(), Files.readString(personalityFile(), StandardCharsets.UTF_8));
		assertTrue(session.wire.lastSystem().contains(PersonalityPromptStore.defaultText()), session.wire.lastSystem());
	}

	@Test
	void aCustomPersonalityIsWhatGroqIsSent() throws IOException {
		writePersonality("# my own Falsity\n" + CUSTOM + "\n");

		Session session = new Session();
		session.say("hello", 5L * DAY);

		String system = session.wire.lastSystem();
		assertTrue(system.contains(CUSTOM), system);
		assertFalse(system.contains("my own Falsity"), "notes in the file stay in the file: " + system);
		assertFalse(system.contains("small plush creature"), "the default is replaced, not added to: " + system);
		assertEquals(1, session.wire.bodies.size(), "one message, one request");
	}

	@Test
	void anEmptyOrMissingOrBrokenFileStillSendsAPersonality() throws IOException {
		writePersonality("");
		Session empty = new Session();
		empty.say("hello", 5L * DAY);
		assertTrue(empty.wire.lastSystem().contains(PersonalityPromptStore.defaultText()), "empty file");
		assertEquals("", Files.readString(personalityFile()), "and the empty file is left as the owner left it");

		writePersonality("# nothing but a note\n");
		Session notes = new Session();
		notes.say("hello", 5L * DAY);
		assertTrue(notes.wire.lastSystem().contains(PersonalityPromptStore.defaultText()), "notes only");

		Files.delete(personalityFile());
		Files.createDirectories(personalityFile());
		Session folder = new Session();
		folder.say("hello", 5L * DAY);
		assertTrue(folder.wire.lastSystem().contains(PersonalityPromptStore.defaultText()), "a folder in the way");
	}

	@Test
	void theRequestIsLaidOutInItsLayersAndEndsWithThePlayersWords() throws IOException {
		writePersonality(CUSTOM);
		Session session = new Session();
		session.memory().recordFact("Player's favourite block is diamonds", 50);

		session.say("tell me about diamonds", 5L * DAY);

		String system = session.wire.lastSystem();
		int rules = system.indexOf("\nRules:\n");
		int personality = system.indexOf(CUSTOM);
		int stage = system.indexOf(StageDirections.forStage(HorrorStage.INNOCENCE));
		int relationship = system.indexOf("Steve is your owner");
		int memory = system.indexOf("Relevant memories: Player's favourite block is diamonds");
		int world = system.indexOf("Verified facts (checked by the game just now): You and the player are in the Overworld");
		assertTrue(0 < rules && rules < personality && personality < stage && stage < relationship
			&& relationship < memory && memory < world, "rules, personality, stage, relationship, memory, world: " + system);

		JsonArray messages = session.wire.messages(0);
		JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
		assertEquals("user", last.get("role").getAsString());
		assertEquals("tell me about diamonds", last.get("content").getAsString());
	}

	@Test
	void savingTheFileChangesTheVeryNextRequestWithoutARestart() throws IOException {
		Session session = new Session();
		session.say("hello", 5L * DAY);
		assertTrue(session.wire.lastSystem().contains(PersonalityPromptStore.defaultText()));

		writePersonality(CUSTOM);
		PersonalityPrompt reloaded = session.prompts.load();
		assertTrue(reloaded.custom(), reloaded.describe());
		session.say("hello again", 5L * DAY + 60_000L);

		assertTrue(session.wire.lastSystem().contains(CUSTOM), session.wire.lastSystem());
		assertFalse(session.wire.lastSystem().contains("small plush creature"), session.wire.lastSystem());
	}

	// ---- the horror stage reaches Groq --------------------------------------

	@Test
	void theStageOfTheHorrorSystemDecidesWhichDirectionsAreSent() {
		Session session = new Session();

		session.say("hello", 5L * DAY);
		assertEquals(HorrorStage.INNOCENCE, session.horror.stageFor(STEVE, "Steve"));
		assertTrue(session.wire.lastSystem().contains(StageDirections.forStage(HorrorStage.INNOCENCE)), session.wire.lastSystem());
		assertFalse(session.wire.lastSystem().contains("unsettling"), "nothing eerie before the horror system says so");

		// The horror system's own rule: one point of tension per interaction, Strange from twenty.
		for (int index = 0; index < 25; index++) {
			session.horror.recordInteraction(STEVE, "Steve");
		}
		assertEquals(HorrorStage.STRANGE, session.horror.stageFor(STEVE, "Steve"));

		session.say("hello again", 5L * DAY + 60_000L);
		assertTrue(session.wire.lastSystem().contains(StageDirections.forStage(HorrorStage.STRANGE)), session.wire.lastSystem());
		assertFalse(session.wire.lastSystem().contains(StageDirections.forStage(HorrorStage.INNOCENCE)), session.wire.lastSystem());
	}

	@Test
	void theConversationNeverRunsAheadOfTheHorrorSystemsCap() {
		Session session = new Session();
		assertEquals(1, session.config.horror.maxStage, "the cap this milestone ships with");

		for (int index = 0; index < 1_000; index++) {
			session.horror.recordInteraction(STEVE, "Steve");
		}
		session.say("hello", 5L * DAY);

		assertEquals(HorrorStage.STRANGE, session.horror.stageFor(STEVE, "Steve"), "a thousand interactions do not pass the cap");
		assertTrue(session.wire.lastSystem().contains(StageDirections.forStage(HorrorStage.STRANGE)));
		for (HorrorStage later : new HorrorStage[] {HorrorStage.SUSPICION, HorrorStage.FEAR, HorrorStage.NIGHTMARE}) {
			assertFalse(session.wire.lastSystem().contains(StageDirections.forStage(later)), later.name() + " is not open yet");
		}
	}

	@Test
	void everyStageHasDirectionsReadyForWhenTheCapIsRaised() throws IOException {
		writePersonality(CUSTOM);
		Session session = new Session();
		// Lifted on this test's private settings only: the stages beyond the first two
		// exist in the horror system but are closed by its own cap until a later milestone.
		session.config.horror.maxStage = HorrorStage.NIGHTMARE.ordinal();

		long now = 5L * DAY;
		for (HorrorStage stage : HorrorStage.values()) {
			session.horror.setStage(STEVE, "Steve", stage.ordinal());
			now += 60_000L;

			PromptBuilder.Layers layers = session.say("hello", now);

			String system = session.wire.lastSystem();
			assertTrue(system.contains(StageDirections.forStage(stage)), stage.name() + ": " + system);
			assertTrue(system.contains(CUSTOM), "the personality is still there at " + stage.name());
			assertEquals(PromptBuilder.PERSONALITY_HEADER + "\n" + CUSTOM, layers.personality(), stage.name());
			assertTrue(system.indexOf(CUSTOM) < system.indexOf(StageDirections.forStage(stage)),
				"the stage comes after the personality it modifies, at " + stage.name());
		}
	}

	@Test
	void theHorrorSystemsOwnSwitchKeepsTheVoiceNormalWithoutMovingTheStage() {
		Session session = new Session();
		for (int index = 0; index < 25; index++) {
			session.horror.recordInteraction(STEVE, "Steve");
		}
		double tension = session.horror.tensionFor(STEVE, "Steve");

		session.config.horror.allowUnsettlingLines = false;
		session.say("hello", 5L * DAY);

		assertTrue(session.wire.lastSystem().contains(StageDirections.forStage(HorrorStage.INNOCENCE)), session.wire.lastSystem());
		assertFalse(session.wire.lastSystem().contains("unsettling"), session.wire.lastSystem());
		assertEquals(HorrorStage.STRANGE, session.horror.stageFor(STEVE, "Steve"), "the stage itself is untouched");
		assertTrue(session.horror.tensionFor(STEVE, "Steve") >= tension, "and tension keeps its value");
		assertEquals(HorrorStage.INNOCENCE, PromptAssembler.voiceStage(session.horror, STEVE, "Steve"));

		session.config.horror.allowUnsettlingLines = true;
		assertEquals(HorrorStage.STRANGE, PromptAssembler.voiceStage(session.horror, STEVE, "Steve"), "switched back on, the voice follows the stage again");
	}

	// ---- personality changes leave the horror system alone -------------------

	@Test
	void changingThePersonalityDoesNotResetHorrorProgressionOrDrift() throws IOException {
		Session session = new Session();
		for (int index = 0; index < 30; index++) {
			session.say("message " + index, 5L * DAY + index * 60_000L);
		}
		PlayerMemory memory = session.memory();
		assertEquals(HorrorStage.STRANGE, session.horror.stageFor(STEVE, "Steve"), "thirty conversations reach Strange");
		int stage = memory.horror.stage;
		double tension = memory.horror.tension;
		int interactions = memory.horror.interactionCount;
		long changedAt = memory.horror.lastStageChangeEpochMillis;
		double curiosity = memory.personality.curiosity;
		double affection = memory.personality.affection;
		double attachment = memory.personality.attachment;
		double loneliness = memory.personality.loneliness;
		int conversations = memory.conversationCount;
		byte[] before = memoryFile();

		// Every way the personality can change: a new text, an empty file, no file at all, a reload.
		writePersonality(CUSTOM);
		assertTrue(session.prompts.load().custom());
		writePersonality("");
		assertTrue(session.prompts.load().fellBack());
		Files.delete(personalityFile());
		assertTrue(session.prompts.load().matchesDefault());
		writePersonality("A completely different creature. It whistles.");
		session.prompts.load();
		session.prompts.refreshIfChanged();

		assertEquals(stage, memory.horror.stage);
		assertEquals(tension, memory.horror.tension);
		assertEquals(interactions, memory.horror.interactionCount);
		assertEquals(changedAt, memory.horror.lastStageChangeEpochMillis);
		assertEquals(HorrorStage.STRANGE, session.horror.stageFor(STEVE, "Steve"));
		assertEquals(curiosity, memory.personality.curiosity);
		assertEquals(affection, memory.personality.affection);
		assertEquals(attachment, memory.personality.attachment);
		assertEquals(loneliness, memory.personality.loneliness);
		assertEquals(conversations, memory.conversationCount);
		assertEquals(new String(before, StandardCharsets.UTF_8), new String(memoryFile(), StandardCharsets.UTF_8),
			"the player's memory file was not written to at all");

		// And the next request carries the new personality at the same stage.
		session.say("hello", 6L * DAY);
		String system = session.wire.lastSystem();
		assertTrue(system.contains("A completely different creature. It whistles."), system);
		assertTrue(system.contains(StageDirections.forStage(HorrorStage.STRANGE)), "still Strange: " + system);
		assertEquals(interactions + 1, memory.horror.interactionCount, "progression carries on from where it was");
	}

	@Test
	void thePersonalityFileAndTheMemoryFilesAreSeparateThings() throws IOException {
		Session session = new Session();
		session.say("hello", 5L * DAY);

		String memoryJson = new String(memoryFile(), StandardCharsets.UTF_8);
		assertFalse(memoryJson.contains("small plush creature"), "the personality text is not copied into a player's memory");
		String personality = Files.readString(personalityFile(), StandardCharsets.UTF_8);
		assertFalse(
			personality.contains(STEVE.toString()) || personality.contains("interactionCount") || personality.contains("\"stage\""),
			"and no player's state is in the personality file");
	}

	// ---- across sessions ----------------------------------------------------

	@Test
	void aRestartKeepsThePersonalityTheStageAndTheRelationship() throws IOException {
		writePersonality(CUSTOM);
		Session first = new Session();
		first.memory().firstMetEpochMillis = 1L * DAY;
		first.memory().recordFact("Player's favourite block is diamonds", 50);
		first.memory().recordPet(20);
		for (int index = 0; index < 30; index++) {
			first.say("diamonds, number " + index, 5L * DAY + index * 60_000L);
		}
		first.memories.saveAll();
		long now = 5L * DAY + 31L * 60_000L;
		// What the mod would send for the next message in the session that is about to end.
		first.history.clear(STEVE);
		PromptBuilder.Layers before = first.say("tell me about diamonds", now);
		PlayerMemory was = first.memory();

		// Everything is built again from nothing but the files on disk.
		Session second = new Session();
		PlayerMemory restored = second.memory();
		assertEquals(was.horror.stage, restored.horror.stage);
		assertEquals(was.horror.tension, restored.horror.tension);
		assertEquals(was.personality.curiosity, restored.personality.curiosity);
		assertEquals(was.personality.affection, restored.personality.affection);
		assertEquals(was.conversationCount, restored.conversationCount);
		assertEquals(was.treatment, restored.treatment);
		assertEquals(HorrorStage.STRANGE, second.horror.stageFor(STEVE, "Steve"));
		assertEquals(CUSTOM, second.prompts.current().text());

		PromptBuilder.Layers after = second.say("tell me about diamonds", now + 1_000L);

		assertEquals(before.rules(), after.rules(), "same rules");
		assertEquals(before.personality(), after.personality(), "same personality");
		assertEquals(before.stage(), after.stage(), "same stage");
		assertTrue(after.personality().contains(CUSTOM), after.personality());
		assertTrue(after.stage().contains(StageDirections.forStage(HorrorStage.STRANGE)), after.stage());
		assertTrue(after.relationship().contains("Steve is your owner"), after.relationship());
		assertTrue(after.relationship().contains("You have known them for 4 days and have talked 31 times and been petted once."),
			"the counts carried over and went on from there: " + after.relationship());
		assertTrue(after.memory().contains("Player's favourite block is diamonds"), after.memory());
	}

	@Test
	void theLastConversationIsPickedUpInTheNextSession() {
		Session first = new Session();
		first.say("I am going to build a lighthouse", 5L * DAY);
		first.memories.saveAll();

		Session second = new Session();
		PromptBuilder.Layers layers = second.say("hello again", 5L * DAY + 3_600_000L);

		assertTrue(layers.memory().contains("Last time you talked: they said 'I am going to build a lighthouse' and you said 'Mm.'."),
			"a new session has no transcript, so the stored notes say how it went: " + layers.memory());
		assertEquals(2, second.wire.messages(0).size(), "only the system prompt and the new message: no transcript survives a restart");

		PromptBuilder.Layers next = second.say("what was I building?", 5L * DAY + 3_660_000L);
		assertFalse(next.memory().contains("Last time you talked"), "once talking, the live transcript carries the thread: " + next.memory());
		assertEquals(4, second.wire.messages(1).size(), "system prompt, the earlier exchange, the new message");
	}

	@Test
	void anAbsenceIsNoticedOnReturn() {
		Session session = new Session();
		session.say("see you soon", 5L * DAY);

		PromptBuilder.Layers back = session.say("I'm back", 9L * DAY + 5_000L);

		assertTrue(back.relationship().contains("They have just come back after 4 days away."), back.relationship());
	}

	// ---- treatment ----------------------------------------------------------

	@Test
	void howThePlayerSpeaksToFalsityShowsUpInTheNextRequest() {
		Session session = new Session();
		long now = 5L * DAY;

		session.say("you're so stupid", now);
		PromptBuilder.Layers afterHarsh = session.say("anyway", now + 60_000L);
		assertTrue(afterHarsh.relationship().contains("They have been harsh with you lately")
			|| afterHarsh.relationship().contains("was harsh, and it stung"), afterHarsh.relationship());

		Session other = new Session();
		other.memories.clear(STEVE);
		other.say("thank you", now);
		other.say("you're so cute", now + 60_000L);
		other.say("I love you", now + 120_000L);
		PromptBuilder.Layers afterKind = other.say("what now", now + 180_000L);
		assertTrue(afterKind.relationship().contains("They have been kind to you lately."), afterKind.relationship());
		assertEquals(3, other.memory().kindWords);
	}

	// ---- speaking first -----------------------------------------------------

	@Test
	void aRemarkOfItsOwnIsBuiltFromTheSameLayersAsAnyReply() throws IOException {
		writePersonality(CUSTOM);
		Session session = new Session();
		for (int index = 0; index < 25; index++) {
			session.horror.recordInteraction(STEVE, "Steve");
		}

		PromptBuilder.Layers layers = session.say(PromptAssembler.SPEAK_FIRST_CUE, 5L * DAY);

		String system = session.wire.lastSystem();
		assertTrue(system.contains(CUSTOM), "the same personality: " + system);
		assertTrue(system.contains(StageDirections.forStage(HorrorStage.STRANGE)), "in the voice of the same stage: " + system);
		assertTrue(system.contains("Steve is your owner"), system);
		assertTrue(system.contains("Verified facts (checked by the game just now)"), system);
		assertTrue(layers.rules().contains("Never invent a memory"), "under the same rules");
		JsonArray messages = session.wire.messages(0);
		assertEquals(PromptAssembler.SPEAK_FIRST_CUE, messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString(),
			"what stands where the player's message would be is the request to speak, nothing else");
	}

	@Test
	void theRequestToSpeakFirstSuppliesNoWordsAndAsksForNoAction() {
		String cue = PromptAssembler.SPEAK_FIRST_CUE;

		assertTrue(cue.startsWith("(") && cue.endsWith(")"), "it is a stage direction, not something a player said");
		assertTrue(cue.contains("of your own accord"), cue);
		assertTrue(cue.contains("use no tag"), "a remark nobody asked for must not ask for an action: " + cue);
		assertTrue(cue.contains("If the verified facts show danger, warn them"), "danger comes first: " + cue);
		assertTrue(cue.contains("Do not greet them as if they had just arrived"), cue);
		assertFalse(cue.contains("\"") || cue.contains("'"), "it quotes no line to say: " + cue);
		assertTrue(cue.length() < 450, "and it is short: " + cue.length());
	}

	@Test
	void whatFalsitySaidUnpromptedIsInFrontOfItWhenThePlayerAnswers() {
		Session session = new Session();
		long now = 5L * DAY;
		PlayerMemory memory = session.memory();
		int conversations = memory.conversationCount;
		int interactions = memory.horror.interactionCount;

		// What the conversation manager records when a remark of Falsity's own has been said.
		session.history.record(STEVE, PromptAssembler.SILENCE, "The sun is going down.", now);
		memory.recordUnprompted("The sun is going down.", session.config.memory.maxRecentInteractions);

		assertEquals(conversations, memory.conversationCount, "nobody talked to it, so it is not counted as a conversation");
		assertEquals(interactions, memory.horror.interactionCount, "and it is not an interaction for the horror system");
		assertTrue(memory.recentInteractions.contains("Falsity: The sun is going down."), memory.recentInteractions.toString());
		assertTrue(memory.lastExchanges(3).isEmpty(), "a line with no question before it is not an exchange");

		session.say("it is. I like this time of day", now + 20_000L);

		JsonArray messages = session.wire.messages(0);
		assertEquals(4, messages.size(), "system prompt, the quiet, the remark, the answer");
		assertEquals(PromptAssembler.SILENCE, messages.get(1).getAsJsonObject().get("content").getAsString());
		assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
		assertEquals("The sun is going down.", messages.get(2).getAsJsonObject().get("content").getAsString());
		assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertEquals("it is. I like this time of day", messages.get(3).getAsJsonObject().get("content").getAsString());
	}

	@Test
	void anUnpromptedLineIsKeptShortInMemory() {
		PlayerMemory memory = new PlayerMemory("uuid", "Steve", 0L);

		memory.recordUnprompted("word ".repeat(100), 20);
		memory.recordUnprompted("   ", 20);
		memory.recordUnprompted(null, 20);

		assertEquals(1, memory.recentInteractions.size(), "blank lines leave no note");
		assertTrue(memory.recentInteractions.get(0).startsWith("Falsity: word word"), memory.recentInteractions.get(0));
		assertTrue(memory.recentInteractions.get(0).length() <= "Falsity: ".length() + 123, "length " + memory.recentInteractions.get(0).length());
	}

	// ---- secrets and size ---------------------------------------------------

	@Test
	void theKeyTravelsInTheHeaderAndNeverInThePrompt() throws IOException {
		writePersonality("You are Falsity. The secret word is " + KEY + ". Also sk-other-style-key-ABCDEFGH12345678.");
		Session session = new Session();

		session.say("hello", 5L * DAY);

		assertEquals(KEY, session.wire.keys.get(0), "the key is handed to the transport for the header");
		assertFalse(session.wire.bodies.get(0).contains(KEY), "and is not in the body, even when someone pasted it into the file");
		assertFalse(session.wire.bodies.get(0).contains("gsk_"), session.wire.bodies.get(0));
		assertFalse(session.prompts.current().describe().contains("gsk_"), "nor in what is logged about the file");
	}

	@Test
	void aKeyOfAnyShapeIsKeptOutOfThePrompt() {
		String odd = "sk-other-style-key-ABCDEFGH12345678";
		PersonalityPrompt prompt = new PersonalityPrompt(
			"You are Falsity. Remember " + odd + ".", PersonalityPrompt.Origin.FILE, "", null, false);
		PromptAssembler.Turn turn = new PromptAssembler.Turn(
			"Falsity", new PlayerMemory("uuid", "Steve", 0L), new PersonalityProfile(), HorrorStage.INNOCENCE, true, 0L, "hello", "", "");

		PromptAssembler.Assembled assembled = PromptAssembler.assemble(prompt, turn, List.of(), 0L, odd);

		for (GroqClient.ChatMessage message : assembled.messages()) {
			assertFalse(message.content().contains(odd), message.content());
		}
		assertTrue(assembled.messages().get(0).content().contains("Remember [removed]."), assembled.messages().get(0).content());
		assertEquals("hello", PromptAssembler.scrub("hello", ""), "nothing to remove, nothing changed");
		assertEquals("hello", PromptAssembler.scrub("hello", null));
		assertEquals("abc", PromptAssembler.scrub("abc", "abc"), "a 'secret' too short to be a key is not treated as one");
		assertEquals("", PromptAssembler.scrub(null, odd));
	}

	@Test
	void theConversationSoFarIsSentInBoundedForm() {
		Session session = new Session();
		long now = 5L * DAY;
		for (int index = 0; index < 60; index++) {
			session.say("message number " + index + " " + "word ".repeat(20), now + index * 1_000L);
		}

		JsonArray messages = session.wire.messages(session.wire.bodies.size() - 1);

		int turns = (messages.size() - 2) / 2;
		assertTrue(turns <= session.config.groq.historyTurns, "no more exchanges than the setting allows, sent " + turns);
		int characters = 0;
		for (int index = 1; index < messages.size() - 1; index++) {
			characters += messages.get(index).getAsJsonObject().get("content").getAsString().length();
		}
		assertTrue(characters <= ConversationHistory.MAX_PROMPT_CHARS, "history characters " + characters);
		assertTrue(session.wire.lastSystem().length() <= PromptBuilder.MAX_SYSTEM_CHARS, "system prompt " + session.wire.lastSystem().length());
	}

	@Test
	void aLongRelationshipDoesNotMakeRequestsGrow() {
		Session session = new Session();
		long now = 5L * DAY;
		PlayerMemory memory = session.memory();
		for (int index = 0; index < 400; index++) {
			memory.recordFact("Player said: I like thing number " + index + " very much indeed", session.config.memory.maxStoredFacts);
		}

		session.say("hello", now);
		int early = session.wire.bodies.get(0).length();
		for (int index = 0; index < 80; index++) {
			session.say("another day, another message " + index, now + (index + 1) * 60_000L);
		}
		int late = session.wire.bodies.get(session.wire.bodies.size() - 1).length();

		assertTrue(late < early + ConversationHistory.MAX_PROMPT_CHARS + 600,
			"request size levels off: first " + early + " characters, after eighty more " + late);
		assertTrue(late < 9_000, "and stays small in absolute terms, was " + late);
	}
}
