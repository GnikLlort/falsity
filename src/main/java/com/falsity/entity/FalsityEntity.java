package com.falsity.entity;

import com.falsity.FalsityMod;
import com.falsity.ai.OfflineFallback;
import com.falsity.behaviour.CompanionBehaviour;
import com.falsity.behaviour.FollowOwnerGoal;
import com.falsity.behaviour.PettingCooldown;
import com.falsity.horror.HorrorEvent;
import com.falsity.memory.PlayerMemory;
import com.falsity.personality.PersonalityProfile;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
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
 * ends up in when it catches up. The only state the client needs is whether
 * Falsity is sitting, and that rides on vanilla's synched pose rather than a
 * custom data slot; the owning player stays server-side state. The greeting
 * animation is started by an entity event.
 */
public class FalsityEntity extends PathfinderMob {
	/** Entity event id used to start the greeting animation on clients. */
	public static final byte EVENT_GREET = 61;

	/** Looping breathing and swaying, played the whole time Falsity exists. */
	public final AnimationState idleAnimationState = new AnimationState();
	/** One-shot perk of the ears and a little hop, played when Falsity is petted. */
	public final AnimationState greetAnimationState = new AnimationState();

	/**
	 * UUID of the player Falsity bonded with, or an empty string.
	 *
	 * <p>This is server state only: it is saved with the entity and never synced
	 * to clients, so a malformed value can never break a client's copy of the
	 * entity. Kept as a string so loading a damaged save cannot throw.
	 */
	private String ownerId = "";

	/**
	 * The owner this session resolved, kept to avoid a level lookup every tick.
	 *
	 * <p>It is a cache, not state: the saved UUID is the truth, and this is
	 * dropped whenever the player leaves the level or the entity is reloaded.
	 */
	private Player cachedOwner;

	/** Ticks left before Falsity may catch up with its owner again. */
	private int catchUpCooldown;
	/** Ticks left of the current greeting, used to stop it repeating. */
	private int greetTicksLeft;
	/** Suppresses repeated use packets from one held interaction key. */
	private final PettingCooldown pettingCooldown = new PettingCooldown();
	private static final int PET_COOLDOWN_TICKS = 40;

	/** How often, in ticks, Falsity gets the chance to say something of its own accord. */
	private static final int SPEAK_FIRST_CHECK_TICKS = 100;

	/** Falsity only speaks first when its owner is within this many blocks (squared). */
	private static final double SPEAK_FIRST_RANGE_SQR = 12.0 * 12.0;

	/** Ticks left of the current stare at the owner (horror, harmless). */
	private int stareTicksLeft;
	/** Ticks left before Falsity may stare again. */
	private int stareCooldownTicks;
	/** Whether Falsity is currently helping its owner in combat. */
	private boolean combatEnabled;
	/** Ticks left before Falsity may attack its combat target again. */
	private int combatCooldownTicks;

	public FalsityEntity(EntityType<? extends FalsityEntity> entityType, Level level) {
		super(entityType, level);
		// A companion is not a random spawn: it must never be silently despawned.
		this.setPersistenceRequired();
	}

	@Override
	protected void registerGoals() {
		FalsityGoals.addGoals(this, this.goalSelector);
	}

	// ----------------------------------------------------------------------
	// Bond and orders
	// ----------------------------------------------------------------------

	public Optional<UUID> getOwnerUuid() {
		String stored = this.ownerId;

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
		if (this.cachedOwner != null) {
			if (this.cachedOwner.isRemoved() || this.cachedOwner.level() != this.level()) {
				this.cachedOwner = null;
			} else {
				return Optional.of(this.cachedOwner);
			}
		}

		return getOwnerUuid().map(uuid -> this.level().getPlayerByUUID(uuid));
	}

	public boolean isBondedTo(UUID player) {
		return getOwnerUuid().map(player::equals).orElse(false);
	}

