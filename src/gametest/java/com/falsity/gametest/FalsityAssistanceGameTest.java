package com.falsity.gametest;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.actions.ActionContext;
import com.falsity.actions.ActionRequest;
import com.falsity.actions.ActionResult;
import com.falsity.actions.ActionType;
import com.falsity.actions.IntentRecognizer;
import com.falsity.ai.ConversationManager;
import com.falsity.entity.FalsityEntity;
import com.falsity.world.WorldAnswers;
import com.falsity.world.WorldService;
import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * In-game checks for item requests, world questions and conversation
 * delivery, run against a real server with a real (fake-connection) player.
 *
 * <p>Nothing here touches the network: Groq is switched off for the tests that
 * go through the conversation manager, so they pass with no key and no
 * connection.
 */
public class FalsityAssistanceGameTest {
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);

	/** A bonded player and companion standing in the test area. */
	private record Scene(ServerLevel level, ServerPlayer player, FalsityEntity falsity) {
		ActionContext context() {
			return new ActionContext(this.player, this.falsity, this.level, FalsityMod.config());
		}

		ActionResult run(String message) {
			ActionRequest request = IntentRecognizer.recognize(message).orElseThrow(
				() -> new IllegalStateException("Not recognised as a request: " + message));
			return FalsityMod.actions().execute(context(), request);
		}
	}

	@GameTest
	public void multiItemRequestDeliversEveryItem(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_multi");

		ActionRequest request = IntentRecognizer.recognize("give me a crafting table and 64 sticks").orElseThrow();
		helper.assertTrue(request.type() == ActionType.GIVE_ITEMS, "Two things asked for should be one multi-item request, was " + request.type());

		ActionResult result = FalsityMod.actions().execute(scene.context(), request);
		helper.assertTrue(result.success(), "The request should succeed: " + result.message());
		helper.assertTrue(count(scene.player(), Items.CRAFTING_TABLE) == 1,
			"Expected 1 crafting table, found " + count(scene.player(), Items.CRAFTING_TABLE));
		helper.assertTrue(count(scene.player(), Items.STICK) == 64,
			"Expected 64 sticks, found " + count(scene.player(), Items.STICK));
		helper.assertTrue(total(scene.player()) == 65, "Nothing else should have been created, total was " + total(scene.player()));
		helper.assertTrue(
			result.message().contains("1 crafting table") && result.message().contains("64 sticks"),
			"The reply should name both items and their counts: " + result.message());

		helper.succeed();
	}

	@GameTest
	public void unknownItemIsNeverInvented(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_unknown");

		ActionResult result = scene.run("give me a flibber and 3 torches");
		helper.assertTrue(result.success(), "The known part of the request should still be delivered: " + result.message());
		helper.assertTrue(count(scene.player(), Items.TORCH) == 3, "Expected 3 torches, found " + count(scene.player(), Items.TORCH));
		helper.assertTrue(total(scene.player()) == 3, "The unknown item must not become some other item, total was " + total(scene.player()));
		helper.assertTrue(result.message().contains("flibber"), "The reply should say what was not understood: " + result.message());

		helper.succeed();
	}

	@GameTest
	public void requestOverTheLimitCreatesNothing(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_limit");

		int limit = FalsityMod.config().items.maxPerRequest;
		ActionResult result = scene.run("give me a torch and " + (limit + 1) + " diamonds");
		helper.assertTrue(!result.success(), "A request over the limit must be refused: " + result.message());
		helper.assertTrue(total(scene.player()) == 0,
			"A refused request must not deliver the part that was within the limit, total was " + total(scene.player()));

		helper.succeed();
	}

	@GameTest
	public void statedMaterialIsHonouredOrRefused(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_material");

		ActionResult result = scene.run("give me stone armour");
		helper.assertTrue(!result.success(), "Stone armour does not exist and must not become iron: " + result.message());
		helper.assertTrue(total(scene.player()) == 0, "Nothing should be created for armour that does not exist");

		helper.succeed();
	}

	@GameTest
	public void positionAnswerComesFromTheServer(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_where");

		ActionResult result = scene.run("where am I?");
		BlockPos pos = scene.player().blockPosition();
		helper.assertTrue(result.success(), "A position question should be answered: " + result.message());
		helper.assertTrue(
			result.message().contains("x=" + pos.getX() + ",") && result.message().contains("y=" + pos.getY() + ",")
				&& result.message().contains("z=" + pos.getZ()),
			"The answer should be the player's real position " + pos + ": " + result.message());

		helper.succeed();
	}

	@GameTest
	public void coordinatesStayHiddenWhereTheWorldHidesThem(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_hidden");
		BlockPos pos = scene.player().blockPosition();
		GameRules rules = scene.level().getGameRules();
		boolean before = rules.get(GameRules.REDUCED_DEBUG_INFO);

		// The level is shared with every other test, so the rule is switched on,
		// used and put back without letting a tick pass in between.
		ActionResult hidden;
		String hiddenFacts;
		rules.set(GameRules.REDUCED_DEBUG_INFO, true, scene.level().getServer());
		try {
			hidden = scene.run("where am I?");
			hiddenFacts = WorldService.facts(scene.player(), scene.falsity(), FalsityMod.config());
		} finally {
			rules.set(GameRules.REDUCED_DEBUG_INFO, before, scene.level().getServer());
		}

		helper.assertTrue(hidden.success(), "The question is still answered: " + hidden.message());
		helper.assertFalse(hidden.message().contains("x="),
			"With reducedDebugInfo on, Falsity must not recite the position: " + hidden.message());
		helper.assertTrue(hidden.message().contains("not allowed to share exact coordinates"),
			"It says why instead: " + hidden.message());
		helper.assertFalse(hiddenFacts.contains("x="),
			"The model is not handed the position either, so it cannot leak it: " + hiddenFacts);
		helper.assertTrue(hiddenFacts.startsWith("You and the player are in "), hiddenFacts);

		if (!before) {
			ActionResult shown = scene.run("where am I?");
			helper.assertTrue(shown.message().contains("x=" + pos.getX() + ","),
				"With the rule off again the position is back: " + shown.message());
		}

		helper.succeed();
	}

	@GameTest
	public void biomeAnswerMatchesTheWorld(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_biome");

		ActionResult result = scene.run("what biome is this?");
		String biome = WorldAnswers.words(WorldService.biomePath(scene.level(), scene.player().blockPosition()));
		helper.assertTrue(result.success(), "A biome question should be answered: " + result.message());
		helper.assertTrue(result.message().contains(biome), "The answer should name the real biome '" + biome + "': " + result.message());

		helper.succeed();
	}

	@GameTest
	public void structureSearchAnswersHonestly(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_village");

		// Whatever this test world contains, the answer must come from a real
		// search: either a found position or a plain "couldn't find".
		ActionResult result = scene.run("where is the nearest village?");
		helper.assertTrue(result.success(), "The search should run without error: " + result.message());
		helper.assertTrue(result.message().contains("village"), "The answer should be about villages: " + result.message());

		helper.succeed();
	}

	@GameTest
	public void structureInAnotherDimensionIsExplained(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_fortress");

		ActionResult result = scene.run("where is the nearest nether fortress?");
		helper.assertTrue(result.success(), "The question should be answered: " + result.message());
		helper.assertTrue(result.message().contains("Nether"), "The answer should say fortresses are in the Nether: " + result.message());

		helper.succeed();
	}

	@GameTest
	public void oreScanReportsARealBlock(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_ore");
		BlockPos relative = new BlockPos(5, 1, 5);
		helper.setBlock(relative, Blocks.DIAMOND_ORE);
		BlockPos ore = helper.absolutePos(relative);

		ActionResult result = scene.run("any diamonds nearby?");
		helper.assertTrue(result.success(), "The scan should run: " + result.message());
		helper.assertTrue(result.message().contains("diamond ore"), "The answer should name the ore: " + result.message());
		helper.assertTrue(
			result.message().contains("x=" + ore.getX() + ",") && result.message().contains("z=" + ore.getZ()),
			"The answer should give the block that was actually found at " + ore + ": " + result.message());

		helper.succeed();
	}

	@GameTest
	public void orderIsCarriedOutWithoutGroqAndAnsweredOnce(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_order");
		UUID uuid = scene.player().getUUID();
		String name = scene.player().getName().getString();

		boolean groqWasEnabled = FalsityMod.config().groq.enabled;
		FalsityMod.config().groq.enabled = false;
		try {
			scene.falsity().setSitting(false);
			int before = FalsityMod.memories().getOrCreate(uuid, name).conversationCount;

			var outcome = FalsityMod.conversations().requestConversation(scene.player(), scene.falsity(), "sit down please");
			helper.assertTrue(outcome == ConversationManager.Outcome.ACCEPTED, "An order should be accepted, was " + outcome);
			helper.assertTrue(scene.falsity().isSitting(), "Falsity should be sitting after being told to");

			int after = FalsityMod.memories().getOrCreate(uuid, name).conversationCount;
			helper.assertTrue(after - before == 1, "One message should produce exactly one reply, produced " + (after - before));
			helper.assertTrue(FalsityMod.conversations().history().size(uuid) == 1, "Exactly one exchange should be on record");
		} finally {
			FalsityMod.config().groq.enabled = groqWasEnabled;
		}

		helper.succeed();
	}

	@GameTest
	public void talkingAboutPlansDoesNotTriggerActions(final GameTestHelper helper) {
		Scene scene = scene(helper, "falsity_plans");
		UUID uuid = scene.player().getUUID();

		boolean groqWasEnabled = FalsityMod.config().groq.enabled;
		FalsityMod.config().groq.enabled = false;
		try {
			var plan = FalsityMod.conversations().requestConversation(scene.player(), scene.falsity(), "I want to build a house");
			helper.assertTrue(plan == ConversationManager.Outcome.ACCEPTED, "A plan should be answered, was " + plan);
			helper.assertTrue(!FalsityMod.builds().isActive(uuid), "Stating a plan must not start a build");

			var hug = FalsityMod.conversations().requestConversation(scene.player(), scene.falsity(), "I need a hug");
			helper.assertTrue(hug == ConversationManager.Outcome.ACCEPTED, "A wish that is not an item should be answered, was " + hug);
			helper.assertTrue(total(scene.player()) == 0, "'I need a hug' must not create items");

			helper.assertTrue(FalsityMod.conversations().history().size(uuid) == 2, "Both messages should have been answered");
		} finally {
			FalsityMod.config().groq.enabled = groqWasEnabled;
		}

		helper.succeed();
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
		return new Scene(level, player, falsity);
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
