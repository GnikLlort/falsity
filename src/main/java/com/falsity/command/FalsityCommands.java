package com.falsity.command;

import com.falsity.FalsityRegistries;
import com.falsity.entity.FalsityEntity;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;

/** Development and administration commands for Falsity. */
public final class FalsityCommands {
	private FalsityCommands() {
	}

	public static void initialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(
				Commands.literal("falsity")
					.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
					.then(Commands.literal("summon").executes(FalsityCommands::summon))
			)
		);
	}

	private static int summon(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Vec3 position = source.getPosition();

		FalsityEntity falsity = new FalsityEntity(FalsityRegistries.FALSITY, level);
		falsity.moveTo(
			position.x, position.y, position.z,
			source.getRotation().y, 0.0F
		);

		if (!level.addFreshEntity(falsity)) {
			source.sendFailure(Component.translatable("command.falsity.failed"));
			return 0;
		}

		falsity.setPersistenceRequired();
		source.sendSuccess(() -> Component.translatable("command.falsity.summoned"), true);
		return 1;
	}
}