	/** Bonds Falsity to a player. Called on the server only. */
	public void bondTo(Player player) {
		this.ownerId = player.getUUID().toString();
		this.cachedOwner = player;
		this.setPersistenceRequired();
		// Drop any idle stroll path so following can take over cleanly.
		this.getNavigation().stop();
		FalsityMod.debug("Falsity bonded to {}", player.getName().getString());
		recordFirstMeeting(player);
	}

	/** Whether the follow-owner goal is registered on this entity. */
	public boolean hasFollowGoal() {
		for (WrappedGoal goal : this.goalSelector.getAvailableGoals()) {
			if (goal.getGoal() instanceof FollowOwnerGoal) {
				return true;
			}
		}
		return false;
	}

	/** Whether the follow-owner goal is currently running. */
	public boolean isFollowingOwner() {
		for (WrappedGoal goal : this.goalSelector.getAvailableGoals()) {
			if (goal.isRunning() && goal.getGoal() instanceof FollowOwnerGoal) {
				return true;
			}
		}
		return false;
	}

	/** Whether idle random strolling is currently running. */
	public boolean isStrolling() {
		for (WrappedGoal goal : this.goalSelector.getAvailableGoals()) {
			if (goal.isRunning() && goal.getGoal() instanceof RandomStrollGoal) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Records the first meeting in persistent memory. Defensive: memory is a
	 * nice-to-have, never a reason for bonding to fail.
	 */
	private void recordFirstMeeting(Player player) {
		if (FalsityMod.memories() == null || FalsityMod.personalities() == null || FalsityMod.horror() == null) {
			return;
		}
		try {
			String name = player.getName().getString();
			PlayerMemory memory = FalsityMod.memories().getOrCreate(player.getUUID(), name);
			memory.touch(System.currentTimeMillis());
			memory.recordFact("First met Falsity.", FalsityMod.config().memory.maxStoredFacts);
			FalsityMod.memories().save(player.getUUID());
			FalsityMod.personalities().applyEvent(player.getUUID(), name, PersonalityProfile.Event.BONDED);
			FalsityMod.horror().recordInteraction(player.getUUID(), name);
		} catch (RuntimeException exception) {
			FalsityMod.LOGGER.warn("Could not record first meeting with {}; bonding still succeeded.", player.getName().getString(), exception);
		}
	}

	/**
	 * Whether Falsity is sitting.
	 *
	 * <p>Sitting is expressed as the vanilla {@link Pose#SITTING} pose, which the
	 * game already syncs to clients for us, so the renderer and the animation
	 * code can read it on both sides without a custom data slot.
	 */
	public boolean isSitting() {
		return this.getPose() == Pose.SITTING;
	}

	public void setSitting(boolean sitting) {
		this.setPose(sitting ? Pose.SITTING : Pose.STANDING);

		if (sitting) {
			this.getNavigation().stop();
		}
	}

	// ----------------------------------------------------------------------
	// Requested actions (server-authoritative)
	// ----------------------------------------------------------------------

	/** Whether Falsity is currently allowed to fight for its owner. */
	public boolean isCombatEnabled() {
		return this.combatEnabled;
	}

	/** Arms or disarms combat assistance. Called by the action handlers. */
	public void setCombatEnabled(boolean enabled) {
		this.combatEnabled = enabled;
		if (!enabled) {
			this.setTarget(null);
		}
	}

	/** Turns to look at a position. */
	public void lookAt(Vec3 position) {
		this.getLookControl().setLookAt(position.x, position.y, position.z, 10.0F, this.getMaxHeadXRot());
	}

	/** Walks to a position, standing up first if it was sitting. */
	public void walkTo(Vec3 position) {
		this.setSitting(false);
		// Same pace the companion uses when following its owner.
		this.getNavigation().moveTo(
			position.x,
			position.y,
			position.z,
			CompanionBehaviour.followSpeed(FalsityMod.config().companion)
		);
	}

	/** Stops walking and stays exactly where it is. */
	public void stay() {
		this.getNavigation().stop();
	}

	/**
	 * Comes to the player, arriving on open ground a couple of blocks away.
	 * This is the same catch-up the entity uses when it is left behind, so it
	 * cannot be used to push Falsity into a wall.
	 */
	public void comeTo(Player player) {
		Vec3 target = findSpotNear(player);
		this.getNavigation().stop();
		this.teleportTo(target.x, target.y, target.z);
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
			recordOrder(player, isSitting() ? "sit" : "follow");
			return InteractionResult.SUCCESS;
		}

		pet(player);
		return InteractionResult.SUCCESS;
	}

	/** Records an order in memory. Never throws. */
	private void recordOrder(Player player, String order) {
		if (FalsityMod.memories() == null || FalsityMod.personalities() == null) {
			return;
		}
		try {
			String name = player.getName().getString();
			PlayerMemory memory = FalsityMod.memories().getOrCreate(player.getUUID(), name);
			memory.touch(System.currentTimeMillis());
			memory.recordOrder(order, FalsityMod.config().memory.maxRecentInteractions);
			FalsityMod.memories().save(player.getUUID());
			FalsityMod.personalities().applyEvent(player.getUUID(), name, PersonalityProfile.Event.ORDER_GIVEN);
		} catch (RuntimeException exception) {
			FalsityMod.LOGGER.warn("Could not record order from {}; orders still worked.", player.getName().getString(), exception);
		}
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
		// Holding use can produce repeated server packets. Do not heal, message,
		// animate, mutate personality, or increase horror for those repeats.
		if (!this.pettingCooldown.tryPet(PET_COOLDOWN_TICKS)) {
			return;
		}
		if (this.getHealth() < this.getMaxHealth()) {
			this.heal(2.0F);
		}

		greet();
		player.sendSystemMessage(Component.translatable("message.falsity.pet"));
		recordPet(player);
	}

	/**
	 * Records a pet in memory and, if the owner was away for a day or more,
	 * greets them with a welcome-back line that proves Falsity noticed.
	 * Petting never triggers a Groq request.
	 */
	private void recordPet(Player player) {
		if (FalsityMod.memories() == null || FalsityMod.personalities() == null || FalsityMod.horror() == null) {
			return;
		}
		try {
			String name = player.getName().getString();
			long now = System.currentTimeMillis();
			PlayerMemory memory = FalsityMod.memories().getOrCreate(player.getUUID(), name);
			long daysAway = memory.daysSinceSeen(now);
			memory.touch(now);
			if (daysAway >= 1L) {
				// Kept, so a conversation later the same day still knows the owner has only just come back.
				memory.recordReturn(now, daysAway);
				memory.addRecent(HorrorEvent.returned(now, daysAway).description(), FalsityMod.config().memory.maxRecentInteractions);
			}
			memory.recordPet(FalsityMod.config().memory.maxRecentInteractions);
			FalsityMod.memories().save(player.getUUID());
			FalsityMod.personalities().applyEvent(player.getUUID(), name, PersonalityProfile.Event.PETTED);
			if (daysAway >= 1L) {
				FalsityMod.personalities().applyEvent(player.getUUID(), name, PersonalityProfile.Event.OWNER_RETURNED);
				String line = new OfflineFallback().welcomeBack(daysAway);
				player.sendSystemMessage(Component.literal("<" + this.getName().getString() + "> " + line));
			}
			FalsityMod.horror().recordInteraction(player.getUUID(), name);
		} catch (RuntimeException exception) {
			FalsityMod.LOGGER.warn("Could not record pet from {}; petting still worked.", player.getName().getString(), exception);
		}
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
		this.pettingCooldown.tick();

		if (this.catchUpCooldown > 0) {
			this.catchUpCooldown--;
		}

		if (this.stareCooldownTicks > 0) {
			this.stareCooldownTicks--;
		}

		catchUpWithOwnerIfNeeded();
		tickStaring();
		tickSpeakingFirst();
		tickCombat();
		tickBuilds();
		FalsityMod.expireIdleState();
	}

	/**
	 * Every few seconds, while its owner is close, Falsity gets the chance to
	 * say something without having been spoken to. Whether it takes the chance
	 * is decided by the conversation manager, which keeps it rare; most of the
	 * time nothing happens here at all.
	 */
	private void tickSpeakingFirst() {
		if (this.tickCount % SPEAK_FIRST_CHECK_TICKS != 0
			|| FalsityMod.conversations() == null || !FalsityMod.config().chat.speaksFirst) {
			return;
		}
		Optional<Player> ownerOptional = getOwner();
		if (ownerOptional.isEmpty()) {
			return;
		}
		Player owner = ownerOptional.get();
		if (owner.level() != this.level() || !owner.isAlive() || this.distanceToSqr(owner) > SPEAK_FIRST_RANGE_SQR) {
			return;
		}
		try {
			FalsityMod.conversations().offerToSpeakFirst(owner, this);
		} catch (RuntimeException exception) {
			FalsityMod.debug("Falsity could not take a moment to speak first: {}", exception.toString());
		}
	}

	/** Advances the owner's active build, if one is running. */
	private void tickBuilds() {
		if (FalsityMod.builds() == null) {
			return;
		}
		getOwner().ifPresent(owner -> FalsityMod.builds().tick(owner.getUUID()));
	}

	/**
	 * Combat assistance: when armed, Falsity closes on its target and attacks
	 * it on a cooldown. It only ever acts on a target the server set for it and
	 * never attacks players. Disarming clears the target immediately.
	 */
	private void tickCombat() {
		if (this.combatCooldownTicks > 0) {
			this.combatCooldownTicks--;
		}

		if (!this.combatEnabled || this.isSitting()) {
			return;
		}

		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive() || target.level() != this.level()) {
			return;
		}

		if (this.distanceToSqr(target) > 36.0) {
			this.getNavigation().moveTo(target, 1.0);
			return;
		}

		this.getLookControl().setLookAt(target, 10.0F, this.getMaxHeadXRot());
		if (this.combatCooldownTicks > 0) {
			return;
		}

		this.combatCooldownTicks = 20;
		AttributeInstance attackDamage = this.getAttribute(Attributes.ATTACK_DAMAGE);
		float damage = attackDamage != null ? (float) attackDamage.getValue() : 2.0F;
		target.hurt(this.damageSources().mobAttack(this), damage);
	}

