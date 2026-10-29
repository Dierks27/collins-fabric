# collins-fabric

Client half of **Collins**, the video-screen system for Minecraft: a Fabric
client mod that decodes and plays YouTube, Twitch and direct video URLs on the
screens the `collins-paper` server plugin creates. You need both halves.

This repository is a fork of [imsawiq/collins](https://github.com/imsawiq/collins)
by Sawiq, Zorsh and EssciZ, licensed under the GPL-3.0 (see `LICENSE` and
`NOTICE`). The fork starts from upstream commit `6d77f687adedc2814265116e76235d81d58bffbf` (branch 26.1) and
targets Minecraft 26.3.

## Build

Requires an installed JDK 25 (any vendor): Gradle auto-detects it, or set
`JAVA_HOME` / `org.gradle.java.home` to point at it. The foojay resolver in
`settings.gradle` only provisions the compile toolchain, not the Gradle daemon.
The first build downloads the Minecraft 26.3 client and assets and takes
several minutes.

```bash
./gradlew build
```

The mod jar is written to `build/libs/collins-fabric-<version>-mc26.3.jar` and
bundles the JavaCV / FFmpeg natives for Windows x64, Linux x64, macOS x64 and
macOS arm64. GitHub Actions builds every push and attaches the jar to a Release
on a `v*` tag.

## Install (each player's PC)

Minecraft 26.3 with Fabric Loader 0.20.1 or newer, then in `mods/`:
Fabric API (0.165.0+26.3 or newer), Cloth Config (26.3.x), ModMenu (21.x) and
this jar. On first use the mod downloads `yt-dlp` and `ffmpeg` into the game
folder and caches videos under `collins-cache/`.

Client commands: `/collinsc time`, `/collinsc quality <value>`,
`/collins-cache info|open|delete|clear`, `/collins-yt info|install|update`.

## Differences from upstream

- Ported to Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3,
  Cloth Config 26.3.159, ModMenu 21.0.0, Loom 1.19).
- The Modrinth update checker is off by default (`checkModrinthUpdates` in
  `config/collins.json`); this fork is private and never published there.
