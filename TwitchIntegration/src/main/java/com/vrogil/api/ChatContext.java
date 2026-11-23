package com.vrogil.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vrogil.bridge.ChatMessage;
import com.vrogil.chat.ChatFormatter;
import com.vrogil.chat.DisplayLine;
import com.vrogil.config.ModConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Twitch chat line, handed to listeners and chat-command handlers.
 *
 * <p>Everything here runs on the Minecraft server thread, so world access is
 * safe. Keep handlers quick: a slow handler delays chat for everyone.
 */
public final class ChatContext {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");

	private final MinecraftServer server;
	private final ModConfig config;
	private final ChatMessage message;
	private final DisplayLine display;

	private String commandName = "";
	private List<String> args = List.of();
	private boolean displaySuppressed;

	public ChatContext(MinecraftServer server, ModConfig config, ChatMessage message, DisplayLine display) {
		this.server = server;
		this.config = config;
		this.message = message;
		this.display = display;
	}

	public ChatMessage message() {
		return message;
	}

	/** Channel from the mod config, without the leading {@code #}. */
	public String channel() {
		return config.channel;
	}

	/** Sanitised display name, safe to put in chat. */
	public String author() {
		return display.author();
	}

	/** Sanitised message body, safe to put in chat. */
	public String body() {
		return display.body();
	}

	/** Twitch chat colour as packed RGB. */
	public int color() {
		return display.color();
	}

	public int bits() {
		return message.bits();
	}

	public boolean isBroadcaster() {
		return message.isBroadcaster();
	}

	public boolean isMod() {
		return message.isMod();
	}

	public boolean isSubscriber() {
		return message.isSubscriber();
	}

	/** Broadcaster or moderator. Check this before destructive actions. */
	public boolean canModerate() {
		return isBroadcaster() || isMod();
	}

	public MinecraftServer server() {
		return server;
	}

	/** The Minecraft player whose name matches the Twitch user, if online. */
	public Optional<ServerPlayer> player() {
		return Optional.ofNullable(server.getPlayerList().getPlayerByName(message.user()));
	}

	/** Empty unless this context was produced by a {@code !command} handler. */
	public String commandName() {
		return commandName;
	}

	/** Arguments after the command name; empty for plain chat. */
	public List<String> args() {
		return args;
	}

	public boolean isCommand() {
		return !commandName.isEmpty();
	}

	/** True when a listener or command hid the original line from chat/HUD. */
	public boolean displaySuppressed() {
		return displaySuppressed;
	}

	public void suppressDisplay() {
		displaySuppressed = true;
	}

	/** Send a line prefixed and coloured like normal Twitch output. */
	public void broadcast(String text) {
		String clean = ChatFormatter.sanitize(text, config.filters.maxMessageLength);
		if (clean.isBlank()) {
			return;
		}
		broadcastRaw(ChatFormatter.component(config, new DisplayLine(author(), clean, color())));
	}

	/** Send a pre-built component to every player. */
	public void broadcastRaw(Component component) {
		server.getPlayerList().broadcastSystemMessage(component, false);
	}

	/**
	 * Run a vanilla command from the world spawn with console permissions.
	 * Returns true when the command reported success.
	 */
	public boolean runCommand(String command) {
		return perform(server.createCommandSourceStack(), command);
	}

	/**
	 * Run a vanilla command as if {@code target} had typed it (same position and
	 * dimension), with console permissions. Handy for {@code ~ ~ ~} selectors.
	 */
	public boolean runCommandAt(ServerPlayer target, String command) {
		if (target == null) {
			return runCommand(command);
		}
		CommandSourceStack source = server.createCommandSourceStack()
				.withEntity(target)
				.withPosition(target.position())
				.withLevel((ServerLevel) target.level());
		return perform(source, command);
	}

	/** Run a command after {@code delayTicks} ticks; returns a cancellable handle. */
	public ScheduledTask runCommand(String command, int delayTicks) {
		return afterTicks(delayTicks, () -> runCommand(command));
	}

	/**
	 * Run a command at {@code target} after {@code delayTicks} ticks.
	 * The player may be gone by then, so re-check {@link #player()} inside long
	 * delays.
	 */
	public ScheduledTask runCommandAt(ServerPlayer target, String command, int delayTicks) {
		return afterTicks(delayTicks, () -> runCommandAt(target, command));
	}

	/**
	 * Queue any action after a number of server ticks (20 ticks = 1 second).
	 * Delays pause with the game and are dropped when the world stops.
	 */
	public ScheduledTask afterTicks(int ticks, Runnable action) {
		Objects.requireNonNull(action, "action");
		ModConfig.Actions settings = actions();
		return ActionScheduler.INSTANCE.schedule(
				author(),
				ticks,
				settings.maxDelayTicks,
				settings.maxPending,
				settings.maxPendingPerAuthor,
				action
		);
	}

	/** Convenience wrapper for {@link #afterTicks(int, Runnable)}. */
	public ScheduledTask afterSeconds(double seconds, Runnable action) {
		double clamped = Math.max(0.0, seconds);
		return afterTicks((int) Math.ceil(clamped * 20.0), action);
	}

	/** Configured default delay for gameplay actions, in ticks. */
	public int actionDelayTicks() {
		return actions().defaultDelayTicks;
	}

	/** Actions currently waiting in the delayed-action queue. */
	public int pendingActionCount() {
		return ActionScheduler.INSTANCE.pendingCount();
	}

	private ModConfig.Actions actions() {
		return config.actions == null ? new ModConfig.Actions() : config.actions;
	}

	/** Internal: used by the {@code !command} router. */
	public void setCommand(String name, List<String> args) {
		this.commandName = name;
		this.args = List.copyOf(args);
	}

	private boolean perform(CommandSourceStack source, String command) {
		CommandSourceStack effective = source.withPermission(2).withSuppressedOutput();
		String raw = command.startsWith("/") ? command.substring(1) : command;
		try {
			CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
			ParseResults<CommandSourceStack> parsed = dispatcher.parse(raw, effective);
			return dispatcher.execute(parsed) > 0;
		} catch (CommandSyntaxException e) {
			LOG.warn("[twitch] command failed: /{} ({})", raw, e.getMessage());
			return false;
		} catch (RuntimeException e) {
			LOG.warn("[twitch] command failed: /{}", raw, e);
			return false;
		}
	}
}
