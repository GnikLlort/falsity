package com.falsity.gametest;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.actions.items.ItemAssistance;
import com.falsity.ai.ApiKeyProvider;
import com.falsity.ai.ConversationHistory;
import com.falsity.ai.ConversationManager;
import com.falsity.ai.GroqClient;
import com.falsity.ai.InitiativePacer;
import com.falsity.ai.OfflineFallback;
import com.falsity.ai.PromptAssembler;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.falsity.memory.PlayerMemory;
import com.falsity.world.WorldService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Falsity speaking without having been spoken to, on a real server: rarely,
 * only with Groq, never to someone who is not answering, and never with an
 * action attached.
 *
 * <p>Time is the test's own clock, moved forward by hand, so minutes pass in
 * no time. Only the network is replaced.
 */
public class FalsitySpeaksFirstGameTest {
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);
	private static final String KEY = "gsk_gametest0123456789abcdefghij";
	private static final long MINUTE = 60_000L;

	/** Long enough for a moment to have come round whatever slack was added: the interval and a half, and a bit. */
	private static final long A_WHILE = 16L * MINUTE;

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

		/** The text of every message in one request, system prompt first. */
		synchronized List<String> contents(int request) {
			List<String> contents = new ArrayList<>();
			JsonArray messages = JsonParser.parseString(this.bodies.get(request)).getAsJsonObject().getAsJsonArray("messages");
			for (JsonElement message : messages) {
				contents.add(message.getAsJsonObject().get("content").getAsString());
			}
			return contents;
		}
	}

	private record Scene(
		ServerPlayer player, FalsityEntity falsity, ConversationManager talk, Script groq, AtomicLong clock, FalsityConfig config
	) {
		UUID uuid() {
			return this.player.getUUID();
		}

		PlayerMemory memory() {
			return FalsityMod.memories().getOrCreate(uuid(), this.player.getName().getString());
		}

		boolean offer() {
			return this.talk.offerToSpeakFirst(this.player, this.falsity);
		}

		/** Looks once so the clock starts, lets time pass, and offers the moment. */
		boolean offerAfterAWhile() {
			offer();
			this.clock.addAndGet(A_WHILE);
			return offer();
		}

		/** Everything Falsity has said to this player, oldest first, with what the player's side of each turn was. */
		List<ConversationHistory.Turn> turns() {
			return this.talk.history().recent(uuid(), 12, this.clock.get());
		}

		int itemsHeld() {
			int found = 0;
			for (int slot = 0; slot < this.player.getInventory().getContainerSize(); slot++) {
				found += this.player.getInventory().getItem(slot).getCount();
			}
			return found;
		}
	}

	@GameTest
	public void falsitySpeaksFirstOnlyOnceItsMomentHasCome(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_first", true);
		scene.groq().answer("The sun is going down. Are we staying out here?");
		PlayerMemory memory = scene.memory();
		int conversations = memory.conversationCount;
		int interactions = memory.horror.interactionCount;
		double tension = memory.horror.tension;

		helper.assertTrue(!scene.offer(), "Falsity must not speak the moment it first sees its owner");
		scene.clock().addAndGet(2L * MINUTE);
		helper.assertTrue(!scene.offer(), "Nor two minutes later");
		helper.assertTrue(scene.groq().requests() == 0, "Nothing should have been sent yet, sent " + scene.groq().requests());

		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(scene.offer(), "After a good while of quiet Falsity should take its moment");
		helper.assertTrue(scene.groq().requests() == 1, "A remark is one request, sent " + scene.groq().requests());
		List<String> sent = scene.groq().contents(0);
		helper.assertTrue(PromptAssembler.SPEAK_FIRST_CUE.equals(sent.get(sent.size() - 1)),
			"Where the player's message would be there should only be the request to speak: " + sent.get(sent.size() - 1));
		helper.assertTrue(sent.get(0).startsWith("You are Falsity") && sent.get(0).contains("is your owner"),
			"The remark should be built from the same prompt as any reply: " + sent.get(0));
		helper.assertTrue(!scene.offer(), "And it must not speak again straight away");

		helper.succeedWhen(() -> {
			List<ConversationHistory.Turn> turns = scene.turns();
			helper.assertTrue(turns.size() == 1 && turns.get(0).falsity().equals("The sun is going down. Are we staying out here?"),
				"The player should hear the remark, heard " + turns);
			helper.assertTrue(turns.get(0).player().equals(PromptAssembler.SILENCE), "It should be on record as unprompted: " + turns);
			helper.assertTrue(memory.recentInteractions.contains("Falsity: The sun is going down. Are we staying out here?"),
				"The remark should be remembered: " + memory.recentInteractions);
			helper.assertTrue(memory.conversationCount == conversations, "A remark nobody asked for is not a conversation");
			helper.assertTrue(memory.horror.interactionCount == interactions && memory.horror.tension == tension,
				"Nor does it move the horror system: the player has not done anything");
			helper.assertTrue(scene.groq().requests() == 1, "Still only one request, sent " + scene.groq().requests());
		});
	}

	@GameTest
	public void speakingFirstCanBeSwitchedOff(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_off", true);
		scene.config().chat.speaksFirst = false;

		helper.assertTrue(!scene.offerAfterAWhile(), "With chat.speaksFirst off Falsity must stay quiet");
		scene.clock().addAndGet(10L * A_WHILE);
		helper.assertTrue(!scene.offer(), "However long it waits");
		helper.assertTrue(scene.groq().requests() == 0, "Nothing should be sent, sent " + scene.groq().requests());
		helper.assertTrue(scene.turns().isEmpty(), "And nothing said: " + scene.turns());
		helper.succeed();
	}

	@GameTest
	public void withoutGroqFalsityNeverSpeaksFirst(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_nogroq", false);

		helper.assertTrue(!scene.offerAfterAWhile(), "Without Groq there are no words of its own to say");
		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(!scene.offer(), "Still nothing later");
		helper.assertTrue(scene.groq().requests() == 0, "Nothing should be sent, sent " + scene.groq().requests());
		helper.assertTrue(scene.turns().isEmpty(), "No line written in advance may stand in: " + scene.turns());
		helper.succeed();
	}

	@GameTest
	public void remarkNobodyAskedForCannotCarryOutAnAction(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_tag", true);
		scene.falsity().setSitting(false);
		scene.groq().answer("Here, take these. [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"count\":64}]");

		helper.assertTrue(scene.offerAfterAWhile(), "Falsity should take its moment");

		helper.succeedWhen(() -> {
			List<ConversationHistory.Turn> turns = scene.turns();
			helper.assertTrue(turns.size() == 1 && turns.get(0).falsity().equals("Here, take these."),
				"Only the words should be said, heard " + turns);
			helper.assertTrue(scene.itemsHeld() == 0, "An unprompted remark must never hand anything over, found " + scene.itemsHeld() + " items");
		});
	}

	@GameTest
	public void remarkThatIsOnlyATagSaysNothing(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_only", true);
		scene.falsity().setSitting(false);
		scene.groq().answer("[action:{\"type\":\"sit\"}]");

		helper.assertTrue(scene.offerAfterAWhile(), "Falsity should take its moment");

		helper.assertTrue(scene.groq().requests() == 1, "The request should have gone out, sent " + scene.groq().requests());
		helper.assertTrue(scene.turns().isEmpty(), "A tag with no words is nothing to say: " + scene.turns());
		helper.assertTrue(!scene.falsity().isSitting(), "And the action in it must not be carried out");
		helper.succeed();
	}

	@GameTest
	public void afterTwoIgnoredRemarksFalsityStaysQuietUntilThePlayerResponds(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_ignored", true);
		scene.groq().answer("First remark.").answer("Second remark.").answer("Third remark.");

		helper.assertTrue(scene.offerAfterAWhile(), "The first remark should be made");
		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(scene.offer(), "And a second, a good while later");
		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(!scene.offer(), "But not a third to someone who has done nothing in return");
		scene.clock().addAndGet(20L * A_WHILE);
		helper.assertTrue(!scene.offer(), "However long it waits");
		helper.assertTrue(scene.groq().requests() == InitiativePacer.MAX_UNANSWERED,
			"Only two requests should have been spent, sent " + scene.groq().requests());

		// The player pets it: they are there after all.
		scene.memory().touch(scene.clock().get());
		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(scene.offer(), "Once the player has done something, Falsity may speak up again");
		helper.assertTrue(scene.groq().requests() == 3, "Three requests in all, sent " + scene.groq().requests());
		helper.succeed();
	}

	@GameTest
	public void conversationInProgressIsNotInterruptedAndTheRemarkIsHeardInTheNextRequest(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_talk", true);
		scene.groq().answer("Hello. I was counting.").answer("It is very quiet today.").answer("Nine. One left.");
		scene.offer();
		scene.clock().addAndGet(A_WHILE);

		// The player talks first: Falsity answers, and does not then pile a remark on top.
		scene.talk().requestConversation(scene.player(), scene.falsity(), "how was your day?");
		scene.clock().addAndGet(30_000L);
		helper.assertTrue(!scene.offer(), "Half a minute after talking is no time to volunteer a remark");
		helper.assertTrue(scene.groq().requests() == 1, "Only the conversation should have been sent, sent " + scene.groq().requests());

		scene.clock().addAndGet(A_WHILE);
		helper.assertTrue(scene.offer(), "Once it has been quiet for a while, Falsity may speak");
		scene.clock().addAndGet(20_000L);
		scene.talk().requestConversation(scene.player(), scene.falsity(), "how many clouds were there?");

		helper.succeedWhen(() -> {
			scene.talk().tick();
			helper.assertTrue(scene.groq().requests() == 3, "A conversation, a remark and an answer: three requests, sent " + scene.groq().requests());
			List<String> sent = scene.groq().contents(2);
			helper.assertTrue(sent.size() == 6, "The last request should replay both earlier turns: " + sent.subList(1, sent.size()));
			helper.assertTrue(
				"how was your day?".equals(sent.get(1)) && "Hello. I was counting.".equals(sent.get(2))
					&& PromptAssembler.SILENCE.equals(sent.get(3)) && "It is very quiet today.".equals(sent.get(4))
					&& "how many clouds were there?".equals(sent.get(5)),
				"The model should see its own unprompted remark when the player answers it: " + sent.subList(1, sent.size()));
		});
	}

	@GameTest
	public void speakingFirstOpensTheConversation(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_f_open", true);
		AtomicInteger opened = new AtomicInteger();
		scene.talk().setSpokeFirstListener((player, falsity) -> {
			if (player == scene.player() && falsity == scene.falsity()) {
				opened.incrementAndGet();
			}
		});
		scene.groq().answer("Do you hear that?");

		helper.assertTrue(scene.offerAfterAWhile(), "Falsity should take its moment");

		helper.succeedWhen(() -> helper.assertTrue(opened.get() == 1,
			"Whoever is listening should be told once that Falsity spoke first, was told " + opened.get() + " times"));
	}

	private static Scene scene(final GameTestHelper helper, String playerName, boolean groqOn) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atCenterOf(helper.absolutePos(SPAWN));

		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);

		ServerPlayer player = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), playerName));
		player.setPos(position.x + 1.0, position.y, position.z);
		player.getInventory().clearContent();
		falsity.bondTo(player);

		// Private settings, so a test can flip a switch without changing the running mod.
		FalsityConfig config = new FalsityConfig();
		config.groq.enabled = groqOn;
		Script script = new Script();
		AtomicLong clock = new AtomicLong(System.currentTimeMillis());
		ConversationManager talk = new ConversationManager(
			config,
			new GroqClient(script, new GroqClient.GroqRateLimiter()),
			new ApiKeyProvider(() -> KEY),
			FalsityMod.memories(),
			FalsityMod.personalities(),
			FalsityMod.horror(),
			new OfflineFallback(),
			FalsityMod.gameDir(),
			clock::get
		);
		talk.setActionExecutor(FalsityMod.actions());
		talk.setItemCatalog(ItemAssistance.CATALOG);
		talk.setWorldFacts((asker, companion) -> WorldService.facts(asker, companion, config));
		return new Scene(player, falsity, talk, script, clock, config);
	}
}
