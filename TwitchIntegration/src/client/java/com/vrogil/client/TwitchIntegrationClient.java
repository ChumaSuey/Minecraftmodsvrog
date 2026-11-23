package com.vrogil.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vrogil.TwitchIntegrationMod;
import com.vrogil.client.hud.TwitchChatHud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Client-only wiring: HUD overlay and its keybind. */
public class TwitchIntegrationClient implements ClientModInitializer {
	private static KeyMapping toggleHud;

	@Override
	@SuppressWarnings("deprecation")
	public void onInitializeClient() {
		// The main entrypoint must stay client-free, so it exposes a sink and the
		// client decides how lines are displayed.
		TwitchIntegrationMod.setHudSink(line -> {
			Minecraft client = Minecraft.getInstance();
			if (client != null) {
				client.execute(() -> TwitchChatHud.push(line));
			}
		});

		toggleHud = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.twitchintegration.toggle_twitch_hud",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F10,
				"category.twitchintegration"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleHud.consumeClick()) {
				TwitchChatHud.toggle();
			}
		});

		// HudRenderCallback is still the simplest hook on Fabric API 0.119.4 for
		// 1.21.4. If it is removed in a later API, only this line moves to the
		// HudLayerRegistrationCallback API.
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> TwitchChatHud.render(graphics));
	}
}
