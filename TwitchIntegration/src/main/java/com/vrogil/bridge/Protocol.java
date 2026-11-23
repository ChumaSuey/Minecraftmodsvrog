package com.vrogil.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Newline-delimited JSON protocol shared with {@code bridge/src/protocol.rs}.
 *
 * <p>Every message is {@code {"v":1,"type":"...","ts":...,"data":{...}}}.
 * Unknown types are ignored on both sides so events can be added without
 * breaking an older jar.
 */
public final class Protocol {
	public static final int VERSION = 1;
	private static final Gson GSON = new Gson();

	private Protocol() {
	}

	public record Incoming(String type, JsonObject data) {
	}

	public static String hello() {
		JsonObject data = new JsonObject();
		data.addProperty("client", "minecraft-mod");
		data.addProperty("protocol", VERSION);
		return envelope("hello", data);
	}

	public static String configure(String channel) {
		JsonObject data = new JsonObject();
		data.addProperty("channel", channel == null ? "" : channel);
		return envelope("cmd.configure", data);
	}

	public static String pong() {
		return envelope("cmd.pong", new JsonObject());
	}

	public static String shutdown() {
		return envelope("cmd.shutdown", new JsonObject());
	}

	public static String say(String text) {
		JsonObject data = new JsonObject();
		data.addProperty("text", text == null ? "" : text);
		return envelope("cmd.say", data);
	}

	public static Incoming parse(String line) {
		try {
			JsonElement element = JsonParser.parseString(line);
			if (!element.isJsonObject()) {
				return null;
			}
			JsonObject root = element.getAsJsonObject();
			JsonElement type = root.get("type");
			if (type == null || !type.isJsonPrimitive()) {
				return null;
			}
			JsonObject data = root.has("data") && root.get("data").isJsonObject()
					? root.getAsJsonObject("data")
					: new JsonObject();
			return new Incoming(type.getAsString(), data);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static ChatMessage chat(JsonObject data) {
		String message = string(data, "message", "");
		if (message.isBlank()) {
			return null;
		}
		String user = string(data, "user", "");
		String displayName = string(data, "displayName", user.isBlank() ? "twitch" : user);
		return new ChatMessage(
				string(data, "id", ""),
				user,
				displayName,
				string(data, "color", ""),
				message,
				integer(data, "bits", 0),
				bool(data, "isMod", false),
				bool(data, "isSubscriber", false),
				bool(data, "isBroadcaster", false)
		);
	}

	public static String string(JsonObject data, String key, String fallback) {
		JsonElement element = data.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsString();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static int integer(JsonObject data, String key, int fallback) {
		JsonElement element = data.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static boolean bool(JsonObject data, String key, boolean fallback) {
		JsonElement element = data.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static String envelope(String type, JsonObject data) {
		JsonObject root = new JsonObject();
		root.addProperty("v", VERSION);
		root.addProperty("type", type);
		root.add("data", data);
		return GSON.toJson(root);
	}
}
