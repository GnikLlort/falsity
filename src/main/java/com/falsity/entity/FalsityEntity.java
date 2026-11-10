package com.falsity.entity;

import com.falsity.FalsityMod;
import com.falsity.behaviour.CompanionBehaviour;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import java.util.UUID;

/**
 * Falsity, first form.
 *
 * <p>A small plush companion that wanders, watches players nearby and bonds with
 * whoever first reaches out to it. Once bonded it follows its owner, sits when
 * told to, and catches up if left behind.
 *
 * <p>The server decides everything: bonding, sitting and the position Falsity
 * ends up in when it catches up. Clients are sent the synced data and the
 * entity events that start an animation, and nothing else.
 */
public class FalsityEntity extends PathfinderMob {
	/** Entity event id used to start the greeting animation on clients. */
	public static final byte EVENT_GREET = 61;

	/** Looping breathing and swaying, played the whole time Falsity exists. */
	public final AnimationState idleAnimationState = new AnimationState();
	/** One-shot perk of the ears and a little hop, played when Falsity is petted. */
	public final AnimationState greetAnimationState = new AnimationState();

	/** Ticks left before Falsity may catch up with its owner again. */
	private int catchUpCooldown;
	/** Ticks left of the current greeting, used to stop it repeating. */
	private int greetTicksLeft;

	public FalsityEntity(EntityType<? extends FalsityEntity> entityType, Level level) {
		super(entityType, level);
		// A companion is not a random spawn: it must never be silently despawned.
		this.setPersistenceRequired();
	}

	@Override
	protected void registerGoals() {
		FalsityGoals.addGoals(this, this.goalSelector);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FalsityEntityData.OWNER, "");
		builder.define(FalsityEntityData.SITTING, false);
	}

	// ----------------------------------------------------------------------
	// Bond and orders
	// ----------------------------------------------------------------------

	public Optional<UUID> getOwnerUuid() {
		String stored = this.entityData.get(FalsityEntityData.OWNER);

		if (stored.isEmpty()) {
			return Optional.empty();
		}

		try {
			return Optional.of(UUID.fromString(stored));
		} catch (IllegalArgumentException malformed) {
			FalsityMod.LOGGER.warn("Falsity has a malformed owner id '{}'; treating it as unbonded.", stored);
			return Optional.empty();
		}
	}

	public Optional<Player> getOwner() {
		return getOwnerUuid().map(uuid -> this.level().getPlayerByUUID(uuid));
	}

	public boolean isBondedTo(UUID player) {
		return getOwnerUuid().map(player::equals).orElse(false);
	}

	/** Bonds Falsity to a player. Called on the server only. */
	public void bondTo(Player player) {
		this.entityData.set(FalsityEntityData.OWNER, player.getUUID().toString());
		this.setPersistenceRequired();
		FalsityMod.debug("Falsity bonded to {}", player.getName().getString());
	}

	public boolean isSitting() {
		return this.entityData.get(FalsityEntityData.SITTING);
	}

	public void setSitting(boolean sitting) {
		this.entityData.set(FalsityEntityData.SITTING, sitting);

		if (sitting) {
			this.getNavigation().stop();
		}
	}

	// ----------------------------------------------------------------------
	// Interaction
	// ----------------------------------------------------------------------

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (this.level().isClientSide()) {
			// The server decides; the client only plays the swing.
			return InteractionResult.SUCCESS;
		}

		return interactWith(player);
	}

	/**
	 * Server-side interaction: bond, give orders, or pet.
	 *
	 * <p>Public so game tests can exercise the real logic without going through
	 * player input handling.
	 */
	public InteractionResult interactWith(Player player) {
		Optional<UUID> owner = getOwnerUuid();

		if (owner.isEmpty()) {
			bond(player);
			return InteractionResult.SUCCESS;
		}

		if (!isBondedTo(player.getUUID())) {
			player.sendSystemMessage(Component.translatable("message.falsity.not_yours"));
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown()) {
			setSitting(!isSitting());
			player.sendSystemMessage(Component.translatable(isSitting() ? "message.falsity.sit" : "message.falsity.follow"));
			return InteractionResult.SUCCESS;
		}

		pet(player);
		return InteractionResult.SUCCESS;
	}

	private void bond(Player player) {
		bondTo(player);
		greet();
		player.sendSystemMessage(Component.translatable("message.falsity.bonded"));

		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(
				ParticleTypes.HEART,
				this.getX(), this.getY() + 1.1, this.getZ(),
				4, 0.3, 0.3, 0.3, 0.01
			);
		}
	}

	private void pet(Player player) {
		if (this.getHealth() < this.getMaxHealth()) {
			this.heal(2.0F);
		}

		greet();
		player.sendSystemMessage(Component.translatable("message.falsity.pet"));
	}

	/** Plays the greeting animation on every client that can see Falsity. */
	public void greet() {
		if (this.greetTicksLeft > 0) {
			// Already mid-greet: restarting would make repeated petting look jittery.
			return;
		}

		this.greetTicksLeft = 40;
		this.level().broadcastEntityEvent(this, EVENT_GREET);
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == EVENT_GREET) {
			this.greetAnimationState.start(this.tickCount);
		} else {
			super.handleEntityEvent(id);
		}
	}

	// ----------------------------------------------------------------------
	// Ticking
	// ----------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();

		if (this.level().isClientSide()) {
			this.idleAnimationState.animateWhen(true, this.tickCount);
			return;
		}

		if (this.greetTicksLeft > 0) {
			this.greetTicksLeft--;
		}

		if (this.catchUpCooldown > 0) {
			this.catchUpCooldown--;
		}

		catchUpWithOwnerIfNeeded();
	}

	/**
	 * If the owner is far away and Falsity is not sitting, it arrives near them
	 * instead of walking for minutes. This mirrors how vanilla pets behave, and
	 * it is the only place Falsity changes position without pathfinding.
	 */
	private void catchUpWithOwnerIfNeeded() {
		if (this.catchUpCooldown > 0 || this.isSitting() || !FalsityMod.config().companion.followOwner) {
			return;
		}

		Optional<Player> ownerOptional = getOwner();

		if (ownerOptional.isEmpty()) {
			return;
		}

		Player owner = ownerOptional.get();

		if (owner.level() != this.level() || !owner.isAlive()) {
			return;
		}

		double distance = Math.sqrt(this.distanceToSqr(owner));

		if (!CompanionBehaviour.shouldTeleport(distance, FalsityMod.config().companion)) {
			return;
		}

		Vec3 target = findSpotNear(owner);
		this.getNavigation().stop();
		this.teleportTo(target.x, target.y, target.z);
		this.catchUpCooldown = 80;
		FalsityMod.debug("Falsity caught up with its owner {} blocks away.", Math.round(distance));
	}

	/** A few blocks around the owner, at their own height, preferring open ground. */
	private Vec3 findSpotNear(Player owner) {
		double x = owner.getX() + this.random.nextInt(-2, 3);
		double z = owner.getZ() + this.random.nextInt(-2, 3);
		return new Vec3(x, owner.getY(), z);
	}

	// ----------------------------------------------------------------------
	// Persistence
	// ----------------------------------------------------------------------

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("Owner", this.entityData.get(FalsityEntityData.OWNER));
		output.putBoolean("Sitting", isSitting());
		output.putInt("CatchUpCooldown", this.catchUpCooldown);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.entityData.set(FalsityEntityData.OWNER, input.getStringOr("Owner", ""));
		setSitting(input.getBooleanOr("Sitting", false));
		this.catchUpCooldown = input.getIntOr("CatchUpCooldown", 0);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}
}
