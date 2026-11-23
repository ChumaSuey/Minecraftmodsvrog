package com.vrogil.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.vrogil.TwitchIntegrationMod;
import com.vrogil.bridge.ChatMessage;
import com.vrogil.config.ModConfig;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /twitch}: link management for server operators. */
public final class TwitchCommand {
	private TwitchCommand() {
	}

	public static void register(
			CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext buildContext,
			Commands.CommandSelection selection
	) {
		dispatcher.register(Commands.literal("twitch")
				.requires(source -> source.getServer().isSingleplayer() || source.hasPermission(2))
				.then(Commands.literal("status").executes(TwitchCommand::status))
				.then(Commands.literal("connect").executes(TwitchCommand::connect))
				.then(Commands.literal("disconnect").executes(TwitchCommand::disconnect))
				.then(Commands.literal("reload").executes(TwitchCommand::reload))
				.then(Commands.literal("test").executes(TwitchCommand::test)));
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		for (String line : TwitchIntegrationMod.statusLines()) {
			context.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int connect(CommandContext<CommandSourceStack> context) {
		if (TwitchIntegrationMod.bridge() != null) {
			context.getSource().sendSuccess(
					() -> Component.literal("Twitch bridge link is already running."), false);
			return Command.SINGLE_SUCCESS;
		}

		ModConfig config = TwitchIntegrationMod.config();
		TwitchIntegrationMod.connect();
		context.getSource().sendSuccess(
				() -> Component.literal("Connecting to the Twitch bridge on 127.0.0.1:" + config.transport.port + " ..."),
				false);
		return Command.SINGLE_SUCCESS;
	}

	private static int disconnect(CommandContext<CommandSourceStack> context) {
		if (TwitchIntegrationMod.bridge() == null) {
			context.getSource().sendSuccess(
					() -> Component.literal("Twitch bridge link is not running."), false);
			return Command.SINGLE_SUCCESS;
		}

		TwitchIntegrationMod.disconnect();
		context.getSource().sendSuccess(
				() -> Component.literal("Twitch bridge link stopped."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		TwitchIntegrationMod.reload();
		context.getSource().sendSuccess(
				() -> Component.literal("Reloaded " + ModConfig.path() + " (channel="
						+ (TwitchIntegrationMod.config().channel.isBlank()
								? "<unset>"
								: "#" + TwitchIntegrationMod.config().channel)
						+ ")"),
				false);
		return Command.SINGLE_SUCCESS;
	}

	private static int test(CommandContext<CommandSourceStack> context) {
		ChatMessage message = new ChatMessage(
				"test",
				"twitchintegration",
				"TestUser",
				"#9146FF",
				"This is a TwitchIntegration test message. If you can read this, both chat and HUD are wired up.",
				0,
				false,
				true,
				false
		);
		TwitchIntegrationMod.handleChat(message);
		context.getSource().sendSuccess(
				() -> Component.literal("Injected a test chat line."), false);
		return Command.SINGLE_SUCCESS;
	}
}
