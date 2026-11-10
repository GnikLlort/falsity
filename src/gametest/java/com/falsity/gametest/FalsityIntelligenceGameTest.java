package com.falsity.gametest;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.ai.ConversationManager;
import com.falsity.entity.FalsityEntity;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.PlayerMemory;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side game tests for Milestone 2 intelligence.
 *
 * <p>These run inside a real Minecraft server as part of
 * {@code ./gradlew build}. They prove memory is created on bonding,
 * personality evolves on petting, horror starts at Innocence and stays
 * capped, and conversations fall back offline without a network. No test
 * needs a Groq key or internet access.
 */
public class FalsityIntelligenceGameTest {
	/** Where Falsity is placed inside the (empty) test structure. */
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);

	@GameTest
	public void bondingCreatesPersistentMemory(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);

		falsity.interactWith(player);

		Optional<PlayerMemory> memory = FalsityMod.memories().get(player.getUUID());
		helper.assertTrue(memory.isPresent(), "Bonding should create persistent memory for the player");
		helper.assertTrue(memory.get().firstMetEpochMillis > 0L, "First meeting should be recorded");
		helper.assertTrue(memory.get().lastSeenEpochMillis > 0L, "Last seen should be recorded");

		// Memory must survive a reload from disk.
		FalsityMod.memories().save(player.getUUID());
		Optional<PlayerMemory> reloaded = FalsityMod.memories().storage().load(player.getUUID().toString());
		helper.assertTrue(reloaded.isPresent(), "Memory should persist to disk");
		helper.assertTrue(
			reloaded.get().firstMetEpochMillis == memory.get().firstMetEpochMillis,
			"Reloaded memory should match what was saved"
		);

		helper.succeed();
	}

	@GameTest
	public void pettingEvolvesPersonality(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.bondTo(owner);

		double before = FalsityMod.personalities().profileFor(owner.getUUID(), owner.getName().getString()).affection;

		falsity.interactWith(owner);

		double after = FalsityMod.personalities().profileFor(owner.getUUID(), owner.getName().getString()).affection;
		helper.assertTrue(after > before, "Petting should increase affection (before " + before + ", after " + after + ")");
		helper.assertTrue(after <= 100.0, "Affection must stay in range");

		helper.succeed();
	}

	@GameTest
	public void horrorStartsAtInnocenceAndStaysCapped(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.bondTo(owner);

		// A dedicated subject id, so the test starts from a clean slate no
		// matter what earlier tests did to the shared mock player.
		UUID subject = UUID.randomUUID();

		HorrorStage stage = FalsityMod.horror().stageFor(subject, "Subject");
		helper.assertTrue(stage == HorrorStage.INNOCENCE, "New relationships must start at Innocence, was " + stage);

		// Many interactions must never push past the milestone cap.
		for (int i = 0; i < 100; i++) {
			FalsityMod.horror().recordInteraction(subject, "Subject");
		}
		HorrorStage after = FalsityMod.horror().stageFor(subject, "Subject");
		helper.assertTrue(
			after.ordinal() <= FalsityMod.config().horror.maxStage,
			"Horror stage must stay at or below the configured cap, was " + after
		);
		helper.assertTrue(after.ordinal() <= 1, "This milestone must never go beyond Strange, was " + after);

		// Falsity must never become hostile: health is untouched by horror.
		helper.assertTrue(falsity.isAlive(), "Falsity should still be alive");
		helper.assertTrue(falsity.getHealth() == falsity.getMaxHealth(), "Horror progression must not damage Falsity");

		helper.succeed();
	}

	@GameTest
	public void offlineConversationDoesNotThrowAndRecordsMemory(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.bondTo(owner);

		// Force the offline path so the test never depends on a network,
		// an API key or a CI secret being present.
		boolean groqWasEnabled = FalsityMod.config().groq.enabled;
		FalsityMod.config().groq.enabled = false;

		try {
			int before = FalsityMod.memories().getOrCreate(owner.getUUID(), owner.getName().getString()).conversationCount;

			var outcome = FalsityMod.conversations().requestConversation(owner, falsity, "Hello, Falsity!");

			helper.assertTrue(
				outcome == ConversationManager.Outcome.ACCEPTED,
				"An offline conversation must still be accepted, was " + outcome
			);

			int after = FalsityMod.memories().getOrCreate(owner.getUUID(), owner.getName().getString()).conversationCount;
			helper.assertTrue(after > before, "The offline reply should be recorded in memory");

			// The exchange must survive a reload from disk.
			FalsityMod.memories().save(owner.getUUID());
			Optional<PlayerMemory> reloaded = FalsityMod.memories().storage().load(owner.getUUID().toString());
			helper.assertTrue(reloaded.isPresent(), "The conversation should persist to disk");
			helper.assertTrue(
				reloaded.get().conversationCount >= after,
				"The stored conversation count should match, was " + reloaded.get().conversationCount
			);
		} finally {
			FalsityMod.config().groq.enabled = groqWasEnabled;
		}

		helper.succeed();
	}

	@GameTest
	public void memoriesStayIsolatedBetweenPlayers(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player first = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.bondTo(first);

		PlayerMemory firstMemory = FalsityMod.memories().getOrCreate(first.getUUID(), first.getName().getString());
		firstMemory.recordFact("First player likes diamonds", FalsityMod.config().memory.maxStoredFacts);
		FalsityMod.memories().save(first.getUUID());

		// A second, unrelated player id: Falsity must never mix the two.
		UUID otherId = UUID.randomUUID();
		PlayerMemory secondMemory = FalsityMod.memories().getOrCreate(otherId, "SomeoneElse");

		helper.assertTrue(
			secondMemory.facts.stream().noneMatch(fact -> fact.contains("diamonds")),
			"Second player's memory must not contain the first player's facts"
		);
		helper.assertTrue(
			FalsityMod.memories().getOrCreate(first.getUUID(), first.getName().getString()).facts.stream().anyMatch(fact -> fact.contains("diamonds")),
			"The first player's memory must still hold its own facts"
		);

		helper.succeed();
	}

	private static FalsityEntity spawn(final GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atCenterOf(helper.absolutePos(SPAWN));
		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);
		return falsity;
	}
}
