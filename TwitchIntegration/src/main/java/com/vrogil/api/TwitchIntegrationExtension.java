package com.vrogil.api;

/**
 * Optional entrypoint for code that reacts to Twitch events.
 *
 * <p>Implement this, then add the class name to the
 * {@code twitchintegration:extensions} entrypoint in {@code fabric.mod.json}:
 *
 * <pre>{@code
 * TwitchIntegrationApi.registerCommand("coinflip", context -> {
 *     boolean heads = ThreadLocalRandom.current().nextBoolean();
 *     context.broadcast(context.author() + " flipped " + (heads ? "heads" : "tails"));
 * });
 * }</pre>
 *
 * <p>Called once during mod initialisation, before any world loads. A separate
 * mod jar can register the same entrypoint key and call the API directly.
 */
public interface TwitchIntegrationExtension {
	void onTwitchIntegrationInit();
}
