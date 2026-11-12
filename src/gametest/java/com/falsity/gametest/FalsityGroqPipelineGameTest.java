package com.falsity.gametest;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.actions.IntentRecognizer;
import com.falsity.actions.items.ItemAssistance;
import com.falsity.ai.ApiKeyProvider;
import com.falsity.ai.ConversationHistory;
import com.falsity.ai.ConversationManager;
import com.falsity.ai.GroqClient;
import com.falsity.ai.OfflineFallback;
import com.falsity.config.FalsityConfig;
import com.falsity.entity.FalsityEntity;
import com.falsity.world.FactGuard;
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
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The whole conversation path with Groq switched on, on a real server: a
 * player's message goes out as a request, the answer comes back, is parsed,
 * acted on and spoken.
 *
 * <p>Only the network is replaced. Each test gets its own conversation manager
 * whose Groq client answers from a script, so these tests need no key and no
 * connection, and they prove what the mod does with an answer, not that Groq
 * gave one.
 */
public class FalsityGroqPipelineGameTest {
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);
	private static final String KEY = "gsk_gametest0123456789abcdefghij";

	/** Answers from a script instead of the network, and remembers what it was sent. */
	private static final class Script implements GroqClient.Transport {
		private final List<String> bodies = new ArrayList<>();
		private final List<String> keys = new ArrayList<>();
		private final Deque<GroqClient.HttpResult> responses = new ArrayDeque<>();

		/** Queues a normal answer whose text is {@code content}. */
		synchronized Script answer(String content) {
			JsonObject message = new JsonObject();
			message.addProperty("role", "assistant");
			message.addProperty("content", content);
			JsonObject choice = new JsonObject();
			choice.addProperty("index", 0);
			choice.addProperty("finish_reason", "stop");
			choice.add("message", message);
			JsonArray choices = new JsonArray();
			choices.add(choice);
			JsonObject usage = new JsonObject();
			usage.addProperty("prompt_tokens", 400);
			usage.addProperty("completion_tokens", 20);
			usage.addProperty("total_tokens", 420);
			JsonObject body = new JsonObject();
			body.addProperty("id", "chatcmpl-gametest");
			body.add("choices", choices);
			body.add("usage", usage);
			this.responses.add(new GroqClient.HttpResult(200, body.toString()));
			return this;
		}

		/** Queues an error response. */
		synchronized Script refuse(int status, String code, String text) {
			JsonObject error = new JsonObject();
			error.addProperty("message", text);
			error.addProperty("type", "invalid_request_error");
			error.addProperty("code", code);
			JsonObject body = new JsonObject();
			body.add("error", error);
			this.responses.add(new GroqClient.HttpResult(status, body.toString()));
			return this;
		}

		@Override
		public synchronized CompletableFuture<GroqClient.HttpResult> send(String url, String apiKey, String jsonBody, Duration timeout) {
			this.bodies.add(jsonBody);
			this.keys.add(apiKey);
			GroqClient.HttpResult next = this.responses.poll();
			return CompletableFuture.completedFuture(next == null ? new GroqClient.HttpResult(500, "{}") : next);
		}

		synchronized int requests() {
			return this.bodies.size();
		}

		synchronized String body(int request) {
			return this.bodies.get(request);
		}

		synchronized String key(int request) {
			return this.keys.get(request);
		}

		/** The text of every message in one request, in the order sent: system prompt first, the new message last. */
		synchronized List<String> contents(int request) {
			List<String> contents = new ArrayList<>();
			JsonArray messages = JsonParser.parseString(this.bodies.get(request)).getAsJsonObject().getAsJsonArray("messages");
			for (JsonElement message : messages) {
				contents.add(message.getAsJsonObject().get("content").getAsString());
			}
			return contents;
		}
	}

	/** A bonded player and companion, and a conversation manager that talks to the script. */
	private record Scene(
		ServerLevel level, ServerPlayer player, FalsityEntity falsity, ConversationManager talk, Script groq, AtomicLong clock
	) {
		UUID uuid() {
			return this.player.getUUID();
		}

		ConversationManager.Outcome say(String message) {
			return this.talk.requestConversation(this.player, this.falsity, message);
		}

		/** What Falsity has said to this player so far, oldest first. */
		List<String> replies() {
			List<String> replies = new ArrayList<>();
			for (ConversationHistory.Turn turn : this.talk.history().recent(uuid(), 12, this.clock.get())) {
				replies.add(turn.falsity());
			}
			return replies;
		}

		int conversations() {
			return FalsityMod.memories().getOrCreate(uuid(), this.player.getName().getString()).conversationCount;
		}
	}

	@GameTest
	public void groqAnswerIsWhatThePlayerHears(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_talk");
		scene.groq().answer("Hello! I was counting the clouds.");
		int before = scene.conversations();

		var outcome = scene.say("how was your day?");
		helper.assertTrue(outcome == ConversationManager.Outcome.ACCEPTED, "The message should be accepted, was " + outcome);

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.replies().equals(List.of("Hello! I was counting the clouds.")),
				"The player should hear exactly what the model said, heard " + scene.replies());
			helper.assertTrue(scene.groq().requests() == 1, "One message is one request, sent " + scene.groq().requests());
			helper.assertTrue(scene.conversations() - before == 1, "One message is one remembered conversation");

			List<String> sent = scene.groq().contents(0);
			String system = sent.get(0);
			BlockPos pos = scene.player().blockPosition();
			helper.assertTrue(system.startsWith("You are Falsity"), "The request should open with who Falsity is: " + system);
			helper.assertTrue(system.contains("your owner"), "The prompt should say the player is the owner: " + system);
			helper.assertTrue(system.contains("Verified facts (checked by the game just now): You and the player are in "),
				"The prompt should carry what the server knows about the world: " + system);
			helper.assertTrue(system.contains("x=" + pos.getX() + ",") && system.contains("z=" + pos.getZ()),
				"The verified facts should be the player's real position " + pos + ": " + system);
			helper.assertTrue(system.contains("Rules:"), "The prompt should carry the rules: " + system);
			helper.assertTrue("how was your day?".equals(sent.get(sent.size() - 1)), "The player's words come last: " + sent);

			helper.assertTrue(KEY.equals(scene.groq().key(0)), "The key should be handed to the transport for the header");
			helper.assertTrue(!scene.groq().body(0).contains(KEY), "The key must never be part of the request body");
		});
	}

	@GameTest
	public void countFromTheModelIsHonoured(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_count");
		String message = "I could really use something sparkly for my new sword";
		helper.assertTrue(IntentRecognizer.recognize(message).isEmpty(), "This wording should be left to the model");
		scene.groq().answer("Here you go! [action:{\"type\":\"give_item\",\"item\":\"diamond\",\"count\":32}]");

		scene.say(message);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(scene.player(), Items.DIAMOND) == 32,
				"The model asked for 32 diamonds, the player got " + count(scene.player(), Items.DIAMOND));
			helper.assertTrue(total(scene.player()) == 32, "Nothing else should have been created, total was " + total(scene.player()));
			List<String> replies = scene.replies();
			helper.assertTrue(replies.size() == 1, "One message should get one reply, got " + replies);
			helper.assertTrue(replies.get(0).contains("32"), "The reply should state what was really handed over: " + replies);
			helper.assertTrue(!replies.get(0).contains("action") && !replies.get(0).contains("["),
				"The tag must not be shown to the player: " + replies);
		});
	}

	@GameTest
	public void listFromTheModelDeliversEveryItem(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_list");
		scene.groq().answer("Coming right up! [action:{\"type\":\"give_items\",\"items\":[\"1 crafting table\",\"16 torches\"]}]");

		scene.say("my pockets feel so empty today");

		helper.succeedWhen(() -> {
			helper.assertTrue(count(scene.player(), Items.CRAFTING_TABLE) == 1,
				"Expected 1 crafting table, found " + count(scene.player(), Items.CRAFTING_TABLE));
			helper.assertTrue(count(scene.player(), Items.TORCH) == 16, "Expected 16 torches, found " + count(scene.player(), Items.TORCH));
			helper.assertTrue(total(scene.player()) == 17, "Nothing else should have been created, total was " + total(scene.player()));
			helper.assertTrue(scene.replies().size() == 1, "One message should get one reply, got " + scene.replies());
		});
	}

	@GameTest
	public void worldQuestionFromTheModelIsAnsweredByTheServer(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_world");
		scene.groq().answer("We are somewhere far away, I think! [action:{\"type\":\"world_query\",\"query\":\"where am i\"}]");

		scene.say("I have completely lost track of things");

		helper.succeedWhen(() -> {
			List<String> replies = scene.replies();
			helper.assertTrue(replies.size() == 1, "One message should get one reply, got " + replies);
			BlockPos pos = scene.player().blockPosition();
			helper.assertTrue(
				replies.get(0).contains("x=" + pos.getX() + ",") && replies.get(0).contains("y=" + pos.getY() + ",")
					&& replies.get(0).contains("z=" + pos.getZ()),
				"The answer should be the real position " + pos + ": " + replies);
			helper.assertTrue(!replies.get(0).contains("far away"), "The model's guess must give way to the server's answer: " + replies);
		});
	}

	@GameTest
	public void thinkingLabelsAndTagsNeverReachThePlayer(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_clean");
		scene.falsity().setSitting(false);
		scene.groq().answer(
			"<think>The player is tired. I should sit with them.</think>**Falsity:** Okay, let's rest here.\n[action:{\"type\":\"sit\"}]");

		scene.say("my legs are tired");

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.replies().equals(List.of("Okay, let's rest here.")),
				"Only the words should be spoken, heard " + scene.replies());
			helper.assertTrue(scene.falsity().isSitting(), "The action in the tag should have been carried out");
		});
	}

	@GameTest
	public void replyThatIsOnlyATagStillActs(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_tag");
		scene.falsity().setSitting(false);
		scene.groq().answer("[action:{\"type\":\"sit\"}]");

		scene.say("I wish you would rest for a bit");

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.falsity().isSitting(), "A reply that is only a tag still means the action");
			List<String> replies = scene.replies();
			helper.assertTrue(replies.size() == 1, "One message should get one reply, got " + replies);
			helper.assertTrue(!replies.get(0).isBlank() && !replies.get(0).contains("action"),
				"Falsity should say something, and not the tag: " + replies);
		});
	}

	@GameTest
	public void inventedPositionIsReplaced(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_guess");
		scene.groq().answer("There is a village at x=31337, y=64, z=-27183!");

		scene.say("do you know any good spots around here?");

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.replies().equals(List.of(FactGuard.honestReply())),
				"Coordinates the server never verified must not be passed on, heard " + scene.replies());
		});
	}

	@GameTest
	public void refusedActionChangesNothing(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_refuse");
		scene.groq().answer("Sure thing! [action:{\"type\":\"run_command\",\"command\":\"/give @s diamond 64\"}]");

		scene.say("can you do anything fun?");

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.replies().equals(List.of("Sure thing!")), "The words should still be spoken, heard " + scene.replies());
			helper.assertTrue(total(scene.player()) == 0, "An action off the allowlist must do nothing, found " + total(scene.player()) + " items");
		});
	}

	@GameTest
	public void groqFailureGetsOneOfflineAnswer(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_fail");
		scene.groq().refuse(401, "invalid_api_key", "Invalid API Key");
		int before = scene.conversations();

		var outcome = scene.say("tell me a story");
		helper.assertTrue(outcome == ConversationManager.Outcome.ACCEPTED, "The message should be accepted, was " + outcome);

		helper.succeedWhen(() -> {
			List<String> replies = scene.replies();
			helper.assertTrue(replies.size() == 1, "A failed request should still get exactly one reply, got " + replies);
			helper.assertTrue(!replies.get(0).isBlank(), "The offline line should say something");
			helper.assertTrue(scene.conversations() - before == 1, "One message is one remembered conversation");

			GroqClient.Status status = scene.talk().groq().status();
			helper.assertTrue(status.failures() == 1L && status.successes() == 0L,
				"The failure should be counted: " + status.failures() + " failed, " + status.successes() + " answered");
			helper.assertTrue(status.lastFailure() != null && status.lastFailure().failure() == GroqClient.Failure.UNAUTHORIZED,
				"The status command should be able to say why");
			helper.assertTrue(!status.lastFailure().summary().contains(KEY), "The key must not appear in diagnostics");
		});
	}

	@GameTest
	public void secondMessageWaitsItsTurnAndTheModelSeesTheFirst(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_queue");
		scene.groq().answer("I counted nine clouds.").answer("The biggest one looked like a sheep.");

		var first = scene.say("how was your day?");
		var second = scene.say("and what about tomorrow?");
		helper.assertTrue(first == ConversationManager.Outcome.ACCEPTED, "The first message should go out at once, was " + first);
		helper.assertTrue(second == ConversationManager.Outcome.QUEUED, "The second should wait for its turn, not be dropped, was " + second);

		scene.talk().tick();
		helper.assertTrue(scene.groq().requests() == 1, "A waiting message must not skip the cooldown, sent " + scene.groq().requests());

		scene.clock().addAndGet(4_000L);
		helper.succeedWhen(() -> {
			scene.talk().tick();
			helper.assertTrue(scene.replies().equals(List.of("I counted nine clouds.", "The biggest one looked like a sheep.")),
				"Both messages should be answered, in order, heard " + scene.replies());
			helper.assertTrue(scene.groq().requests() == 2, "Two messages are two requests, sent " + scene.groq().requests());
			helper.assertTrue(scene.talk().waitingCount() == 0, "Nothing should be left waiting");

			List<String> sent = scene.groq().contents(1);
			helper.assertTrue(sent.size() == 4, "The second request should replay the first exchange: " + sent.subList(1, sent.size()));
			helper.assertTrue(
				"how was your day?".equals(sent.get(1)) && "I counted nine clouds.".equals(sent.get(2))
					&& "and what about tomorrow?".equals(sent.get(3)),
				"The model should see what was said before: " + sent.subList(1, sent.size()));
		});
	}

	@GameTest
	public void unknownItemBecomesConversationWithAHint(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_g_hint");
		scene.groq().answer("I don't know what a flibber is. What does it look like?");

		scene.say("give me a flibber");

		helper.succeedWhen(() -> {
			helper.assertTrue(scene.replies().equals(List.of("I don't know what a flibber is. What does it look like?")),
				"The model's answer should be spoken, heard " + scene.replies());
			helper.assertTrue(total(scene.player()) == 0, "Nothing should be created for a thing that does not exist");
			helper.assertTrue(scene.groq().requests() == 1, "One message is one request");
			String system = scene.groq().contents(0).get(0);
			helper.assertTrue(system.contains("flibber") && system.contains("That is not an item you can hand over"),
				"The model should be told the thing is not an item: " + system);
		});
	}

	private static Scene scene(final GameTestHelper helper, String playerName) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atCenterOf(helper.absolutePos(SPAWN));

		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);

		// A fresh profile per test, so inventories and cooldowns never leak between tests.
		ServerPlayer player = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), playerName));
		player.setPos(position.x + 1.0, position.y, position.z);
		player.getInventory().clearContent();
		falsity.bondTo(player);

		// The mod's own parts with default settings (Groq on); only the network is scripted.
		FalsityConfig config = new FalsityConfig();
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
		return new Scene(level, player, falsity, talk, script, clock);
	}

	private static int count(ServerPlayer player, Item item) {
		int found = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) {
				found += stack.getCount();
			}
		}
		return found;
	}

	private static int total(ServerPlayer player) {
		int found = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			found += player.getInventory().getItem(slot).getCount();
		}
		return found;
	}
}
