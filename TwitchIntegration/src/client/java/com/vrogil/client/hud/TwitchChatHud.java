package com.vrogil.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.vrogil.TwitchIntegrationMod;
import com.vrogil.chat.DisplayLine;
import com.vrogil.config.ModConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Top-left overlay showing recent Twitch messages. F10 toggles it. */
public final class TwitchChatHud {
	private static final int MARGIN_X = 4;
	private static final int MARGIN_Y = 4;
	private static final int MAX_WIDTH = 320;
	private static final int BODY_COLOR = 0xFFFFFF;
	private static final long FADE_MS = 1_500L;
	private static final int HARD_LINE_CAP = 20;
	private static final ConcurrentLinkedDeque<Entry> ENTRIES = new ConcurrentLinkedDeque<>();

	private record Entry(DisplayLine line, long addedAtMs) {
	}

	private static volatile boolean visible = true;

	private TwitchChatHud() {
	}

	public static void push(DisplayLine line) {
		ENTRIES.addLast(new Entry(line, System.currentTimeMillis()));

		ModConfig config = TwitchIntegrationMod.config();
		int cap = Math.max(1, Math.min(HARD_LINE_CAP, config.display.maxLines)) * 3;
		while (ENTRIES.size() > cap) {
			ENTRIES.pollFirst();
		}
	}

	public static void toggle() {
		visible = !visible;
	}

	public static void render(GuiGraphics graphics) {
		ModConfig config = TwitchIntegrationMod.config();
		if (!visible || !config.display.hud) {
			return;
		}

		long now = System.currentTimeMillis();
		long ttl = Math.max(2, config.display.hudTtlSeconds) * 1000L;
		ENTRIES.removeIf(entry -> now - entry.addedAtMs() > ttl);

		List<Entry> entries = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			entries.add(entry);
		}
		if (entries.isEmpty()) {
			return;
		}

		int maxLines = Math.max(1, Math.min(HARD_LINE_CAP, config.display.maxLines));
		if (entries.size() > maxLines) {
			entries = entries.subList(entries.size() - maxLines, entries.size());
		}

		Font font = Minecraft.getInstance().font;
		int lineHeight = font.lineHeight + 2;
		int width = 0;
		for (Entry entry : entries) {
			int entryWidth = font.width(entry.line().author() + ": " + entry.line().body());
			width = Math.max(width, Math.min(MAX_WIDTH, entryWidth));
		}

		int x = MARGIN_X;
		int y = MARGIN_Y;
		int height = entries.size() * lineHeight;

		graphics.fill(x - 2, y - 2, x + width + 4, y + height + 2, 0x66000000);

		for (Entry entry : entries) {
			long remaining = ttl - (now - entry.addedAtMs());
			int alpha = (int) (255.0 * Math.min(1.0, remaining / (double) FADE_MS));
			alpha = Math.max(0, Math.min(255, alpha));

			String name = entry.line().author() + ": ";
			int nameWidth = font.width(name);
			String body = font.plainSubstrByWidth(entry.line().body(), Math.max(24, width - nameWidth));

			graphics.drawString(font, name, x, y, withAlpha(entry.line().color(), alpha), true);
			graphics.drawString(font, body, x + nameWidth, y, withAlpha(BODY_COLOR, alpha), true);
			y += lineHeight;
		}
	}

	private static int withAlpha(int rgb, int alpha) {
		return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
	}
}
