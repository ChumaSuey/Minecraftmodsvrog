package com.vrogil.bridge;

/**
 * One chat line from Twitch, already decoded from the wire protocol.
 *
 * @param color Twitch's chat colour as {@code #RRGGBB}, or an empty string when
 *              the user has not picked one.
 */
public record ChatMessage(
		String id,
		String user,
		String displayName,
		String color,
		String message,
		int bits,
		boolean isMod,
		boolean isSubscriber,
		boolean isBroadcaster
) {
}
