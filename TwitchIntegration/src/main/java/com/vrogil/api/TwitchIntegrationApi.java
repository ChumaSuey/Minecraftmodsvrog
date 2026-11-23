package com.vrogil.api;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension surface for TwitchIntegration.
 *
 * <p>All registrations return an {@link AutoCloseable} that removes the callback.
 * Every callback below except {@link #sendChat(String)} runs on the Minecraft
 * server thread whenever a world is loaded, so touching the world is safe.
 *
 * <p>Call it from a {@link TwitchIntegrationExtension}, or directly from your own
 * {@code ModInitializer} in a separate mod jar that depends on TwitchIntegration.
 * Registering nothing is fine: the whole surface is inert until used.
 */
public final class TwitchIntegrationApi {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");

	private static final CopyOnWriteArrayList<ChatListener> CHAT_LISTENERS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<RawEventListener> RAW_LISTENERS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<StatusListener> STATUS_LISTENERS = new CopyOnWriteArrayList<>();
	private static final ConcurrentHashMap<String, ChatCommandHandler> COMMANDS = new ConcurrentHashMap<>();

	private static volatile Consumer<String> chatSender = text -> {
	};

	private TwitchIntegrationApi() {
	}

	/** Observes every line that survived the config filters. */
	@FunctionalInterface
	public interface ChatListener {
		void onChat(ChatContext context);
	}

	/** Handles {@code !name args}. */
	@FunctionalInterface
	public interface ChatCommandHandler {
		void handle(ChatContext context);
	}

	/** Receives bridge events that have no typed model yet (future subs/raids). */
	@FunctionalInterface
	public interface RawEventListener {
		void onEvent(String type, JsonObject data);
	}

	/** Receives Twitch link state changes, e.g. {@code connected}, {@code link-lost}. */
	@FunctionalInterface
	public interface StatusListener {
		void onStatus(String state);
	}

	public static AutoCloseable onChat(ChatListener listener) {
		Objects.requireNonNull(listener, "listener");
		CHAT_LISTENERS.add(listener);
		return () -> CHAT_LISTENERS.remove(listener);
	}

	/**
	 * Register a chat command, without the prefix: {@code "coinflip"} handles
	 * {@code !coinflip}. Names are lowercased and must match
	 * {@code [a-z0-9_-]{1,32}}.
	 */
	public static AutoCloseable registerCommand(String name, ChatCommandHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		String key = normalizeCommand(name);
		if (!key.matches("[a-z0-9_-]{1,32}")) {
			throw new IllegalArgumentException(
					"command names must match [a-z0-9_-]{1,32}, got: " + name);
		}

		COMMANDS.put(key, handler);
		return () -> COMMANDS.remove(key, handler);
	}

	public static AutoCloseable onRawEvent(RawEventListener listener) {
		Objects.requireNonNull(listener, "listener");
		RAW_LISTENERS.add(listener);
		return () -> RAW_LISTENERS.remove(listener);
	}

	public static AutoCloseable onStatus(StatusListener listener) {
		Objects.requireNonNull(listener, "listener");
		STATUS_LISTENERS.add(listener);
		return () -> STATUS_LISTENERS.remove(listener);
	}

	/**
	 * Send a message to Twitch chat.
	 *
	 * <p>The current bridge build is read-only, so this is wired to {@code cmd.say}
	 * but the bridge answers {@code unsupported}. The endpoint is here so sending
	 * can be enabled later without changing extension code.
	 */
	public static void sendChat(String text) {
		chatSender.accept(text);
	}

	/** Internal: the mod installs the real bridge-backed sender. */
	public static void setChatSender(Consumer<String> sender) {
		chatSender = sender == null ? text -> {
		} : sender;
	}

	/** Internal: used by the command router. */
	public static ChatCommandHandler commandHandler(String name) {
		return COMMANDS.get(normalizeCommand(name));
	}

	public static void fireChat(ChatContext context) {
		for (ChatListener listener : CHAT_LISTENERS) {
			try {
				listener.onChat(context);
			} catch (RuntimeException e) {
				LOG.warn("[twitch] a chat listener threw", e);
			}
		}
	}

	public static void fireRaw(String type, JsonObject data) {
		for (RawEventListener listener : RAW_LISTENERS) {
			try {
				listener.onEvent(type, data);
			} catch (RuntimeException e) {
				LOG.warn("[twitch] a raw-event listener threw", e);
			}
		}
	}

	public static void fireStatus(String state) {
		for (StatusListener listener : STATUS_LISTENERS) {
			try {
				listener.onStatus(state);
			} catch (RuntimeException e) {
				LOG.warn("[twitch] a status listener threw", e);
			}
		}
	}

	/** Internal: advances the delayed-action queue once per server tick. */
	public static void tickActions() {
		ActionScheduler.INSTANCE.tick();
	}

	/** Internal: drops every queued action when the world stops. */
	public static void clearActions() {
		ActionScheduler.INSTANCE.clear();
	}

	/** Number of actions waiting in the delayed-action queue. */
	public static int pendingActions() {
		return ActionScheduler.INSTANCE.pendingCount();
	}

	private static String normalizeCommand(String name) {
		String key = name.trim().toLowerCase(Locale.ROOT);
		while (key.startsWith("!")) {
			key = key.substring(1);
		}
		return key;
	}
}
