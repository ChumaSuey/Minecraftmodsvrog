# Extending TwitchIntegration

TwitchIntegration exposes an empty hook surface so gameplay reactions can live
outside the mod's own code. Nothing is registered by default, so adding an
extension cannot change behaviour until you claim a hook.

## 1. Add an extension class

```java
package com.vrogil.ext;

import com.vrogil.api.TwitchIntegrationApi;
import com.vrogil.api.TwitchIntegrationExtension;

public final class MyExtension implements TwitchIntegrationExtension {
    @Override
    public void onTwitchIntegrationInit() {
        // register hooks here
    }
}
```

Then list it under the custom entrypoint in `fabric.mod.json`:

```json
"entrypoints": {
  "twitchintegration:extensions": [
    "com.vrogil.ext.MyExtension"
  ]
}
```

A separate mod jar can declare the same entrypoint key and depend on
TwitchIntegration instead of editing this file.

## 2. Endpoints at a glance

### Events

| Call | Callback | Fires |
| --- | --- | --- |
| `TwitchIntegrationApi.onChat(listener)` | `onChat(ChatContext)` | every line that survived the config filters |
| `TwitchIntegrationApi.registerCommand(name, handler)` | `handle(ChatContext)` | `!name args` from chat |
| `TwitchIntegrationApi.onRawEvent(listener)` | `onEvent(String type, JsonObject data)` | bridge events with no typed model yet (future subs/raids/redemptions) |
| `TwitchIntegrationApi.onStatus(listener)` | `onStatus(String state)` | `link-established`, `link-lost`, `connecting`, `connected`, `reconnecting`, `disconnected` |
| `TwitchIntegrationApi.sendChat(text)` | — | streamer → Twitch; prewired but the read-only bridge answers `unsupported` today |

Every `register…` call returns an `AutoCloseable` that removes the callback.

### Actions on `ChatContext`

| Endpoint | Effect |
| --- | --- |
| `author()`, `body()`, `color()`, `channel()`, `bits()` | message data (author/body already sanitised) |
| `isBroadcaster()`, `isMod()`, `isSubscriber()`, `canModerate()` | badge checks |
| `player()`, `server()` | `Optional<ServerPlayer>` by username, and the server |
| `commandName()`, `args()`, `isCommand()` | set by the command router |
| `broadcast(text)`, `broadcastRaw(component)` | send a styled line to all players |
| `suppressDisplay()` | hide the original line from chat and HUD |
| `runCommand(command)` | vanilla command at world spawn, console permissions |
| `runCommandAt(player, command)` | same, at a player's position and dimension |
| `runCommand(command, delayTicks)` | delayed form, returns `ScheduledTask` |
| `runCommandAt(player, command, delayTicks)` | delayed form at the player |
| `afterTicks(ticks, action)` | queue any `Runnable` after N ticks |
| `afterSeconds(seconds, action)` | queue any `Runnable` after N seconds (20 ticks/s) |
| `actionDelayTicks()` | configured default delay (`actions.defaultDelayTicks`) |
| `pendingActionCount()` | actions currently waiting in the queue |

Callbacks run on the **Minecraft server thread** when a world is loaded. Keep them
fast and never block. Exceptions are caught and logged per listener, so one broken
extension cannot break chat.

## 3. Delay and cooldown

Two different things:

- **Cooldown** (`commands.cooldownSeconds`) throttles how often a viewer may run a
  registered command. It never delays the action.
- **Delay** (below) postpones a specific action after the message.

```java
TwitchIntegrationApi.registerCommand("spawn", ctx -> {
    if (!ctx.canModerate() || ctx.args().isEmpty()) return;
    String mob = ctx.args().get(0).toLowerCase(Locale.ROOT);
    if (!ALLOWED_MOBS.contains(mob)) return;

    ctx.player().ifPresent(player -> {
        ctx.broadcast(ctx.author() + " summoned a " + mob + "!");
        // default 1 s delay, so viewers see the message before the mob appears
        ctx.runCommandAt(player, "summon " + mob + " ~ ~ ~", ctx.actionDelayTicks());
    });
    ctx.suppressDisplay();
});
```

```java
// arbitrary delayed work
ScheduledTask task = ctx.afterSeconds(5, () -> {
    // Re-check state: the player may have logged out by now.
    ctx.player().ifPresent(player -> ctx.runCommandAt(player, "effect give @s jump_boost 3 2"));
});

// cancellation is best-effort
task.cancel();
```

Delays are measured in **server ticks** (20 per second), so they pause with
singleplayer and never run after the world stops. Queued actions are dropped on
`SERVER_STOPPING`. A callback keeps the same `ChatContext`, but the world can
change in between: re-check `player()` and `canModerate()` inside long delays.

Submitting a delayed action can be rejected (queue full, or too many pending for
one author). The returned handle then reports `isCancelled() == true` and the
action never runs. Limits:

```json
"actions": {
  "defaultDelayTicks": 20,
  "maxDelayTicks": 600,
  "maxPending": 256,
  "maxPendingPerAuthor": 8
}
```

`defaultDelayTicks` is only used when a handler reads `ctx.actionDelayTicks()`;
`afterTicks`/`afterSeconds` use exactly what you pass (clamped to
`maxDelayTicks`).

## 4. Examples

```java
// Observe
TwitchIntegrationApi.onChat(ctx ->
        LOGGER.info("[twitch] {}: {}", ctx.author(), ctx.body()));

// Simple reply, still shows the original message
TwitchIntegrationApi.registerCommand("coinflip", ctx -> {
    boolean heads = ThreadLocalRandom.current().nextBoolean();
    ctx.broadcast(ctx.author() + " flipped " + (heads ? "heads" : "tails"));
});

// Plain chat reaction (no "!")
TwitchIntegrationApi.onChat(ctx -> {
    if (!ctx.body().toLowerCase(Locale.ROOT).contains("dance")) return;
    ctx.afterSeconds(1.5, () -> ctx.player().ifPresent(player ->
            ctx.runCommandAt(player, "effect give @s jump_boost 3 2", 0)));
});

// Future event types flow through the raw hook
TwitchIntegrationApi.onRawEvent((type, data) ->
        LOGGER.info("[twitch] unhandled event {} -> {}", type, data));
```

## 5. Command settings

`config/twitchintegration.json`:

```json
"commands": {
  "enabled": true,
  "prefix": "!",
  "cooldownSeconds": 0
}
```

`cooldownSeconds` is per user, per command, and only applies to registered
commands. Unknown `!words` are shown as ordinary chat and never error.

## 6. Security notes

- `runCommand`/`runCommandAt` execute with **console permissions**. Treat command
  arguments as untrusted input: gate on `canModerate()`, whitelist values, and
  never concatenate raw chat text into a command that gives items or changes the
  world.
- Delayed actions can fire after a viewer is no longer relevant. Re-validate
  everything inside the callback.
- `sendChat` is intentionally inert until the authenticated build lands; do not
  assume messages reach Twitch yet.
- Nothing in this API stores credentials. See `README.es.md` for credential rules.
