package com.vrogil.twitch;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.vrogil.api.ChatContext;
import com.vrogil.api.TwitchIntegrationApi;
import com.vrogil.config.ModConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns {@code !name args} into calls on registered handlers.
 *
 * <p>No handlers are registered by the mod. Unknown commands fall through and are
 * displayed like ordinary chat, so the feature is invisible until an extension
 * claims a name.
 */
public final class ChatCommandRouter {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");
	private static final int MAX_COOLDOWN_ENTRIES = 512;
	private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();

	private ChatCommandRouter() {
	}

	public static void dispatch(ChatContext context, ModConfig.ChatCommands config) {
		if (config == null || !config.enabled || config.prefix.isEmpty()) {
			return;
		}

		String body = context.body();
		if (!body.startsWith(config.prefix)) {
			return;
		}

		String rest = body.substring(config.prefix.length()).trim();
		if (rest.isEmpty()) {
			return;
		}

		String[] parts = rest.split("\\s+");
		String name = parts[0].toLowerCase(Locale.ROOT);
		TwitchIntegrationApi.ChatCommandHandler handler = TwitchIntegrationApi.commandHandler(name);
		if (handler == null) {
			return;
		}

		if (!cooldownAllows(name, context.author(), config.cooldownSeconds)) {
			return;
		}

		List<String> args = parts.length > 1
				? Arrays.asList(parts).subList(1, parts.length)
				: List.of();
		context.setCommand(name, args);

		try {
			handler.handle(context);
		} catch (RuntimeException e) {
			LOG.warn("[twitch] command '{}' from '{}' failed", name, context.author(), e);
		}
	}

	private static boolean cooldownAllows(String command, String author, int cooldownSeconds) {
		if (cooldownSeconds <= 0) {
			return true;
		}

		long now = System.currentTimeMillis();
		long window = cooldownSeconds * 1000L;
		String key = command + '\u0000' + author.toLowerCase(Locale.ROOT);

		Long previous = LAST_USED.get(key);
		if (previous != null && now - previous < window) {
			return false;
		}
		LAST_USED.put(key, now);

		if (LAST_USED.size() > MAX_COOLDOWN_ENTRIES) {
			long cutoff = now - window;
			LAST_USED.entrySet().removeIf(entry -> entry.getValue() < cutoff);
		}

		return true;
	}
}
