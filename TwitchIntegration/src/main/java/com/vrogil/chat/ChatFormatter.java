package com.vrogil.chat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vrogil.bridge.ChatMessage;
import com.vrogil.config.ModConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * Turns untrusted Twitch text into something safe to put in front of a player.
 *
 * <p>Chat is never parsed as a format string or as JSON: every piece ends up in
 * {@link Component#literal}, so {@code %} or {@code §} in a message cannot do
 * anything clever.
 */
public final class ChatFormatter {
	private static final int DEFAULT_COLOR = 0xFFFFFF;
	private static final int AUTHOR_LIMIT = 32;

	private ChatFormatter() {
	}

	public static Optional<DisplayLine> toDisplayLine(ChatMessage message, ModConfig config) {
		String body = sanitize(message.message(), config.filters.maxMessageLength);
		if (body.isBlank()) {
			return Optional.empty();
		}
		if (isBlocked(body, config.filters.blockedWords)) {
			return Optional.empty();
		}

		String author = sanitizeAuthor(message.displayName());
		if (author.isBlank()) {
			author = sanitizeAuthor(message.user());
		}
		if (author.isBlank()) {
			author = "twitch";
		}

		return Optional.of(new DisplayLine(author, body, parseColor(message.color())));
	}

	public static Component component(ModConfig config, DisplayLine line) {
		MutableComponent text = Component.literal(config.display.prefix).withStyle(ChatFormatting.DARK_PURPLE);
		text.append(Component.literal(line.author())
				.withStyle(style -> style.withColor(TextColor.fromRgb(line.color()))));
		text.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
		text.append(Component.literal(line.body()).withStyle(ChatFormatting.WHITE));
		return text;
	}

	public static String sanitize(String input, int maxLength) {
		if (input == null || input.isEmpty()) {
			return "";
		}

		StringBuilder builder = new StringBuilder(input.length());
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '\u00a7') {
				// § starts a vanilla formatting code; drop the marker and its partner
				i++;
				continue;
			}
			if (c == '\t') {
				builder.append(' ');
				continue;
			}
			if (c < 0x20 || c == 0x7f) {
				continue;
			}
			builder.append(c);
		}

		String clean = builder.toString().replaceAll("\\s+", " ").trim();
		int limit = Math.max(1, maxLength);
		if (clean.length() > limit) {
			clean = clean.substring(0, limit - 1) + "\u2026";
		}
		return clean;
	}

	public static boolean isBlocked(String body, List<String> blockedWords) {
		if (blockedWords == null || blockedWords.isEmpty()) {
			return false;
		}
		String lower = body.toLowerCase(Locale.ROOT);
		for (String word : blockedWords) {
			if (word != null && !word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	public static int parseColor(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_COLOR;
		}
		String hex = value.startsWith("#") ? value.substring(1) : value;
		if (hex.length() != 6) {
			return DEFAULT_COLOR;
		}
		try {
			return Integer.parseInt(hex, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return DEFAULT_COLOR;
		}
	}

	private static String sanitizeAuthor(String name) {
		return sanitize(name, AUTHOR_LIMIT);
	}
}
