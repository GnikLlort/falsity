package com.falsity.gametest;

import com.falsity.FalsityMod;
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

		// If the entity were erroring every tick it would not survive this long,
		// and its tick counter would not advance.
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(falsity.isAlive(), "Falsity should still be alive after 10 ticks");
			helper.assertTrue(falsity.tickCount >= 10, "Falsity should have ticked, tickCount was " + falsity.tickCount);
			helper.assertTrue(falsity.getId() == startId, "Falsity should keep its entity id");
			helper.assertTrue(
				Double.isFinite(falsity.getX()) && Double.isFinite(falsity.getY()) && Double.isFinite(falsity.getZ()),
				"Falsity should have a real position"
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

		// A stone walkway with clear headroom so Falsity can path and walk
		// straight towards its owner without falling or hitting obstacles.
		for (int x = 0; x <= 12; x++) {
			for (int z = 1; z <= 3; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
				helper.setBlock(x, 1, z, Blocks.AIR);
				helper.setBlock(x, 2, z, Blocks.AIR);
			}
		}

		helper.assertTrue(
			level.getBlockState(helper.absolutePos(new BlockPos(12, 0, 2))).is(Blocks.STONE),
			"The game test walkway could not be built, so walking cannot be measured"
		);

		FalsityEntity falsity = spawn(helper);
		Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 here = Vec3.atBottomCenterOf(helper.absolutePos(SPAWN));
		owner.snapTo(here.x + 8.0, here.y, here.z);
		falsity.bondTo(owner);

		helper.assertTrue(
			falsity.isBondedTo(owner.getUUID()),
			"Falsity should be bonded to the owner player"
		);
		helper.assertTrue(
			falsity.getOwner().isPresent(),
			"Falsity should resolve the player it bonded with"
		);
		helper.assertTrue(
			owner.level() == level,
			"Owner should be in the same server level as Falsity"
		);
		helper.assertTrue(
			FalsityMod.config().companion.followOwner,
			"Follow configuration must enable following"
		);
		helper.assertTrue(
			!falsity.isSitting(),
			"Falsity must not be sitting before following"
		);
		helper.assertTrue(
			falsity.hasFollowGoal(),
			"FollowOwnerGoal must be registered on Falsity"
		);

		Vec3 startPos = falsity.position();
		double startDistance = falsity.distanceTo(owner);
		helper.assertTrue(
			startDistance > FalsityMod.config().companion.followStartDistance,
			"Owner must start beyond followStartDistance ("
				+ FalsityMod.config().companion.followStartDistance + "), was " + startDistance
		);

		helper.succeedWhen(() -> {
			helper.assertTrue(
				falsity.isFollowingOwner(),
				"FollowOwnerGoal should be running"
			);
			helper.assertTrue(
				!falsity.isStrolling(),
				"RandomStrollGoal must not interfere while following"
			);
			helper.assertTrue(
				falsity.getNavigation().getPath() != null,
				"Navigation should have a valid path towards the owner"
			);
			double closedDistance = startDistance - falsity.distanceTo(owner);
			double movedFromStart = falsity.position().distanceTo(startPos);
			helper.assertTrue(
				closedDistance >= 0.5 && movedFromStart >= 0.5,
				"Falsity should walk towards its owner; closed distance by "
					+ closedDistance + " blocks (moved " + movedFromStart + " from start)"
			);
		});
	}

	private static FalsityEntity spawn(final GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 position = Vec3.atBottomCenterOf(helper.absolutePos(SPAWN));
		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		level.addFreshEntity(falsity);
		return falsity;
	}
}
