package com.falsity.gametest;

import com.falsity.FalsityRegistries;
import com.falsity.entity.FalsityEntity;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side game tests for Milestone 1.
 *
 * <p>These run inside a real Minecraft server as part of {@code ./gradlew build}.
 * They prove the entity registers, spawns, has its attributes, survives ticking,
 * bonds, responds to orders and actually walks towards its owner. They cannot
 * prove anything about how it looks: that needs a client, which is why the
 * rendered model is checked separately by the asset tooling.
 */
public class FalsityGameTest {
	/** Where Falsity is placed inside the (empty) test structure. */
	private static final BlockPos SPAWN = new BlockPos(2, 1, 2);

	@GameTest
	public void falsitySpawnsWithItsAttributes(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);

		helper.assertTrue(falsity.isAlive(), "Falsity should be alive after spawning");
		helper.assertTrue(
            falsity.getAttributeValue(Attributes.MAX_HEALTH) == 20.0,
            "Falsity should have 20 max health, but had " + falsity.getAttributeValue(Attributes.MAX_HEALTH)
		);
		helper.assertTrue(
			falsity.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0,
			"Falsity should have a movement speed"
		);
		helper.assertTrue(falsity.isPersistenceRequired(), "Falsity must not despawn");
		helper.assertTrue(falsity.getOwnerUuid().isEmpty(), "A newly spawned Falsity has no owner");

		helper.succeed();
	}

	@GameTest
	public void falsityTicksAndKeepsItsId(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		int startId = falsity.getId();
		double startX = falsity.getX();
		double startZ = falsity.getZ();

		// If the entity were being dropped or erroring every tick, it would not
		// survive this long, and it would not have moved anywhere at all.
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(falsity.isAlive(), "Falsity should still be alive after 40 ticks");
			helper.assertTrue(falsity.tickCount >= 40, "Falsity should have ticked, tickCount was " + falsity.tickCount);
			helper.assertTrue(falsity.getId() == startId, "Falsity should keep its entity id");
			helper.assertTrue(
				Math.abs(falsity.getX() - startX) > 1.0E-4 || Math.abs(falsity.getZ() - startZ) > 1.0E-4,
				"Falsity should have wandered from where it started"
			);
			helper.succeed();
		});
	}

	@GameTest
	public void falsityBondsToTheFirstPlayer(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);

		falsity.interactWith(player);

		Optional<java.util.UUID> owner = falsity.getOwnerUuid();
		helper.assertTrue(owner.isPresent(), "Petting an unbonded Falsity should bond it");
		helper.assertTrue(
			owner.get().equals(player.getUUID()),
			"Falsity should bond to the player who touched it first"
		);

		// A second player must not be able to take it.
		Player other = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.interactWith(other);
		helper.assertTrue(
			falsity.getOwnerUuid().orElseThrow().equals(player.getUUID()),
			"Falsity should stay bonded to its first owner"
		);

		helper.succeed();
	}

	@GameTest
	public void falsityObeysSitAndFollow(final GameTestHelper helper) {
		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		falsity.bondTo(owner);

		helper.assertTrue(!falsity.isSitting(), "Falsity starts standing");

		falsity.setSitting(true);
		helper.assertTrue(falsity.isSitting(), "Falsity should sit when told to");

		falsity.setSitting(false);
		helper.assertTrue(!falsity.isSitting(), "Falsity should stand when told to");

		helper.succeed();
	}

	@GameTest
	public void falsityWalksTowardsItsOwner(final GameTestHelper helper) {
		ServerLevel level = helper.getLevel();

		// A little stone floor, so there is somewhere to path.
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}

		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 here = Vec3.atCenterOf(helper.absolutePos(SPAWN));
		owner.moveTo(here.x + 4.0, here.y, here.z);
		falsity.bondTo(owner);

		double startDistance = falsity.distanceToSqr(owner);

		helper.succeedWhen(() ->
			helper.assertTrue(
				falsity.distanceToSqr(owner) < startDistance - 0.5,
				"Falsity should walk towards its owner; it has moved "
					+ (startDistance - falsity.distanceToSqr(owner)) + " blocks squared"
			)
		);
	}

	private static FalsityEntity spawn(final GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atCenterOf(helper.absolutePos(SPAWN));
		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);
		return falsity;
	}
}
