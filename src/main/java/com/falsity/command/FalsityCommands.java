package com.falsity.command;

import com.falsity.FalsityMod;
import com.falsity.FalsityRegistries;
import com.falsity.ai.ConversationManager;
import com.falsity.entity.FalsityEntity;
import com.falsity.horror.HorrorStage;
import com.falsity.memory.MemorySummarizer;
import com.falsity.memory.PlayerMemory;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Development, administration and conversation commands for Falsity. */
public final class FalsityCommands {
	/** How far away Falsity may be and still hear a talk command. */
	private static final double TALK_RANGE = 16.0;

	private FalsityCommands() {
	}

	public static void initialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(
				Commands.literal("falsity")
					.then(summonCommand())
					.then(talkCommand())
					.then(memoryCommand())
					.then(personalityCommand())
					.then(horrorCommand())
			)
		);
	}

	/**
	 * {@code /falsity summon} — moderator only.
	 *
	 * <p>Each subcommand is built by its own method so the tree stays
	 * readable and the permission of each branch is obvious.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> summonCommand() {
		return Commands.literal("summon")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
			.executes(FalsityCommands::summon);
	}

	/**
	 * {@code /falsity talk <message>} — open to every player.
	 *
	 * <p>The command itself has no permission gate: Falsity is a companion,
	 * and anyone may talk to it. Whether the message is answered is decided
	 * by the entity (bonded, nearby, not on cooldown), not by a permission.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> talkCommand() {
		return Commands.literal("talk")
			.then(Commands.argument("message", StringArgumentType.greedyString())
				.executes(FalsityCommands::talk));
	}

	/** {@code /falsity memory [clear <player>]} — inspect is open, clearing is moderator only. */
	private static LiteralArgumentBuilder<CommandSourceStack> memoryCommand() {
		return Commands.literal("memory")
			.executes(FalsityCommands::showMemory)
			.then(Commands.literal("clear")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(FalsityCommands::clearMemory)));
	}

	/** {@code /falsity personality} — open; shows Falsity's traits towards the caller. */
	private static LiteralArgumentBuilder<CommandSourceStack> personalityCommand() {
		return Commands.literal("personality")
			.executes(FalsityCommands::showPersonality);
	}

	/** {@code /falsity horror [set <player> <stage>]} — inspect is open, setting is moderator only. */
	private static LiteralArgumentBuilder<CommandSourceStack> horrorCommand() {
		return Commands.literal("horror")
			.executes(FalsityCommands::showHorror)
			.then(Commands.literal("set")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
				.then(Commands.argument("target", EntityArgument.player())
					.then(Commands.argument("stage", IntegerArgumentType.integer(0, 4))
						.executes(FalsityCommands::setHorror))));
	}

	private static int summon(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Vec3 position = source.getPosition();

		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.setPos(position.x, position.y, position.z);

		if (source.getEntity() != null) {
			falsity.setYRot(source.getEntity().getYRot());
			falsity.setYHeadRot(source.getEntity().getYRot());
		}

		if (!level.addFreshEntity(falsity)) {
			source.sendFailure(Component.translatable("command.falsity.failed"));
			return 0;
		}

		falsity.setPersistenceRequired();
		source.sendSuccess(() -> Component.translatable("command.falsity.summoned"), true);
		return 1;
	}

	private static int talk(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		if (!(source.getEntity() instanceof Player player)) {
			source.sendFailure(Component.translatable("command.falsity.players_only"));
			return 0;
		}

		String message = StringArgumentType.getString(context, "message");
		Optional<FalsityEntity> nearby = findNearbyCompanion(source.getLevel(), player);

		if (nearby.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.falsity.no_companion"));
			return 0;
		}

		FalsityEntity falsity = nearby.get();
		if (!falsity.isBondedTo(player.getUUID())) {
			player.sendSystemMessage(Component.translatable("message.falsity.not_yours"));
			return 0;
		}

		if (FalsityMod.conversations() == null) {
			player.sendSystemMessage(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		ConversationManager.Outcome outcome = FalsityMod.conversations().requestConversation(player, falsity, message);
		switch (outcome) {
			case ACCEPTED -> {
				return 1;
			}
			case DISABLED -> {
				player.sendSystemMessage(Component.translatable("message.falsity.talking_disabled"));
				return 0;
			}
			case TOO_LONG -> {
				player.sendSystemMessage(Component.translatable("message.falsity.too_long"));
				return 0;
			}
			case ON_COOLDOWN -> {
				long remaining = FalsityMod.conversations().cooldownRemainingSeconds(player.getUUID());
				player.sendSystemMessage(Component.translatable("message.falsity.cooldown", remaining));
				return 0;
			}
			default -> {
				return 0;
			}
		}
	}

	private static int showMemory(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		if (!(source.getEntity() instanceof Player player)) {
			source.sendFailure(Component.translatable("command.falsity.players_only"));
			return 0;
		}

		if (FalsityMod.memories() == null) {
			source.sendFailure(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		String name = player.getName().getString();
		PlayerMemory memory = FalsityMod.memories().getOrCreate(player.getUUID(), name);
		MemorySummarizer.refresh(memory, FalsityMod.config().memory.maxSummaryChars);

		player.sendSystemMessage(Component.literal("--- Falsity remembers about " + name + " ---"));
		player.sendSystemMessage(Component.literal("Conversations: " + memory.conversationCount + ", pets: " + memory.petCount));
		if (memory.summary != null && !memory.summary.isBlank()) {
			player.sendSystemMessage(Component.literal(memory.summary));
		} else {
			player.sendSystemMessage(Component.literal("Falsity does not remember much about you yet."));
		}
		player.sendSystemMessage(Component.literal("Facts stored: " + memory.facts.size() + " (use /falsity memory clear <player> as op to wipe)"));
		return 1;
	}

	private static int clearMemory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(context, "target");

		if (FalsityMod.memories() == null) {
			context.getSource().sendFailure(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		UUID targetId = target.getUUID();
		FalsityMod.memories().clear(targetId);
		context.getSource().sendSuccess(() -> Component.translatable("command.falsity.memory_cleared", target.getName().getString()), true);
		return 1;
	}

	private static int showPersonality(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		if (!(source.getEntity() instanceof Player player)) {
			source.sendFailure(Component.translatable("command.falsity.players_only"));
			return 0;
		}

		if (FalsityMod.personalities() == null) {
			source.sendFailure(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		String name = player.getName().getString();
		var profile = FalsityMod.personalities().profileFor(player.getUUID(), name);
		var mood = FalsityMod.personalities().moodFor(player.getUUID(), name);

		player.sendSystemMessage(Component.literal("--- Falsity's personality towards " + name + " ---"));
		player.sendSystemMessage(Component.literal("Mood: " + mood.name().toLowerCase() + " (" + profile.describe() + ")"));
		player.sendSystemMessage(Component.literal(
			"Curiosity " + Math.round(profile.curiosity)
				+ ", affection " + Math.round(profile.affection)
				+ ", attachment " + Math.round(profile.attachment)
				+ ", playfulness " + Math.round(profile.playfulness)
		));
		player.sendSystemMessage(Component.literal(
			"Jealousy " + Math.round(profile.jealousy)
				+ ", suspicion " + Math.round(profile.suspicion)
				+ ", loneliness " + Math.round(profile.loneliness)
				+ ", fear " + Math.round(profile.fear)
		));
		return 1;
	}

	private static int showHorror(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		if (!(source.getEntity() instanceof Player player)) {
			source.sendFailure(Component.translatable("command.falsity.players_only"));
			return 0;
		}

		if (FalsityMod.horror() == null) {
			source.sendFailure(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		String name = player.getName().getString();
		HorrorStage stage = FalsityMod.horror().stageFor(player.getUUID(), name);
		double tension = FalsityMod.horror().tensionFor(player.getUUID(), name);

		player.sendSystemMessage(Component.literal("Falsity is " + stage.displayName() + " towards you (tension " + Math.round(tension) + ")."));
		return 1;
	}

	private static int setHorror(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(context, "target");
		int stageOrdinal = IntegerArgumentType.getInteger(context, "stage");

		if (FalsityMod.horror() == null) {
			context.getSource().sendFailure(Component.translatable("message.falsity.unavailable"));
			return 0;
		}

		HorrorStage stage = FalsityMod.horror().setStage(target.getUUID(), target.getName().getString(), stageOrdinal);
		context.getSource().sendSuccess(() -> Component.translatable("command.falsity.horror_set", target.getName().getString(), stage.displayName()), true);
		return 1;
	}

	/** Finds the nearest Falsity bonded to this player within talk range. */
	private static Optional<FalsityEntity> findNearbyCompanion(ServerLevel level, Player player) {
		Vec3 position = player.position();
		AABB box = new AABB(
			position.x - TALK_RANGE, position.y - TALK_RANGE, position.z - TALK_RANGE,
			position.x + TALK_RANGE, position.y + TALK_RANGE, position.z + TALK_RANGE
		);
		List<Entity> nearby = level.getEntities(null, box, entity ->
			entity instanceof FalsityEntity falsity && falsity.isBondedTo(player.getUUID()));

		List<FalsityEntity> candidates = new ArrayList<>();
		for (Entity entity : nearby) {
			if (entity instanceof FalsityEntity falsity) {
				candidates.add(falsity);
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		candidates.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
		return Optional.of(candidates.get(0));
	}
}
