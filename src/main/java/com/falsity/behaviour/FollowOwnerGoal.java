package com.falsity.behaviour;

import com.falsity.FalsityMod;
import com.falsity.entity.FalsityEntity;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Walks Falsity after the player it bonded with.
 *
 * <p>Vanilla's own follow goal only works on tameable animals, and Falsity is
 * deliberately not one of those: it is bound by its own rules and its own
 * configuration. Distances are decided by {@link CompanionBehaviour}.
 */
public class FollowOwnerGoal extends Goal {
	/** Ticks between re-issued paths. Pathfinding every tick would be wasteful. */
	private static final int PATH_RECALCULATION_TICKS = 10;

	private final FalsityEntity falsity;
	private LivingEntity owner;
	private int ticksUntilRepath;

	public FollowOwnerGoal(FalsityEntity falsity) {
		this.falsity = falsity;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (this.falsity.isSitting()) {
			return false;
		}

		Optional<Player> ownerOptional = this.falsity.getOwner();

		if (ownerOptional.isEmpty()) {
			return false;
		}

		Player candidate = ownerOptional.get();

		if (!candidate.isAlive() || candidate.isSpectator() || candidate.level() != this.falsity.level()) {
			return false;
		}

		this.owner = candidate;
		double distance = Math.sqrt(this.falsity.distanceToSqr(candidate));
		return CompanionBehaviour.shouldFollow(distance, FalsityMod.config().companion);
	}

	@Override
	public boolean canContinueToUse() {
		if (this.owner == null || this.falsity.isSitting()) {
			return false;
		}

		if (!this.owner.isAlive() || this.owner.level() != this.falsity.level()) {
			return false;
		}

		double distance = Math.sqrt(this.falsity.distanceToSqr(this.owner));
		return CompanionBehaviour.shouldKeepFollowing(distance, FalsityMod.config().companion);
	}

	@Override
	public void start() {
		this.ticksUntilRepath = 0;
	}

	@Override
	public void stop() {
		this.owner = null;
		this.falsity.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (this.owner == null) {
			return;
		}

		// Look at the owner every tick; only re-path occasionally.
		this.falsity.getLookControl().setLookAt(this.owner, 10.0F, this.falsity.getMaxHeadXRot());

		if (--this.ticksUntilRepath > 0) {
			return;
		}

		this.ticksUntilRepath = PATH_RECALCULATION_TICKS;
		double speed = CompanionBehaviour.followSpeed(FalsityMod.config().companion);
		this.falsity.getNavigation().moveTo(this.owner, speed);
	}
}
