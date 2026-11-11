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

Minecraft 26.3 with Fabric Loader 0.18.4 or newer, then in `mods/`:
Fabric API (0.165.0+26.3 or newer) and this jar. Cloth Config is bundled
inside the jar. ModMenu (21.x) is optional: with it, the settings screen
appears under Mods > Collins; without it everything else works the same.
On first use the mod downloads `yt-dlp` and `ffmpeg` into the game
folder and caches videos under `collins-cache/`.

Client commands: `/collinsc time`, `/collinsc quality <value>`,
`/collinsc sites [on|off]`, `/collins-cache info|open|delete|clear|limit [GB]`,
`/collins-yt info|install|update`.

## Only play YouTube and Twitch (on by default)

The player's own game downloads a screen's video, straight from the site in
the address, when they come near a playing screen. So the mod has a safety
setting, **Only play YouTube and Twitch**, which is **on by default**: the mod
then refuses to load a video from any site that is not on its allowed list,
whatever the server sends. A refused screen stays blank; nothing is fetched,
resolved or downloaded for it, and the player gets one chat line per screen
and address:

> Blocked: this server asked to play a video from example.com. Turn off "Only
> play YouTube and Twitch" in the Collins settings (or run /collinsc sites off)
> to allow it.

- Allowed list (editable in the settings screen or `config/collins.json`,
  `allowedHosts`): `youtube.com`, `www.youtube.com`, `m.youtube.com`,
  `music.youtube.com`, `youtu.be`, `youtube-nocookie.com`, `twitch.tv`,
  `www.twitch.tv`, `m.twitch.tv`. A listed site also allows its subdomains
  (`gaming.youtube.com`), but only the site part of the address counts: look-
  alikes such as `youtube.com.evil.example`, `notyoutube.com` or
  `https://youtube.com@evil.example/` are refused. The rules are the same as
  the `collins-paper` server plugin's, so the two never disagree.
- The check is on the address the server sent, before anything is resolved.
  The video files YouTube and Twitch then point to (`googlevideo.com`,
  `ttvnw.net`, ...) still load.
- Change it in Mods > Collins > Safety & storage (with ModMenu), with
  `/collinsc sites on|off`, or with `onlyAllowedSites` in
  `config/collins.json`. It takes effect immediately.

**Admin-approved videos.** On a server running collins-paper 1.7.0 or newer,
an admin can approve an address from another site (a direct `.mp4`, RuTube,
VK, ...) and the server then sends it to everyone. Players who keep this
setting on will **not** see those screens: that is the trade-off of a
client-side safety switch. To watch them, turn the setting off (or add that
site to the allowed list).

## Video cache limit

Downloaded videos are kept under `collins-cache/` (all sites together) up to a
limit, **4 GB by default** (1 to 64 GB). When a new download would go over it,
the oldest videos are deleted first; a single video bigger than the limit is
not downloaded (the player is told so once, with how to raise the limit).
Change it in Mods > Collins > Safety & storage, with
`/collins-cache limit <GB>`, or with `cacheLimitGb` in `config/collins.json`.
`/collins-cache info` shows how much is used against the limit. Before 1.6.0
the cache had a fixed 16 GB cap that did not count YouTube, RuTube or VK
downloads at all.

## Differences from upstream

- Ported to Minecraft 26.3 (built against Fabric Loader 0.20.1, runs on
  0.18.4+; Fabric API 0.165.0+26.3, Cloth Config 26.3.159 bundled, ModMenu
  21.0.0 optional, Loom 1.19).
- "Only play YouTube and Twitch" safety setting, on by default, and a
  configurable video cache limit (see above).
- The Modrinth update checker is off by default (`checkModrinthUpdates` in
  `config/collins.json`); this fork is private and never published there.