	/**
	 * Horror foundation: at Strange or beyond, Falsity occasionally stops
	 * and stares at its owner for a few seconds. Harmless by design: no
	 * movement, no sound, no damage. Gated by a long cooldown so it stays
	 * rare, and only when the owner is close enough to be watched.
	 */
	private void tickStaring() {
		if (this.stareTicksLeft > 0) {
			this.stareTicksLeft--;
			getOwner().ifPresent(owner -> this.getLookControl().setLookAt(owner, 10.0F, this.getMaxHeadXRot()));
			if (this.stareTicksLeft == 0) {
				this.stareCooldownTicks = 6000;
			}
			return;
		}

		if (this.stareCooldownTicks > 0 || this.isSitting()) {
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
		if (this.distanceToSqr(owner) > 36.0) {
			return;
		}
		if (FalsityMod.horror() == null) {
			return;
		}

		// Rare: roughly once every few minutes of close proximity.
		if (this.random.nextInt(1200) != 0) {
			return;
		}

		boolean allowed;
		try {
			allowed = FalsityMod.horror().shouldStare(owner.getUUID(), owner.getName().getString());
		} catch (RuntimeException exception) {
			return;
		}
		if (!allowed) {
			return;
		}

		this.stareTicksLeft = 60 + this.random.nextInt(60);
		this.getNavigation().stop();
		FalsityMod.debug("Falsity is staring at its owner.");
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
		output.putString("Owner", this.ownerId);
		output.putBoolean("Sitting", isSitting());
		output.putInt("CatchUpCooldown", this.catchUpCooldown);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.ownerId = input.getStringOr("Owner", "");
		this.cachedOwner = null;
		setSitting(input.getBooleanOr("Sitting", false));
		this.catchUpCooldown = input.getIntOr("CatchUpCooldown", 0);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}
}
