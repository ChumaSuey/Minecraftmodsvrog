package com.vrogil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.vrogil.api.ChatContext;
import com.vrogil.api.TwitchIntegrationApi;
import com.vrogil.api.TwitchIntegrationExtension;
import com.vrogil.bridge.BridgeClient;
import com.vrogil.bridge.ChatMessage;
import com.vrogil.bridge.Protocol;
import com.vrogil.chat.ChatFormatter;
import com.vrogil.chat.DisplayLine;
import com.vrogil.command.TwitchCommand;
import com.vrogil.config.ModConfig;
import com.vrogil.twitch.ChatCommandRouter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for TwitchIntegration.
 *
 * <p>The mod never talks to Twitch itself. It starts (or connects to) the Rust
 * bridge on loopback TCP and turns the events it receives into chat lines,
 * console output and extension callbacks. Keeping the network code in another
 * process means a bridge crash cannot take Minecraft down, and the mod jar never
 * contains a token.
 */
public class TwitchIntegrationMod implements ModInitializer {
	public static final String MOD_ID = "twitchintegration";
	public static final String EXTENSION_ENTRYPOINT = "twitchintegration:extensions";
	public static final Logger LOGGER = LoggerFactory.getLogger("twitchintegration");

	private static volatile ModConfig config = new ModConfig();
	private static volatile BridgeClient bridge;
	private static volatile MinecraftServer server;
	private static volatile Consumer<DisplayLine> hudSink;

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		LOGGER.info(
				"[twitch] config loaded from {} (channel={})",
				ModConfig.path(),
				config.channel.isBlank() ? "<unset>" : "#" + config.channel
		);

		TwitchIntegrationApi.setChatSender(TwitchIntegrationMod::sendChatToBridge);
		loadExtensions();

		CommandRegistrationCallback.EVENT.register(TwitchCommand::register);

		ServerTickEvents.END_SERVER_TICK.register(server -> TwitchIntegrationApi.tickActions());

		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			if (config.enabled && config.transport.autoConnect) {
				connect();
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
			TwitchIntegrationApi.clearActions();
			disconnect();
			server = null;
		});
	}

	public static ModConfig config() {
		return config;
	}

	/** Client-only hook so the HUD can receive lines without making the main
	 * source set depend on client classes. */
	public static void setHudSink(Consumer<DisplayLine> sink) {
		hudSink = sink;
	}

	public static BridgeClient bridge() {
		return bridge;
	}

	public static synchronized void connect() {
		if (bridge != null) {
			return;
		}
		BridgeClient client = new BridgeClient(TwitchIntegrationMod::config, TwitchIntegrationMod::handleChat);
		bridge = client;
		client.start();
	}

	public static synchronized void disconnect() {
		BridgeClient current = bridge;
		bridge = null;
		if (current != null) {
			current.stop();
		}
	}

	public static void reload() {
		config = ModConfig.load();
		BridgeClient current = bridge;
		if (current != null) {
			current.applyConfig();
		}
	}

	/** Called from the bridge reader thread with a decoded chat line. */
	public static void handleChat(ChatMessage message) {
		ModConfig cfg = config;
		if (!cfg.enabled) {
			return;
		}

		Optional<DisplayLine> display = ChatFormatter.toDisplayLine(message, cfg);
		if (display.isEmpty()) {
			return;
		}
		DisplayLine line = display.get();

		MinecraftServer current = server;
		if (current == null) {
			// No world: nothing gameplay-facing can run, but the overlay still can.
			emitHud(cfg, line);
			return;
		}

		try {
			current.execute(() -> dispatch(current, cfg, message, line));
		} catch (RuntimeException e) {
			LOGGER.debug("[twitch] server went away before the chat line could be dispatched", e);
		}
	}

	/** Runs extensions and display work on the server thread, in one ordered pass. */
	private static void dispatch(MinecraftServer current, ModConfig cfg, ChatMessage message, DisplayLine line) {
		try {
			ChatContext context = new ChatContext(current, cfg, message, line);

			TwitchIntegrationApi.fireChat(context);
			ChatCommandRouter.dispatch(context, cfg.commands);

			if (context.displaySuppressed()) {
				return;
			}

			if (cfg.display.serverChat) {
				current.getPlayerList()
						.broadcastSystemMessage(ChatFormatter.component(cfg, line), false);
			}
			emitHud(cfg, line);
		} catch (RuntimeException e) {
			LOGGER.warn("[twitch] chat dispatch failed", e);
		}
	}

	private static void emitHud(ModConfig cfg, DisplayLine line) {
		Consumer<DisplayLine> sink = hudSink;
		if (cfg.display.hud && sink != null) {
			sink.accept(line);
		}
	}

	/** Bridge events with no typed model yet (subs, raids, redemptions, ...). */
	public static void handleRawEvent(String type, JsonObject data) {
		MinecraftServer current = server;
		if (current == null) {
			TwitchIntegrationApi.fireRaw(type, data);
			return;
		}
		try {
			current.execute(() -> TwitchIntegrationApi.fireRaw(type, data));
		} catch (RuntimeException e) {
			TwitchIntegrationApi.fireRaw(type, data);
		}
	}

	/** Link/Twitch state changes, e.g. {@code connected}, {@code link-lost}. */
	public static void handleStatus(String state) {
		MinecraftServer current = server;
		if (current == null) {
			TwitchIntegrationApi.fireStatus(state);
			return;
		}
		try {
			current.execute(() -> TwitchIntegrationApi.fireStatus(state));
		} catch (RuntimeException e) {
			TwitchIntegrationApi.fireStatus(state);
		}
	}

	private static void sendChatToBridge(String text) {
		BridgeClient current = bridge;
		if (current == null) {
			LOGGER.warn("[twitch] sendChat ignored: the bridge link is not running");
			return;
		}
		String clean = ChatFormatter.sanitize(text, 500);
		if (clean.isBlank()) {
			return;
		}
		current.send(Protocol.say(clean));
	}

	private static void loadExtensions() {
		int loaded = 0;
		for (EntrypointContainer<TwitchIntegrationExtension> container : FabricLoader.getInstance()
				.getEntrypointContainers(EXTENSION_ENTRYPOINT, TwitchIntegrationExtension.class)) {
			String id = container.getProvider().getMetadata().getId();
			try {
				container.getEntrypoint().onTwitchIntegrationInit();
				loaded++;
			} catch (Throwable t) {
				// A broken extension must not stop the mod from loading.
				LOGGER.error("[twitch] extension from '{}' failed to initialise", id, t);
			}
		}
		if (loaded > 0) {
			LOGGER.info("[twitch] initialised {} extension(s)", loaded);
		}
	}

	public static List<String> statusLines() {
		ModConfig cfg = config;
		BridgeClient current = bridge;
		List<String> lines = new ArrayList<>();

		lines.add("TwitchIntegration: " + (cfg.enabled ? "enabled" : "disabled")
				+ ", channel=" + (cfg.channel.isBlank() ? "<unset>" : "#" + cfg.channel)
				+ ", port=" + cfg.transport.port
				+ ", spawn=" + (cfg.transport.autoSpawn ? "auto" : "manual"));

		if (current == null) {
			lines.add("Bridge link: not started (use /twitch connect)");
			return lines;
		}

		lines.add("Bridge link: " + current.state() + ", Twitch: " + current.twitchState());
		lines.add("Messages: " + current.messagesReceived() + " received, "
				+ current.messagesDropped() + " dropped, "
				+ TwitchIntegrationApi.pendingActions() + " actions queued");
		return lines;
	}
}
