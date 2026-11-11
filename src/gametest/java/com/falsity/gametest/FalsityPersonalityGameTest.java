package com.falsity.gametest;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.actions.items.ItemAssistance;
import com.falsity.ai.ApiKeyProvider;
import com.falsity.ai.ConversationHistory;
import com.falsity.ai.ConversationManager;
import com.falsity.ai.GroqClient;
import com.falsity.ai.OfflineFallback;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.falsity.horror.HorrorManager;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityPrompt;
import com.falsity.personality.PersonalityPromptStore;
import com.falsity.personality.StageDirections;
import com.falsity.world.WorldService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The personality system on a real server: the personality file, the horror
 * stage's voice and the relationship all reach the request a conversation
 * sends, and none of it disturbs what was there before.
 *
 * <p>As in the Groq pipeline tests, only the network is replaced. Each test has
 * its own conversation manager, its own personality file in a scratch folder,
 * and its own copy of the settings, so nothing here changes the running mod's
 * configuration or its real personality file.
 */
public class FalsityPersonalityGameTest {
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);
	private static final String KEY = "gsk_gametest0123456789abcdefghij";
	private static final String CUSTOM =
		"You are Falsity, a stern little guardian who speaks in clipped sentences and counts everything twice.";
	private static final String OTHER = "You are Falsity, a sleepy creature who answers slowly and loves the rain.";
	private static final long DAY = 24L * 60L * 60L * 1000L;

	/** Answers from a script instead of the network, and remembers what it was sent. */
	private static final class Script implements GroqClient.Transport {
		private final List<String> bodies = new ArrayList<>();
		private final Deque<String> answers = new ArrayDeque<>();

		synchronized Script answer(String content) {
			this.answers.add(content);
			return this;
		}

		@Override
		public synchronized CompletableFuture<GroqClient.HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout) {
			this.bodies.add(jsonBody);
			String content = this.answers.poll();
			JsonObject message = new JsonObject();
			message.addProperty("role", "assistant");
			message.addProperty("content", content == null ? "Mm." : content);
			JsonObject choice = new JsonObject();
			choice.addProperty("index", 0);
			choice.addProperty("finish_reason", "stop");
			choice.add("message", message);
			JsonArray choices = new JsonArray();
			choices.add(choice);
			JsonObject body = new JsonObject();
			body.addProperty("id", "chatcmpl-gametest");
			body.add("choices", choices);
			return CompletableFuture.completedFuture(new GroqClient.HttpResult(200, body.toString()));
		}

		synchronized int requests() {
			return this.bodies.size();
		}

		synchronized String body(int request) {
			return this.bodies.get(request);
		}

		/** The system prompt of one request. */
		synchronized String system(int request) {
			JsonArray messages = JsonParser.parseString(this.bodies.get(request)).getAsJsonObject().getAsJsonArray("messages");
			return messages.get(0).getAsJsonObject().get("content").getAsString();
		}

		/** The text of every message in one request, system prompt first, the new message last. */
		synchronized List<String> contents(int request) {
			List<String> contents = new ArrayList<>();
			JsonArray messages = JsonParser.parseString(this.bodies.get(request)).getAsJsonObject().getAsJsonArray("messages");
			for (JsonElement message : messages) {
				contents.add(message.getAsJsonObject().get("content").getAsString());
			}
			return contents;
		}
	}

	/** A bonded player and companion with their own settings, horror manager, personality file and scripted Groq. */
	private record Scene(
		ServerPlayer player,
		FalsityEntity falsity,
		ConversationManager talk,
		Script groq,
		AtomicLong clock,
		FalsityConfig config,
		HorrorManager horror,
		PersonalityPromptStore prompts
	) {
		UUID uuid() {
			return this.player.getUUID();
		}

		String name() {
			return this.player.getName().getString();
		}

		PlayerMemory memory() {
			return FalsityMod.memories().getOrCreate(uuid(), name());
		}

		ConversationManager.Outcome say(String message) {
			return this.talk.requestConversation(this.player, this.falsity, message);
		}

		List<String> replies() {
			List<String> replies = new ArrayList<>();
			for (ConversationHistory.Turn turn : this.talk.history().recent(uuid(), 12, this.clock.get())) {
				replies.add(turn.falsity());
			}
			return replies;
		}

		void personality(String contents) {
			try {
				Files.createDirectories(this.prompts.file().getParent());
				Files.writeString(this.prompts.file(), contents, StandardCharsets.UTF_8);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
			this.prompts.load();
		}
	}

	// ---- the file -------------------------------------------------------------

	@GameTest
	public void personalityFileExistsOnceTheModHasStarted(final GameTestHelper helper) {
		PersonalityPromptStore store = FalsityMod.personalityPrompts();
		Path file = store.file();

		helper.assertTrue(file != null, "The running mod should read its personality from a file");
		helper.assertTrue(file.endsWith(Path.of("falsity", "personality.txt")), "The file should be config/falsity/personality.txt, was " + file);
		helper.assertTrue(Files.isRegularFile(file), "Starting the mod should have created " + file);
		PersonalityPrompt current = store.current();
		helper.assertTrue(!current.text().isBlank(), "There is always a personality");
		helper.assertTrue(current.text().length() <= PersonalityPromptStore.MAX_CHARS, "The personality should be held to its limit");
		helper.assertTrue(!current.describe().contains(current.text()), "What is reported about the file must not quote it");

		// The default that ships inside the mod file was found there, not replaced by the short stand-in.
		helper.assertTrue(PersonalityPromptStore.defaultText().contains("Outlook:"),
			"The bundled default personality should have been read from the mod: " + PersonalityPromptStore.defaultText());
		helper.assertTrue(PersonalityPromptStore.defaultFileContents().contains("/falsity personality reload"),
			"The bundled default file should carry its notes for the owner");

		// The reload a command runs: it reads the same file again and never fails.
		PersonalityPrompt reloaded = FalsityMod.reloadPersonality();
		helper.assertTrue(reloaded.text().equals(current.text()), "Reloading an unchanged file should change nothing");
		helper.succeed();
	}

	@GameTest
	public void missingFileIsCreatedAndEmptyFileFallsBack(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_file", true, 1L);
		Path file = scene.prompts().file();

		helper.assertTrue(Files.isRegularFile(file), "A missing personality file should be created, looked for " + file);
		helper.assertTrue(scene.prompts().current().text().equals(PersonalityPromptStore.defaultText()),
			"A fresh file should hold the default personality");

		scene.personality("");
		helper.assertTrue(scene.prompts().current().fellBack(), "An empty file should fall back to the default");
		scene.groq().answer("Hello.");
		scene.say("how was your day?");
		helper.assertTrue(scene.groq().requests() == 1, "An empty personality file must not stop the request, sent " + scene.groq().requests());
		helper.assertTrue(scene.groq().system(0).contains(PersonalityPromptStore.defaultText()),
			"With an empty file the default personality should be sent: " + scene.groq().system(0));
		helper.succeed();
	}

	// ---- what reaches Groq ------------------------------------------------------

	@GameTest
	public void customPersonalityIsSentAndTheReplyIsTheModelsOwn(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_custom", true, 1L);
		scene.personality("# a note that must not be sent\n" + CUSTOM + "\n");
		scene.groq().answer("Two clouds. I counted them twice.");

		var outcome = scene.say("how was your day?");
		helper.assertTrue(outcome == ConversationManager.Outcome.ACCEPTED, "The message should be accepted, was " + outcome);

		// The request is built and handed to the transport before the call returns.
		helper.assertTrue(scene.groq().requests() == 1, "One message is one request, sent " + scene.groq().requests());
		String system = scene.groq().system(0);
		helper.assertTrue(system.startsWith("You are Falsity"), "The prompt should open with who is speaking: " + system);
		helper.assertTrue(system.contains(CUSTOM), "The personality file's text should be in the request: " + system);
		helper.assertTrue(!system.contains("a note that must not be sent"), "Notes in the file must stay in the file: " + system);
		helper.assertTrue(!system.contains("small plush creature"), "The default personality should be replaced, not added to: " + system);
		helper.assertTrue(system.contains(StageDirections.forStage(HorrorStage.INNOCENCE)), "A new relationship speaks in the first stage's voice: " + system);
		helper.assertTrue(system.contains(scene.name() + " is your owner"), "The prompt should name the owner: " + system);

		int rules = system.indexOf("\nRules:\n");
		int personality = system.indexOf(CUSTOM);
		int stage = system.indexOf(StageDirections.forStage(HorrorStage.INNOCENCE));
		int relationship = system.indexOf(scene.name() + " is your owner");
		int memory = system.indexOf("\n\nWhat you remember");
		int world = system.indexOf("Verified facts (checked by the game just now): You and the player are in ");
		helper.assertTrue(0 < rules && rules < personality && personality < stage && stage < relationship && relationship < memory && memory < world,
			"The layers should come as rules, personality, stage, relationship, memory, world: " + system);
		List<String> sent = scene.groq().contents(0);
		helper.assertTrue("how was your day?".equals(sent.get(sent.size() - 1)), "The player's words come last: " + sent);
		helper.assertTrue(!scene.groq().body(0).contains(KEY), "The key must never be part of the request body");

		helper.succeedWhen(() -> helper.assertTrue(scene.replies().equals(List.of("Two clouds. I counted them twice.")),
			"The player should hear the model's own words and nothing canned, heard " + scene.replies()));
	}

	@GameTest
	public void horrorStageDecidesTheVoice(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_stage", true, 1L);
		scene.personality(CUSTOM);
		helper.assertTrue(scene.horror().stageFor(scene.uuid(), scene.name()) == HorrorStage.INNOCENCE, "A new relationship starts at Innocence");

		// The horror system's own progression: tension from interactions, Strange from twenty.
		for (int index = 0; index < 25; index++) {
			scene.horror().recordInteraction(scene.uuid(), scene.name());
		}
		helper.assertTrue(scene.horror().stageFor(scene.uuid(), scene.name()) == HorrorStage.STRANGE,
			"Twenty-five interactions should reach Strange, was " + scene.horror().stageFor(scene.uuid(), scene.name()));

		scene.groq().answer("You hummed that tune on the first day too.");
		scene.say("how was your day?");

		String system = scene.groq().system(0);
		helper.assertTrue(system.contains(StageDirections.forStage(HorrorStage.STRANGE)), "At Strange the request should carry that stage's directions: " + system);
		helper.assertTrue(!system.contains(StageDirections.forStage(HorrorStage.INNOCENCE)), "And not the first stage's: " + system);
		helper.assertTrue(system.contains(CUSTOM), "The personality is still there under the stage: " + system);
		helper.assertTrue(system.indexOf(CUSTOM) < system.indexOf(StageDirections.forStage(HorrorStage.STRANGE)),
			"The stage comes after the personality it changes: " + system);
		helper.succeed();
	}

	@GameTest
	public void everyStageHasAVoiceOnceTheCapAllowsIt(final GameTestHelper helper) {
		// Each stage gets its own player and its own settings with the horror system's cap
		// lifted, so the mod's real settings (capped at Strange) are never touched.
		for (HorrorStage stage : HorrorStage.values()) {
			Scene scene = scene(helper, "falsity_p_s" + stage.ordinal(), true, 1L);
			scene.config().horror.maxStage = HorrorStage.NIGHTMARE.ordinal();
			scene.personality(CUSTOM);
			HorrorStage set = scene.horror().setStage(scene.uuid(), scene.name(), stage.ordinal());
			helper.assertTrue(set == stage, "The stage should have been set to " + stage + ", was " + set);

			scene.say("how was your day?");

			helper.assertTrue(scene.groq().requests() == 1, stage + ": one message is one request, sent " + scene.groq().requests());
			String system = scene.groq().system(0);
			helper.assertTrue(system.contains(StageDirections.forStage(stage)), stage + " should speak in its own voice: " + system);
			helper.assertTrue(system.contains(CUSTOM), stage + " should still carry the personality: " + system);
			helper.assertTrue(system.contains("Never invent a memory"), stage + " should still carry the rules: " + system);
		}
		helper.assertTrue(FalsityMod.config().horror.maxStage <= 1, "The mod's own cap must be untouched, was " + FalsityMod.config().horror.maxStage);
		helper.succeed();
	}

	@GameTest
	public void unsettlingSwitchKeepsTheVoiceNormalAndTheStageWhereItWas(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_switch", true, 1L);
		scene.horror().setStage(scene.uuid(), scene.name(), HorrorStage.STRANGE.ordinal());
		scene.config().horror.allowUnsettlingLines = false;

		scene.say("how was your day?");

		String system = scene.groq().system(0);
		helper.assertTrue(system.contains(StageDirections.forStage(HorrorStage.INNOCENCE)),
			"With unsettling lines switched off the voice should stay normal: " + system);
		helper.assertTrue(!system.contains("unsettling"), "Nothing unsettling should be asked for: " + system);
		helper.assertTrue(scene.horror().stageFor(scene.uuid(), scene.name()) == HorrorStage.STRANGE,
			"The stage itself must not move, was " + scene.horror().stageFor(scene.uuid(), scene.name()));
		helper.succeed();
	}

	// ---- changing the personality ----------------------------------------------

	@GameTest
	public void changingThePersonalityKeepsHorrorProgressAndTakesEffectAtOnce(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_change", true, 1L);
		scene.personality(CUSTOM);
		for (int index = 0; index < 25; index++) {
			scene.horror().recordInteraction(scene.uuid(), scene.name());
		}
		PlayerMemory memory = scene.memory();
		helper.assertTrue(memory.horror.stage == HorrorStage.STRANGE.ordinal(), "The relationship should be at Strange, was " + memory.horror.stage);
		int interactionsAtStart = memory.horror.interactionCount;
		double tensionAtStart = memory.horror.tension;

		scene.groq().answer("First answer.").answer("Second answer.");
		scene.say("how was your day?");
		helper.assertTrue(scene.groq().system(0).contains(CUSTOM), "The first request should use the first personality");

		// Every way the file can change, then a new text: none of it may touch the player's state.
		int stage = memory.horror.stage;
		double tension = memory.horror.tension;
		int interactions = memory.horror.interactionCount;
		long stageChangedAt = memory.horror.lastStageChangeEpochMillis;
		double curiosity = memory.personality.curiosity;
		double attachment = memory.personality.attachment;
		int conversations = memory.conversationCount;
		scene.personality("");
		helper.assertTrue(scene.prompts().current().fellBack(), "An empty file should fall back to the default");
		scene.personality("# only a note\n");
		scene.personality(OTHER);
		helper.assertTrue(scene.prompts().current().custom(), "The new text should be in use: " + scene.prompts().current().describe());
		helper.assertTrue(memory.horror.stage == stage, "Changing the personality must not reset the stage: " + stage + " became " + memory.horror.stage);
		helper.assertTrue(memory.horror.tension == tension, "Changing the personality must not change tension: " + tension + " became " + memory.horror.tension);
		helper.assertTrue(memory.horror.interactionCount == interactions, "Changing the personality must not change the interaction count");
		helper.assertTrue(memory.horror.lastStageChangeEpochMillis == stageChangedAt, "Changing the personality must not count as a stage change");
		helper.assertTrue(memory.personality.curiosity == curiosity && memory.personality.attachment == attachment,
			"Changing the personality text must not reset the personality values");
		helper.assertTrue(memory.conversationCount == conversations, "Changing the personality must not change what is remembered");

		var second = scene.say("and what about tomorrow?");
		helper.assertTrue(second == ConversationManager.Outcome.QUEUED, "The second message should wait its turn, was " + second);
		scene.clock().addAndGet(4_000L);

		helper.succeedWhen(() -> {
			scene.talk().tick();
			helper.assertTrue(scene.groq().requests() == 2, "Both messages should have been sent, sent " + scene.groq().requests());
			String system = scene.groq().system(1);
			helper.assertTrue(system.contains(OTHER), "The next request should use the new personality: " + system);
			helper.assertTrue(!system.contains(CUSTOM), "And not the old one: " + system);
			helper.assertTrue(system.contains(StageDirections.forStage(HorrorStage.STRANGE)), "At the same stage as before: " + system);
			helper.assertTrue(scene.replies().equals(List.of("First answer.", "Second answer.")), "Both answers should be heard, heard " + scene.replies());
			helper.assertTrue(memory.horror.stage == HorrorStage.STRANGE.ordinal(), "Still Strange after talking, was " + memory.horror.stage);
			helper.assertTrue(memory.horror.interactionCount == interactionsAtStart + 2,
				"Progress should carry on from where it was: " + interactionsAtStart + " then " + memory.horror.interactionCount);
			helper.assertTrue(memory.horror.tension == tensionAtStart + 2.0,
				"Tension should carry on from where it was: " + tensionAtStart + " then " + memory.horror.tension);
		});
	}

	// ---- relationship -------------------------------------------------------------

	@GameTest
	public void returnAfterDaysAwayIsToldToTheModelAndRemembered(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_away", true, 1L);
		PlayerMemory memory = scene.memory();
		memory.lastSeenEpochMillis = scene.clock().get() - 3L * DAY - 60_000L;

		scene.say("I'm back");

		String system = scene.groq().system(0);
		helper.assertTrue(system.contains("They have just come back after 3 days away."), "The absence should be in the request: " + system);
		helper.assertTrue(memory.lastAbsenceDays == 3L, "The absence should be remembered, was " + memory.lastAbsenceDays);
		helper.assertTrue(memory.recentInteractions.contains("Player returned after 3 days away."),
			"The return should be noted the way the horror system words it: " + memory.recentInteractions);
		helper.assertTrue(memory.daysSinceSeen(scene.clock().get()) == 0L, "The player has now been seen");
		helper.succeed();
	}

	@GameTest
	public void kindAndHarshWordsAreRemembered(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_words", false, 1L);
		PlayerMemory memory = scene.memory();
		String before = memory.treatment;

		// Without Groq every reply is immediate, so several messages can be said in one go.
		scene.say("you're so stupid");
		helper.assertTrue(memory.treatment.equals(before + "H") && memory.harshWords == 1,
			"Harsh words should be remembered as such: " + memory.treatment + ", " + memory.harshWords);
		scene.say("sorry");
		scene.say("I love you");
		helper.assertTrue(memory.treatment.equals(before + "HKK") && memory.kindWords == 2,
			"Kind words should be remembered as such: " + memory.treatment + ", " + memory.kindWords);
		helper.assertTrue(scene.replies().size() == 3, "Every message should have been answered, heard " + scene.replies());
		helper.assertTrue(scene.groq().requests() == 0, "With Groq off nothing should be sent, sent " + scene.groq().requests());
		helper.succeed();
	}

	@GameTest
	public void renamedCompanionIsToldItsName(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_p_name", true, 1L);
		scene.falsity().setCustomName(Component.literal("Mimi"));

		scene.say("how was your day?");

		String system = scene.groq().system(0);
		helper.assertTrue(system.startsWith("You are Mimi, "), "A renamed companion should be told its name: " + system);
		helper.assertTrue(system.endsWith("Now answer the player's next message as Mimi."), "And answer under it: " + system);
		helper.succeed();
	}

	// ---- offline --------------------------------------------------------------------

	@GameTest
	public void offlineVoiceFollowsTheStageAndItsSwitch(final GameTestHelper helper) {
		// With this seed the offline voice's first draw at Strange is "bring up something remembered".
		long seed = 8_000_024L;

		Scene strange = scene(helper, "falsity_p_off1", false, seed);
		strange.personality(CUSTOM);
		strange.memory().recordFact("Player said: I like building towers", 50);
		strange.horror().setStage(strange.uuid(), strange.name(), HorrorStage.STRANGE.ordinal());
		strange.say("hello");
		List<String> heard = strange.replies();
		helper.assertTrue(heard.size() == 1 && heard.get(0).startsWith("I still remember... "),
			"At Strange the offline voice should bring up something remembered, heard " + heard);
		String recalled = heard.get(0).substring("I still remember... ".length(), heard.get(0).length() - 1);
		boolean real = false;
		for (String fact : strange.memory().facts) {
			real |= fact.equalsIgnoreCase(recalled);
		}
		helper.assertTrue(real, "What it brings up must be something that was really stored, was '" + recalled + "' of " + strange.memory().facts);

		Scene switchedOff = scene(helper, "falsity_p_off2", false, seed);
		switchedOff.memory().recordFact("Player said: I like building towers", 50);
		switchedOff.horror().setStage(switchedOff.uuid(), switchedOff.name(), HorrorStage.STRANGE.ordinal());
		switchedOff.config().horror.allowUnsettlingLines = false;
		switchedOff.say("hello");
		helper.assertTrue(switchedOff.replies().size() == 1 && !switchedOff.replies().get(0).startsWith("I still remember"),
			"With unsettling lines switched off the offline voice should stay ordinary, heard " + switchedOff.replies());

		Scene innocent = scene(helper, "falsity_p_off3", false, seed);
		innocent.memory().recordFact("Player said: I like building towers", 50);
		innocent.say("hello");
		helper.assertTrue(innocent.replies().size() == 1 && !innocent.replies().get(0).startsWith("I still remember"),
			"At Innocence the offline voice should stay ordinary, heard " + innocent.replies());
		helper.assertTrue(strange.groq().requests() + switchedOff.groq().requests() + innocent.groq().requests() == 0,
			"With Groq off nothing should be sent");
		helper.succeed();
	}

	private static Scene scene(final GameTestHelper helper, String playerName, boolean groqOn, long seed) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atCenterOf(helper.absolutePos(SPAWN));

		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);

		ServerPlayer player = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), playerName));
		player.setPos(position.x + 1.0, position.y, position.z);
		player.getInventory().clearContent();
		falsity.bondTo(player);

		// Private settings and a private horror manager over the mod's real memory:
		// a test may lift the cap or flip a switch without changing the running mod.
		FalsityConfig config = new FalsityConfig();
		config.groq.enabled = groqOn;
		HorrorManager horror = new HorrorManager(FalsityMod.memories(), config);

		Path file;
		try {
			file = PersonalityPromptStore.fileIn(Files.createTempDirectory("falsity-personality-gametest"));
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
		PersonalityPromptStore prompts = new PersonalityPromptStore(file);
		prompts.load();

		Script script = new Script();
		AtomicLong clock = new AtomicLong(System.currentTimeMillis());
		ConversationManager talk = new ConversationManager(
			config,
			new GroqClient(script, new GroqClient.GroqRateLimiter()),
			new ApiKeyProvider(() -> KEY),
			FalsityMod.memories(),
			FalsityMod.personalities(),
			horror,
			new OfflineFallback(new Random(seed)),
			FalsityMod.gameDir(),
			clock::get
		);
		talk.setActionExecutor(FalsityMod.actions());
		talk.setItemCatalog(ItemAssistance.CATALOG);
		talk.setWorldFacts((asker, companion) -> WorldService.facts(asker, companion, config));
		talk.setPersonalityPrompts(prompts);
		return new Scene(player, falsity, talk, script, clock, config, horror, prompts);
	}
}
