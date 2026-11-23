# TwitchIntegration

Read-only Twitch chat inside a Minecraft session (Fabric, 1.21.4).

The mod does no networking itself. A small Rust program, `twitch-bridge`,
connects to Twitch IRC over WebSocket and forwards chat to the game as
newline-delimited JSON over loopback TCP. That split keeps a bridge crash from
taking Minecraft down, keeps OAuth tokens out of the JVM entirely, and lets the
network side be tested without launching the game.

```
Twitch ──WSS/IRC──▶ twitch-bridge (Rust) ──NDJSON/127.0.0.1──▶ TwitchIntegration (Fabric)
                                                                    ├─ vanilla chat
                                                                    ├─ HUD overlay
                                                                    └─ extension hooks
```

Read-only chat needs **no credentials**: the bridge joins anonymously as
`justinfanNNNNN`. Credential policy for future authenticated features lives in
[`README.es.md`](README.es.md).

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 21 |
| Rust / Cargo | 1.80+ |
| Minecraft | 1.21.4 |
| Fabric Loader | 0.16.0+ |
| Fabric API | 0.119.4+1.21.4 |

## Build

From the workspace root:

```bash
./gradlew :twitchintegration:build        # Windows: gradlew.bat
./gradlew :twitchintegration:runClient
```

The jar lands in `TwitchIntegration/build/libs/twitchintegration-1.21.4-<version>.jar`.
Gradle also compiles `bridge/` and packs the binary into the jar; pass
`-PskipBridge` if Rust is not installed (the mod still builds and can talk to a
bridge you started yourself).

Building the bridge alone:

```bash
cargo build --release --manifest-path TwitchIntegration/bridge/Cargo.toml
# or
TwitchIntegration/tools/build-bridge.sh          # .ps1 on Windows
```

Gradle builds the bridge for the machine it runs on. The packaged jar is therefore
platform-specific; manual mode works on any platform with a separately built
bridge.

## Run

1. Start the bridge (manual mode, the default):

   ```bash
   TwitchIntegration/bridge/target/release/twitch-bridge --channel yourchannel --port 38370
   ```

   Or test the network side alone, without Minecraft:

   ```bash
   TwitchIntegration/bridge/target/release/twitch-bridge --channel yourchannel --print
   ```

2. Put the jar in `mods/` and start the game.
3. Edit `config/twitchintegration.json` and set `"channel": "yourchannel"`.
4. Load a world and run `/twitch connect` (singleplayer also auto-connects).

### Auto-spawn mode

Set either `"mode": "auto"` or `"autoSpawn": true`. The mod locates the bridge
(bundled in the jar, then `bridge/target/release/`, then `PATH`), extracts it to
`config/twitchintegration/bin/<os>-<arch>/` if needed, and starts it with the
configured channel and port.

> A mod that extracts and runs an `.exe` looks like malware to SmartScreen and to
> other players. Manual mode avoids that reputation problem and is the better
> default for anything you distribute.

## Commands

| Command | What it does |
| --- | --- |
| `/twitch status` | Config, link state, Twitch state, message counters |
| `/twitch connect` | Start the link (and the bridge, in auto mode) |
| `/twitch disconnect` | Close the link and stop a spawned bridge |
| `/twitch reload` | Re-read the config without restarting |
| `/twitch test` | Inject a fake chat line to check display wiring |

Requires permission level 2, or any player in a singleplayer world.

**F10** toggles the HUD overlay (rebindable under Controls → TwitchIntegration).

## Config

`config/twitchintegration.json`, written with defaults on first launch:

```json
{
  "channel": "",
  "enabled": true,
  "display": {
    "serverChat": true,
    "hud": true,
    "prefix": "[Twitch] ",
    "maxLines": 8,
    "hudTtlSeconds": 12
  },
  "transport": {
    "autoConnect": true,
    "autoSpawn": false,
    "mode": "manual",
    "port": 38370,
    "bridgePath": "",
    "reconnectMaxSeconds": 60
  },
  "filters": {
    "blockedWords": [],
    "maxMessageLength": 300
  },
  "commands": {
    "enabled": true,
    "prefix": "!",
    "cooldownSeconds": 0
  },
  "actions": {
    "defaultDelayTicks": 20,
    "maxDelayTicks": 600,
    "maxPending": 256,
    "maxPendingPerAuthor": 8
  }
}
```

- `display.serverChat` broadcasts lines through the server's system chat, so all
  players see them. `display.hud` is the client-side overlay; it works even when
  server chat is off.
