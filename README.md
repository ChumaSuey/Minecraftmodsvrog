# MinecraftModsVrogil

A small Fabric mod workspace. Each mod lives in its own directory and builds from
the single Gradle wrapper at the repository root.

| Mod | Folder | Jar |
| --- | --- | --- |
| **TwitchIntegration** — read-only Twitch chat in-game, fed by a local Rust bridge | [`TwitchIntegration/`](TwitchIntegration/) | `twitchintegration-1.21.4-<version>.jar` |

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 21 |
| Rust / Cargo | 1.80+ (only for building the Twitch bridge) |
| Minecraft | 1.21.4 |
| Fabric Loader | 0.16.0+ |
| Fabric API | 0.119.4+1.21.4 |

## Build

```bash
./gradlew build                       # all mods           (Windows: gradlew.bat)
./gradlew :twitchintegration:build    # one mod
./gradlew :twitchintegration:runClient
```

Artifacts land in `<ModFolder>/build/libs/`.

Rust is optional for the TwitchIntegration build: pass `-PskipBridge` to skip the
bridge compile, or install Cargo to have the binary bundled into the jar. Every
mod's own README has the full details.

## Layout

```
.
├─ .github/workflows/          # CI for the whole workspace
├─ gradle/ gradlew gradlew.bat # one wrapper
├─ settings.gradle             # includes each mod
├─ build.gradle                # `build`/`clean` fan out to every mod
├─ gradle.properties           # shared Minecraft/Fabric pins + per-mod versions
└─ TwitchIntegration/          # mod directory (sources, Rust bridge, docs, tools)
```

To add a mod: create a directory with a `build.gradle` that applies the Loom
plugin, add `include '<name>'` plus `project(':name').projectDir = file('<Folder>')`
in `settings.gradle`, and give it a version property here.

## License

See [`LICENSE`](LICENSE).