- `transport.bridgePath` overrides discovery. Leave empty to use the bundled
  binary, a local `bridge/target/release/` build, or `PATH`.
- `filters.blockedWords` is a case-insensitive substring list; matching lines are
  dropped before display.
- `commands` configures viewer `!commands`. No handlers come with the mod; see
  [`docs/EXTENDING.md`](docs/EXTENDING.md).
- `actions` limits delayed gameplay actions: `defaultDelayTicks` (20 = 1 s),
  `maxDelayTicks`, and global/per-author queue caps. Delays run on server ticks,
  so they pause with the game and are dropped when the world stops.

No credentials are stored by the config. When authenticated features arrive, they
belong in a gitignored file owned by the bridge — see [`README.es.md`](README.es.md).

## Extending it

The mod ships an empty hook surface, so gameplay reactions (`!spawn`, polls,
channel-point effects) live outside the mod's own code:

```java
public final class MyExtension implements TwitchIntegrationExtension {
    @Override
    public void onTwitchIntegrationInit() {
        TwitchIntegrationApi.registerCommand("coinflip", ctx -> {
            boolean heads = ThreadLocalRandom.current().nextBoolean();
            ctx.broadcast(ctx.author() + " flipped " + (heads ? "heads" : "tails"));
        });
    }
}
```

Add the class to the `twitchintegration:extensions` entrypoint in
`fabric.mod.json`.

**Endpoints**

| Call | Purpose |
| --- | --- |
| `onChat(listener)` | observe every displayed message |
| `registerCommand("name", handler)` | handle `!name args` |
| `onRawEvent(listener)` | future event types (subs, raids, redemptions) |
| `onStatus(listener)` | link/Twitch state changes |
| `sendChat(text)` | streamer → Twitch (prewired, unsupported on the read-only bridge) |

`ChatContext` exposes `broadcast`, `suppressDisplay`, `player`, `canModerate`,
`runCommand`/`runCommandAt`, and the delayed forms `runCommand(cmd, ticks)`,
`afterTicks(ticks, action)` and `afterSeconds(seconds, action)`. Delays return a
cancellable `ScheduledTask`, use `ctx.actionDelayTicks()` for the configured
1-second default, and are capped by the `actions` config block.

```java
ctx.player().ifPresent(player ->
        ctx.runCommandAt(player, "summon cow ~ ~ ~", ctx.actionDelayTicks()));
```

Full guide: [`docs/EXTENDING.md`](docs/EXTENDING.md).

## Behaviour notes

- All Twitch text is sanitised (`§`, control characters, length cap), rendered
  with `Component.literal`, and never parsed as a format string or JSON.
- Chat listeners and command handlers run on the server thread; HUD updates are
  marshalled to the client thread.
- Delayed actions are queued by server tick, are cancellable, and are cleared
  when the world stops. `/twitch status` shows how many are waiting.
- Bridge events are rate-limited (40 events/s per client); the mod drops
  unparseable lines and counts them.
- The bridge pings every 15 s; the mod reconnects if the link is silent for 45 s.
- Reconnects back off from 1 s to `transport.reconnectMaxSeconds` with jitter.
- Nothing is written to disk by the mod: no chat logs, no tokens.

## Project layout

```
TwitchIntegration/
├─ bridge/                       Rust bridge (IRC client + loopback TCP server)
├─ docs/EXTENDING.md             hook API guide
├─ src/main/java/com/vrogil/     entrypoint, config, bridge link, chat, commands,
│                                and the public api/ package
├─ src/client/java/…             HUD overlay, keybind
├─ src/main/resources/assets/    icon, lang, bundled bridge binary (generated)
└─ tools/                        standalone bridge build scripts
```

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `no bridge listening on 127.0.0.1:38370` | Bridge not started, wrong port, or blocked by a local firewall |
| `no bridge binary found` | Auto mode is on but nothing was built or bundled; run the bridge manually or set `bridgePath` |
| Connects but no messages | Channel is offline/quiet, or the name is misspelled (no `#`) |
| Nothing in chat, HUD works | `display.serverChat` is false, or you are not opped on a dedicated server |
| HUD invisible | `display.hud` is false or the overlay is toggled off with F10 |
| `sendChat` does nothing | Expected: the bridge is read-only and answers `cmd.say` with `unsupported` |

## Not included (yet)

Sending chat, EventSub (channel points, subs, raids, follows), OAuth device-code
login. The protocol is versioned and the hooks are in place, so those can be added
without breaking existing builds.
