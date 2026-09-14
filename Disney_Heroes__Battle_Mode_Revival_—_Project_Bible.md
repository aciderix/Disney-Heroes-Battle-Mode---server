# Disney Heroes: Battle Mode Revival — Project Bible / Handover Bible

**Repository inspected:** `aciderix/Disney-Heroes-Battle-Mode---server`  
**Inspection basis:** source tree, Java/Python/TypeScript/Shell/Rust/CI sources, project memory and journal, architecture documents, and test inventories.  
**Inspection mode:** code and documentation reading only. No build, server start, test execution, APK patch, or game execution was performed during this handover pass.

> **Authority rule.** The current source code is the primary authority for implementation status. `MEMORY.md`, `JOURNAL.md`, README files, and design documents are historical or architectural evidence and may lag behind the code. Where they disagree, this Bible records the discrepancy instead of silently choosing the more optimistic claim.

## 1. Executive Summary

This project is a preservation and revival effort for *Disney Heroes: Battle Mode* after the original online service became unavailable. The intended result is not a conventional rewrite of the game. It is a reproducible platform layer that reuses the recovered game client, data formats, networking classes, rendering code, and native behavior wherever possible, while supplying a self-hostable authoritative server, a desktop port, content delivery, launcher tooling, authentication, and optional APK tooling.

The repository currently contains substantially more than the early bootstrap README suggests. The source tree includes a Java authoritative server, a Python content/login service, an HTTP launcher daemon, a React/Tauri launcher frontend, server/client bundle packaging, mnemonic Ed25519 authentication, an optional community server directory, fixed-target and directory-picker APK patch paths, desktop rendering shims, Spine/particle backends, and a large smoke/headless test suite. Several of those components are explicitly marked as tested in the journal and source comments, but this document does **not** independently upgrade those claims because this pass was read-only.

The overall status is therefore mixed:

| Area | Evidence-based status | Meaning |
|---|---|---|
| Server protocol foundation | **Implemented; historically headless-tested** | The code decodes the game handshake and uses the original protocol stack, but full game behavior remains broader than the login handshake. |
| Content server and local login bridge | **Implemented; historically tested for core behavior** | The Python service serves manifests/assets and can mint strict-mode login tickets through the Java AuthService. Internet deployment is not complete. |
| Persistence/authentication | **Implemented; historically headless-tested** | SQLite-backed accounts, sessions, Ed25519 challenge-response, registration, and ticket binding exist. |
| Desktop client | **Implemented; partially verified** | The port has substantial LWJGL/native/Spine/particle infrastructure and historical in-game evidence for multiple screens and modes, but exhaustive screen/mode coverage and current clean-checkout reproducibility are not established. |
| Java particle backend | **Experimental/partial** | The original native path through unidbg remains the reference; the Java particle path is switchable and has comparison diagnostics, but remaining parity work is known. |
| Launcher daemon | **Implemented; historically smoke-tested** | It is a loopback HTTP daemon with identity, host, build, play, settings, directory, logs, and admin proxy routes. |
| Launcher UI | **Implemented in source; not fully validated here** | React screens call real daemon endpoints, but the full windowed distribution and UX remain platform-sensitive. |
| Server/client packaging | **Implemented; historically bundle-tested** | Generated bundles include scripts and a runtime strategy; the launcher package embeds JDK/Python/tooling. |
| APK patching | **Implemented but fragile/limited** | Fixed redirection and picker injection paths exist, including XAPK merge, Smali rebuild, signing, and final checks. Regular-user reliability is not established. |
| Public Internet networking | **Partial** | Directory reachability checks, UPnP attempts, signatures, and remote admin exist; a complete secure Internet deployment protocol does not. |
| Full end-to-end game | **Partial; not fully verified** | Core loops and many individual modes have historical smoke or in-game evidence, but no evidence here establishes one complete start-to-finish production-quality playthrough covering every major system, restart, reconnect and client/platform path. |

The project should be handed to a new team as a serious, technically advanced preservation prototype with meaningful working slices—not as a finished consumer release.

## 2. Project Philosophy

The project follows a **minimal-modification and reuse-first** philosophy. The server is intended to reuse the game's own NIO protocol, message factories, codecs, and data semantics rather than inventing a parallel wire protocol. The desktop port similarly prefers shims, adapters, and dispatch layers over rewriting game logic. The source comments repeatedly distinguish glue from recreation: the project should supply missing platform services while preserving original behavior wherever evidence permits.

Reproducibility is a central design constraint. Game-derived artifacts are generated from an APK supplied by the user rather than committed or redistributed. Extraction, decompilation, reframe, packaging, APK patching, and launcher assembly are scripted. Heavy or copyright-sensitive artifacts are treated as regenerable. Runtime dependencies are either embedded, downloaded into caches, or explicitly reported.

Debuggability is treated as a product feature. The repository contains smoke tests, headless protocol tests, screen-contract tooling, mode graph discovery, particle comparison diagnostics, logs, status endpoints, process lifecycle reporting, and explicit environment toggles. The intended workflow is to expose what the code is doing rather than assert success because a button returned 200.

The project also has a strong anti-overclaiming rule. Source comments frequently state when a feature is only an increment, a local proof, or a deliberate refusal rather than a fake success. This is the correct standard for future work: retain explicit `VERIFIED`, `IMPLEMENTED BUT NOT FULLY VERIFIED`, `PARTIAL`, `BROKEN`, `MISSING`, and `UNKNOWN` labels.

AI-assisted development is documented through `MEMORY.md`, `JOURNAL.md`, `CLAUDE.md`, and `.claude/settings.json`. The repository uses persistent project memory, detailed chronological journal entries, compact-resume hooks, source-anchored contracts, and observable tools so that an agent or new developer can recover context. The `.claude/settings.json` hook runs `post-compact-reprise.sh` after compaction, which reflects the project's priority on continuity and controlled AI work.

## 3. History and Major Milestones

The historical record describes an evolution from APK reconnaissance and content extraction into a multi-layer revival stack:

1. **Bootstrap and reconnaissance.** The project established the preservation goal, reverse-engineered key APK structures, extracted game data, identified the live content manifest, and documented the legal/distribution boundary: the repository supplies software and tooling, while the user supplies the game APK.
2. **Content delivery foundation.** `server/content_server.py` was introduced to replace the unavailable content service without changing the game. It rewrites `index.txt`, serves cached archives, and falls back to archive.org.
3. **Protocol server foundation.** The Java server began by reusing the game's TCP stack and codecs. The `ClientInfo1 → BootData1` handshake was established as a real socket path in historical smoke tests.
4. **Persistence and authentication.** Mnemonic identities, deterministic Ed25519 keys, account storage, challenge-response sessions, registration, ticket minting, and strict-mode gates were added.
5. **Desktop port.** The project moved from a minimal launcher/client experiment toward an LWJGL3 desktop backend with platform shims, native Spine options, audio extraction, and particle work.
6. **Framing and JVM stability.** Reframing dex2jar output with ASM `COMPUTE_FRAMES`, avoiding `-Xverify:none`, and using conservative JVM settings addressed instability described in the journal.
7. **Launcher core.** The planned launcher daemon became source code: identity orchestration, local hosting, build jobs, play lifecycle, settings, logs, remote admin proxying, and directory integration are now present.
8. **Packaging.** Server and client bundles, embedded JRE logic, launcher packages, embedded Python, and Windows PortableGit support were added to move toward one-click use.
9. **APK and community directory.** Fixed server redirection, XAPK merging, signing checks, picker injection, signed server registration, reachability probing, and public-directory UI were added.
10. **Diagnostics and industrialization.** Screen contracts, mode graphs, particle Java/native comparison, combat probes, and CI package verification expanded the project's verification surface.

The historical journal is valuable for causes and failed approaches, but it is not a substitute for current source inspection. In particular, several planning documents still describe launcher and APK work as future work even though corresponding source files now exist.

## 4. Current Project Status

### 4.1 Status vocabulary

- **Verified:** implementation was inspected and/or there is explicit repository evidence of testing. This Bible does not claim that the current pass executed those tests.
- **Implemented but not fully verified:** relevant code exists, but coverage, current reproducibility, or integration evidence is insufficient.
- **Partial:** only a subset of the intended feature exists.
- **Broken:** source or recorded evidence indicates a current failure.
- **Missing:** no implementation was found.
- **Unknown:** the repository evidence read here is insufficient.

### 4.2 High-level assessment

The project has a working **local/LAN-oriented vertical slice in principle**: a content/login service, a Java TCP server, a client port, strict/permissive authentication paths, and launcher lifecycle orchestration. The source also contains a public-directory path designed to reject unreachable hosts. However, the target of “a normal user downloads the package, supplies an APK, hosts or joins over the Internet, and plays the complete game reliably” is not yet established.

The most important unfinished areas are Internet protocol/security completion, Java particle parity, APK reliability, comprehensive game testing, historical content/live-ops completeness, social coverage, and launcher UX/productization. These are not cosmetic tasks; they are the difference between a technically impressive prototype and a dependable revival release.

### 4.3 Capability matrix — evidence scope

The following matrix separates source presence from validation evidence. A check in the code column means that relevant implementation was inspected; it does not mean that every variant or platform is complete.

| Capability | Source surface | Smoke/headless evidence | Historical in-game evidence | Full system/platform coverage |
|---|---|---|---|---|
| Login, boot, sessions and persistence | Present | Present for core paths | Present for local slices | Not exhaustive |
| Campaign/combat/rewards | Present | Present for selected handlers | Present for selected flows | Not exhaustive |
| Chests and hero progression | Present | Wire/persistence probes | Present for tutorial and selected chests | Paid/battle-pass paths partial |
| Guilds, guild war and guild chat | Present | Dedicated guild/wire paths | Present for selected guild flows | Social/economy variants incomplete |
| Arena, invasion, surge and Port | Present | Mode-specific probes exist | Present for selected modes | Mode matrix incomplete |
| Events/contests/live-ops | Present as builders, state and admin configuration | Selected construction/handler evidence | Limited and event-dependent | Historical content completeness unknown |
| Desktop rendering/particles | Present | Headless/native comparison tooling | Multiple historical captures | Full screen/effect/platform matrix incomplete |
| APK picker and fixed-target pipeline | Present | Static/build checks and packaging probes | Runtime coverage limited | Android version/device matrix incomplete |
| Public Internet hosting | Partial | Directory/signature/probe paths | Local/LAN-oriented evidence | Remote strict play and hardening incomplete |

This matrix is deliberately conservative: it avoids both the false claim that only the login exists and the opposite false claim that source presence equals complete product validation.

## 5. Overall Architecture

The system is divided into these layers:

```text
User APK
  ├─ extraction/decompilation/reframe ──> game data, framed game jars, bundles
  └─ APK patch/picker pipeline ─────────> signed side-load APK

Launcher UI (Tauri + React)
  └─ local HTTP ──> LauncherDaemon (Java, loopback only)
                    ├─ MnemonicIdentity/AuthService client
                    ├─ HostManager ──> LoginServer + content_server.py or bundle run.sh
                    ├─ BuildManager ──> extraction/decompile/reframe/package
                    ├─ PlayManager ──> generated client bundle
                    ├─ directory client / verifier
                    └─ admin proxy ──> local or remote AdminService

Game client
  ├─ HTTP content/login ──> content_server.py
  └─ TCP game protocol ──> LoginServer / original game networking classes
```

The launcher daemon binds to loopback using JDK `HttpServer`. It is intentionally separate from the remote game `AuthService` and from any remote admin service. The launcher keeps live process state in memory and exposes status/polling endpoints to the UI. The frontend contains no intended crypto or game protocol implementation.

## 6. Server

The Java server is under `server/java`. The central `dhserver.LoginServer` starts the game's TCP server, decodes the original `ClientInfo1` handshake, and returns boot data using the game's message and codec classes. This is a reuse-based approach rather than a custom protocol implementation.

The server includes:

- TCP login/game transport and original protocol shims.
- `AuthService` HTTP endpoints for challenge, verify, register, and ticket minting.
- `UserStore`/account persistence and `SessionStore` session binding.
- SQLite-backed server data and statistics paths.
- Admin service support, token authentication, metrics/status, logs, and operator-oriented endpoints.
- Directory identity and signed server-information classes.
- Game data and stats loading paths.
- Smoke and headless tests, including handshake, auth, account, persistence, and bundle probes.

The Python content server handles the HTTP side that the original client expects. It serves `/live/index.txt`, rewrites archive URLs to the local host, serves or caches `.zip` assets, supports `HEAD`, ranges, retries, atomic cache replacement, and provides `/login`. In strict mode, `/login` obtains a nominative ticket from `/auth/mint`; the launcher can write the authenticated user ID to a file after authenticating the mnemonic account. It also proxies public auth routes while deliberately not exposing `/auth/mint` directly.

**Limitations:** the server is not equivalent to a complete production game backend merely because the handshake works. Full gameplay state coverage, every screen's backend handlers, live operations, concurrency behavior, abuse resistance, migration tooling, and Internet hardening require additional evidence.

### 6.1 Implemented game-system surface

The server source is broader than a login skeleton. The current class map includes `ServerUser` and `UserStore` for player state, `ServerArena`/`ServerArenaLadder` for arena opponents and ranking, `ServerGuild` and donation requests for guild state, `ServerWar*` classes for war matching, members, cars, boxes, sabotage, scoring, scheduling and end state, `ServerInvasion*` classes for invasion rotation and player/boss state, `ServerSurge*` classes for surge maps, combat, rewards and state, `ServerExpedition`, `ServerFriendships`, `ServerChallenges`, `ServerTrials`, `ServerContest*`, `ServerMissions`, `ServerEvents`, `ServerSpecialEventsExt`, and `StoreOpponentSource`.

`LoginServer` routes a large set of wire messages and actions, including tutorial progression, chest opening, campaign attacks, action groups, player naming/language, weekly quest/battle-pass paths, invasion information, guild operations, friendships, challenges, mail/rewards and mode-specific actions. The exact supported surface must be read from the routing branches in `server/java/dhserver/LoginServer.java` and the corresponding `ServerUser` methods; class presence alone is not proof of complete behavior.

The strongest evidence is mode-specific and uneven. Campaign, chest, persistence, arena, guild, invasion, surge and difficulty-mode/Port work have dedicated smoke or historical in-game probes, while `docs/HEADLESS_VERIFICATION.md` explicitly lists SURGE, CITY WATCH, CHALLENGES and HEIST as still requiring the complete contract → scaffold → logic → wire/headless → in-game sequence. Some outcomes remain client-reported with server-side re-execution or validation rather than fully authoritative simulation. Paid chest/battle-pass shims, live event availability, and some post-tutorial actions remain partial.

### 6.2 Administration and live-ops

`dhserver.admin.AdminService` runs in the server JVM and is proxied by the game-free launcher. Every request requires a bearer or `X-Admin-Token`, compared in constant time. It provides `/admin/ping`, `/admin/monitor`, content-era release selection, clock offsets, player lookup and mutation (`giveResource`, `grantHero`, team level, campaign/tutorial/unlock operations), audit output, event insertion/removal/clear, real enum discovery, and moderation endpoints for ban, unban, mute, unmute, kick, and moderation listing. `ContentEra`, `EventsAdmin`, `AdminAudit`, and `Moderation` implement the associated domains.

The service can bind loopback or a configured network address and can use an `SSLContext`, but the source explicitly leaves TLS hardening and rate limiting to the networking chantier. It is mono-shard by design (`SHARD = 1`). Player edits operate on persisted state and may not immediately update an already-live in-memory session; the source documents that this can be overwritten on reconnect. These are important operational limitations.

### 6.3 Social and multi-user behavior

Multi-user transport and social gameplay are separate capabilities. `LoginServer` maintains a connection-to-user map and an online user-to-socket map, then uses `pushToGuild` for best-effort real-time delivery. `ServerGuild` persists bounded wire histories for guild chat, donation requests, gifts, contest contributions, invasion bosses and war-season summaries. The `SendChat` path is specifically implemented for the `GUILD` room: it builds an authoritative `Chat`, archives it, returns it to the sender, and broadcasts it to online guild members. Non-guild rooms such as global/VIP are explicitly ignored for now because they require an inter-shard bus. Moderation can suppress muted users before archive or broadcast.

This means the project has a meaningful guild/community slice, not merely multiple independent accounts, but it does not yet provide the full original social network. Guild economy, gifts, donations, rankings, global/VIP chat, offline delivery semantics, abuse controls, and all social UI paths require separate evidence.

### 6.4 Backend versus historical content

An implemented server class does not prove that historical live content is complete. The backend can construct or persist event objects and operator specifications through `ServerEvents`, `EventsAdmin`, `ContentEra`, and `ServerSpecialEventsExt`, while the client-facing values still depend on extracted tables, strings, event snapshots, release selection, timers, rotations, reward definitions, and available assets. Historical events may therefore be structurally supported while their original schedules, offers, rewards, or asset sets remain incomplete or unknown. This distinction is central for contests, trials, battle-pass variants, shops, timed offers, guild gifts, heists and other seasonal systems.

## 7. Client / Java Port

The desktop port is in `desktop-port`. `build.gradle` uses LWJGL 3.3.4, libGDX 1.9.7 native support, JSON/Android compatibility libraries, the extracted game logic jar, optional Spine Java runtime, audio backend code, and unidbg/dynarmic dependencies. Native classifiers are selected from the build OS rather than hard-coded to Linux.

The client entry point is `dhdesktop.DesktopLauncher`. The surrounding scripts prepare generated logic jars, native libraries, runtime assets, platform-specific launchers, and environment-based diagnostics. `run-online.sh` demonstrates the intended local vertical slice: start content/login, start the Java server, then launch the client with `ServerType.LIVE` redirected to the local content endpoint.

The port contains significant compatibility work: Android API/library shims, LWJGL3 backend replacements, native loading, audio delegation, Spine dispatch, atlas handling, and test drivers. Historical journal entries provide in-game evidence across core loops and multiple screens/modes, so the accurate status is **substantially implemented and partially in-game validated**. Exhaustive screen/mode coverage, clean-checkout reproducibility, every platform path, and long-running sessions remain unverified.

## 8. Rendering / Spine / Particles

Spine and particle behavior has two principal strategies:

1. The default particle path executes the original `libspine-native.so` through unidbg ARM emulation and virtual Android/JNI support.
2. The experimental Java path, enabled through `dh.particlebackend=java`, uses the game's own `ParticleEmitter` classes. `JavaParticleEngine` adapts the binary `.np` v3 format, resolves atlas sprites/regions/pages, handles flipbook frames, emits game-format vertices, and keeps disposed handles alive until particles complete.

`com.perblue.heroes.cparticle.Native` dispatches between these paths. A comparison mode duplicates operations to both backends and diffs vertices, draw calls, colors, and UVs. This is valuable because unidbg is treated as the current oracle while Java parity is being developed.

The Java backend remains **experimental/partial**. Source comments identify known parity-sensitive areas such as transparency slot order, gradient color/timeline offsets, flipbooks, `AboveZ`/`BelowZ` behavior, multiply/Z-offset flags, lifecycle/disposal, and exact vertex output. The native/unidbg path should remain the reference until the comparison corpus demonstrates parity across the full effect set.

#### 8.1 The Java particle backend crashes the hub (2026-09-15) — why unidbg is the default

The Java backend was briefly made the **default** in generated client bundles. This made the game unusable: it crashed on the main hub screen for **every** player, on **every** hub display. The default was reverted to unidbg.

Mechanism, established by differential test and a JDI probe rather than inference:

- `JavaParticleEngine` fails to create some effects — specifically the glow of the hub's **PORT** icon (`world/env/mainscreen/vfx/mainscreen_port_*_glow.np`). The scene node is therefore present **without** a `ParticleEffectRenderable` component.
- `MainScreenDisplay.setPersistantGlowAlpha` has two code paths. The generic one null-checks the component (`ifnonnull` on `DHSpriteRenderable`). The special case for `features/port/port1-glow` does **not**: it iterates `node.children` and calls `child.getComponent(ParticleEffectRenderable).getTint()` directly.
- `MainScreen.updateSceneVisuals` loops over **all** `MainIconType` values unconditionally, so a single missing component is enough to throw a `NullPointerException` every time the hub refreshes.

Evidence: with `java`, the crash is systematic; with `unidbg`, the hub renders and the crash disappears. Assets were verified complete (zip↔disk: 17 866 intact, 0 missing, 0 truncated), so this is a backend defect, not missing content.

**Testing trap worth knowing.** `JavaParticleEngine.flagJava()` is a *cascade*: system property, then `DH_PARTICLEBACKEND`, then the marker file `<user.home>/.dh_particlebackend`. Every step only tests equality to `"java"` — none of them can *disable* the backend. Setting `DH_PARTICLEBACKEND=unidbg` therefore does **not** select unidbg: resolution falls through to the marker file, which may still contain `"java"` from an earlier session. An initial round of testing was invalidated this way and wrongly cleared the backend. To test unidbg for real, overwrite or delete the marker.

The CI workflow also builds a game-free host Spine native library for Linux and Windows, intended to provide a faster JNI backend without requiring a compiler on the player's machine. This improves distribution but does not remove the need for runtime integration validation.

## 9. Launcher

The launcher consists of `server/java/dhlauncher`, `launcher-ui/src`, and `launcher-ui/src-tauri`.

### 9.1 Daemon

`LauncherDaemon` binds only to `127.0.0.1` and exposes:

- `/health`
- `/identity/generate`, `/identity/login`, `/identity/register`
- `/servers`, `/servers/remove`, `/servers/ping`
- `/directory`, `/directory/verify`
- `/host/start`, `/host/stop`, `/host/status`, `/host/publicip`, `/host/upnp`, `/host/logs`
- `/build/start`, `/build/status`
- `/play`, `/play/stop`, `/play/status`
- `/settings`
- `/admin/target`, `/admin/target/clear`, and generic `/admin/*` proxying

The daemon loads optional `directory.env` beside its jar, keeps mnemonic private keys local, calls remote AuthService endpoints, and uses pinned TLS support for remote administrative targets. The TLS hostname-verification workaround is explicitly constrained by the source comments to pinned admin calls; this deserves continued security review.

### 9.2 Hosting

`HostManager` supports development mode (separate Java game server and Python content process) and generated bundle mode (one `run.sh`/`run.bat` process). It creates an admin token per session, writes logs, reports PIDs and ports, and stops child processes. Optional public publication attempts UPnP mappings for content/game/auth ports, detects CGNAT, supplies public game/content addresses, and passes signed-directory environment variables into the bundle.

The host operator is a separate role from the game administrator. The host operator manages ports, child-process lifecycle, bundle paths, public IP/NAT/UPnP, directory publication, environment variables, logs, and shutdown/recovery. The game administrator uses `AdminService` to manage players, resources, heroes, content era, events, moderation, and audit. The launcher may proxy the latter, but the permissions, security boundary, and failure modes are different and should remain separate in future UI and documentation.

### 9.3 Playing

`PlayManager` starts the generated client bundle and sets `DH_SERVER` plus an optional permissive `DH_USERID`. In strict mode it deliberately does not inject a user ID; the source documents that remote strict play still needs a dedicated client hook to carry the `loginRequestID`. This is a real remaining integration boundary, not a hidden success.

### 9.4 UI

The React application waits for daemon health, loads settings, enforces a persisted disclaimer version, and renders the shell. The Host screen polls real host status, auto-registers a local hosted server as a favorite, supports strict mode, and has opt-in directory publication. The Generate screen offers server, client, and APK targets, full/decompile/reframe and packaging options, fixed APK redirection, and directory picker mode. The source comments emphasize that displayed states should be backed by actual daemon responses.

The UI is implemented in source but remains not fully verified as a packaged end-user application. Error messages are still terse in several places, and several design-document screens/functions are not visibly represented in the files inspected here.

### 9.5 User workflow and local configuration

The intended user flow is: launch the packaged Tauri application; wait for the local daemon health gate; accept the disclaimer; select or import a personally supplied APK; choose server, desktop client, or APK output; poll the background build; place the generated server bundle in the Host screen or select a generated client bundle; optionally create/register or restore the mnemonic account; add/select a server; then start Play. Hosting can auto-register the local server as a favorite. Public publication is explicitly opt-in and requires a public address or successful UPnP assistance.

`LauncherConfig` resolves platform configuration under Windows `%APPDATA%\\DisneyHeroesPort`, Linux `$XDG_CONFIG_HOME/disney-heroes-port` or `~/.config/disney-heroes-port`, and macOS `~/Library/Application Support/DisneyHeroesPort`, with `dh.launcher.config` as an override. The daemon also accepts `--port`, `--project`, `dh.launcher.port`, `DH_DIRECTORY_URL`, `DH_DIRECTORY_ANON_KEY`, and related directory aliases. Build/host/play behavior is further controlled by `DH_CONTENT_PORT`, `DH_GAME_PORT`, `DH_AUTH_PORT`, `DH_ADMIN_*`, `DH_PUBLIC_*`, `DH_SERVER_*`, `DH_USERID`, `DH_PARTICLEBACKEND`, `DH_SPINEBACKEND`, `DH_FRAMES`, `DH_SHOT`, and other development flags. The full set is distributed across `LauncherDaemon`, `BuildManager`, `HostManager`, generated bundle scripts, `run-desktop.sh`, and `run-online.sh`; it should be consolidated into a versioned operator reference.

## 10. APK Pipeline

`tools/patch_apk.sh` provides a fixed redirection path. It:

1. Downloads/caches baksmali, smali, APK signer, and APKEditor.
2. Detects XAPK/APKS archives by internal `.apk` entries and merges them into a universal APK.
3. Locates the dex containing `login.disneyheroesgame.com`.
4. Disassembles the dex, edits `ServerType.smali` through `apk_redirect_smali.py`, and reassembles it.
5. Repackages without compressing `resources.arsc` and native `.so` files.
6. Removes old signatures, zipaligns and signs with uber-apk-signer.
7. Verifies that the new host exists and the old hostname is absent.
8. Refuses to deliver an APK with zero native libraries, identifying the common base-APK-only failure.

`BuildManager` exposes fixed-target mode and picker mode. Picker injection is handled by `tools/apk_inject_picker.sh` and the `mobile` sources. The launcher UI warns that the user must provide their own APK/XAPK and install the result outside the Play Store.

Status: **implemented but not reliable enough for regular users**. Known failure modes include APK/XAPK variant differences, changed obfuscation or class paths, missing native split libraries, downloader/tool version drift, signing/install restrictions, Android ABI/device compatibility, picker compilation/injection changes, and runtime behavior not covered by static verification. The repository correctly refuses several false-success cases, but that is not the same as broad compatibility.

## 10.1 APK, extracted data, and asset provenance

The repository's content provenance is documented in `docs/ASSETS.md` and `docs/RECON.md`, and the current tree contains the corresponding artifacts. `game/disney-heroes-12.1.0.apk` is the base APK; `libs/game.jar` and `libs/commons-logging.jar` are the committed decompilation/runtime jars used to avoid repeated conversion. `game-data/stats/` contains the extracted balancing tables (`.tab` and related table files), while `game-data/strings/` contains extracted localized `.properties` files. Both are generated from the user-supplied APK by `tools/extract_game_data.sh`; they are not equivalent to the external live asset archives.

`docs/ASSETS.md` records the `index.txt` manifest, the archive.org asset mirror, archive revisions, content categories, the local `assets-cache/` option, the `MISSING_ADDITIONAL` boot gate, and the distinction between downloadable client assets and balancing data baked into the APK or delivered through stat-sync. `docs/RECON.md` records APK identity, package layout, protocol classes, server/content endpoints, AssetUpdater behavior, the original XOR/Deflate stack, and the remaining need to verify runtime completeness of archived assets. These documents are supporting evidence; current source and observed execution status remain authoritative.

### Content Preservation & Provenance

Content preservation is a distinct subsystem from the private-server backend. The repository contains `index.txt` and `disney_heroes_live_index.txt`, `upload_batch.py`, `.github/workflows/upload_to_ia.yml`, and `docs/live-archive-inventory.json.gz`. The workflow manually uploads ten batches to the Internet Archive using credentials supplied as GitHub secrets; it does not run automatically on every commit. `docs/ASSETS.md` identifies the archived asset collection and its revisions, while `server/content_server.py` can redirect or relay requested archives and use a local non-committed cache.

| Artifact | Source | Regenerable | Role and distribution boundary |
|---|---|---:|---|
| `game-data/stats/*.tab` | User APK extraction | Yes | Server balancing/stat input; not hand-authored data |
| `game-data/strings/**/*.properties` | User APK extraction | Yes | Localized client/UI strings; regenerated with the extraction pipeline |
| `game/disney-heroes-12.1.0.apk` | Base APK input | No, except from an equivalent user source | Copyrighted game input; the README states that binaries/assets remain with rights holders |
| `libs/game.jar` and dependencies | APK/decompilation or supplied runtime artifact | Rebuildable with toolchain, but provenance-sensitive | Game logic/runtime input; version must be recorded |
| `index.txt` / `disney_heroes_live_index.txt` | Recovered historical content manifest | Not generated from current server state | Selects asset archives and revisions |
| Internet Archive asset zips | Historical asset preservation | Non-trivial | Client assets; availability/licensing and completeness remain explicit |
| `assets-cache/` | Host prefetch/cache | Yes | Local operational cache, not a source-of-truth archive |

The preservation layer must not be described as proof that every historical live-ops value was recovered. `docs/ASSETS.md` explicitly records that the archived zips contain client assets but not the original `.tab` balancing tables or unrecovered live stat-sync traffic. Reproducibility therefore requires recording APK version, extraction output, manifest revision, archive revision, and generated jar provenance separately.

## 11. Networking

The current protocol is a hybrid:

- HTTP content and login (`content_server.py`).
- Raw TCP game transport using the game's protocol and codec classes.
- AuthService HTTP for challenge/registration/verification/ticket minting.
- Optional directory HTTP/Supabase publication.
- Optional remote AdminService proxying.

Local/LAN operation is the strongest target. Public self-hosting is partially addressed: the launcher can attempt UPnP, detect CGNAT, ask the directory cloud to probe `/info`, reject private/CGNAT addresses, and require Ed25519 signatures with fresh nonces. The Supabase Edge Function verifies canonical registration data, checks timestamps, rejects unroutable addresses, probes the host from the cloud, verifies the signed `/info` response, and only then upserts the record.

This is not yet a complete Internet protocol. Remaining concerns include TLS policy for content/auth/game-adjacent traffic, NAT and asymmetric reachability, dynamic IP changes, port collision and firewall guidance, denial-of-service/rate limiting, server discovery trust and privacy, remote strict-session transport, cloud deployment automation, IPv6 completeness, and behavior when the directory is unavailable. The project instructions explicitly require further Internet networking work for both self-hosted PCs and remote/cloud servers.

### 11.1 Threat-model baseline

| Surface | Representative threat | Existing control | Explicit gap |
|---|---|---|---|
| AuthService/session | Account takeover, replay, nonce abuse | Ed25519 challenge/registration, TTL/session binding, strict user mapping | Rate limiting, abuse monitoring and operational key rotation |
| AdminService/launcher proxy | Token theft or unauthorized mutation | Required bearer token, constant-time comparison, loopback default, optional TLS context | Rotation policy, rate limiting, hardened remote TLS and least-privilege roles |
| Directory | Fake or unreachable server publication | Signed registration, nonce, timestamp, cloud reachability and `/info` probe | Abuse/quota policy, privacy and sustained availability monitoring |
| Hosting/network | Exposed ports, UPnP abuse, NAT asymmetry | Optional UPnP, CGNAT detection, explicit public endpoint fields | Firewall guidance, DoS isolation, IPv6 and failover behavior |
| Content/cache | Malicious or corrupt archive, stale cache | Manifest/cache handling, atomic replacement and archive mirror | Strong artifact integrity/provenance policy and cache trust model |
| APK/toolchain | Malicious input or compromised downloaded tool | User-supplied input, validation checks, generated local artifacts | Dependency pinning, checksums, sandboxing and release provenance |
| Local launcher API | Local process abusing daemon endpoints | Loopback binding and UI-mediated workflow | Local authorization model and OS-level process isolation |

This is a baseline for future security work, not a claim that the listed controls are sufficient for public deployment.

## 12. Persistence / Database

The server uses SQLite paths such as `server/data/dh-server.db`, with separate stats output under `game-data/stats`. Authentication persists public keys/accounts and uses session records with nonce uniqueness, TTL, signature verification, and `loginRequestID → userID` binding. The mnemonic phrase itself is not intended to be stored server-side.

The launcher stores local settings, server favorites, paths, and disclaimer state through its configuration managers. The UI's default settings include language, APK path, output directory, client directory, and bundle directory. The source/design boundary indicates that a clear-text mnemonic should not be persisted by default; any “remember me” mechanism requires careful encryption and local-password handling.

The database layer is **implemented and historically tested for core auth behavior**, but production migration, backup/restore, corruption recovery, schema evolution, concurrent operators, retention, and privacy procedures are not established by the inspection evidence.

## 13. Development & Build Workflow

The principal workflows are script-driven:

- `tools/extract_game_data.sh` extracts tables and strings from the user APK.
- `tools/decompile.sh` produces game jars through dex2jar/Maven tooling.
- Reframe tooling uses ASM and `ReframeJar` to generate valid StackMapTable frames.
- `desktop-port/run-online.sh` assembles a local server/content/client run.
- `tools/build_launcher.sh` compiles a game-free launcher jar, embeds a full JDK, downloads relocatable Python, optionally embeds PortableGit on Windows, copies tooling, bundles runtime jars, and creates launcher scripts.
- `BuildManager` orchestrates these steps in a background worker and exposes state/logs.
- Generated server/client bundles include launch scripts and a JRE strategy; server content still historically required Python unless the embedded launcher runtime is used.
- `.github/workflows/launcher-release.yml` builds Linux and Windows artifacts, prebuilds hostspine natives, smoke-checks extracted JDK/Python/executables, archives packages, and can attach them to GitHub Releases.

The scripts contain many platform-specific safeguards: relative extraction paths on Windows, real execution checks for `python` vs App Execution Alias stubs, embedded MSYS coreutils, OS-specific native classifiers, JDK symlink dereferencing, older-GLIBC CI selection, and avoidance of `bash -c` path re-parsing for Windows reframe commands.

This workflow is sophisticated but dependency-heavy. Build reproducibility still depends on upstream downloads, exact APK formats, Maven/GitHub availability, OS-specific toolchains, and CI runner behavior.

### 13.3 Clean-checkout reproduction outline

The source-supported reproduction sequence is: obtain a clean checkout; provide a compatible user APK; run `tools/extract_game_data.sh` for `game-data`; run `tools/decompile.sh` and the reframe path to prepare generated jars; use `desktop-port/run-online.sh` for the local content/server/client harness; use `tools/build_launcher.sh` with an OS-appropriate `JAVA_HOME` to assemble the game-free launcher package; or use the launcher daemon's `/build/start` endpoint to orchestrate a server/client/APK target. The UI can be built with Node 22 and the Tauri shell with Rust plus Linux WebView dependencies or Windows WebView2. Tests and probes are invoked through the repository's smoke scripts and individual `server/smoke` programs, while generated logs and captures are kept under `build/` or the paths printed by the scripts.

This is a **reproduction outline, not a claim of execution in this handover**. A new developer must pin the APK version, JDK, Node/Rust, Gradle/Maven/downloaded tool versions, OS, native backend choice, and environment variables before comparing results. On Windows, use the embedded PortableGit/MSYS path or the build package's runtime; on Linux, ensure Bash, core utilities, a compatible JDK, native graphics/runtime libraries, and X/GL or a documented headless display are available. Common recovery actions encoded in the scripts include deleting stale generated jars/caches, removing stale bundle output, stopping old `LoginServer`/`content_server.py`/client processes, checking occupied ports, and preserving logs before retrying.

### 13.1 Reverse-engineering and conversion tools

The repository includes more than the high-level build scripts. `tools/decompile.sh`, `tools/extract_game_data.sh`, `tools/gen_bip39.sh`, `tools/build_spine_jar.sh`, `tools/reframe/src/ReframeJar.java`, `PatchGdxAudio.java`, `PatchGdxCalls.java` and `StripJar.java` form the APK-to-runtime conversion path. `tools/audit/`, `tools/fetch_assets.sh`, `tools/merge_release.sh`, and the screen-contract tools support inspection, asset preparation and release assembly. Native reverse/validation support is under `native/tools/` (`disasm.py`, `np_certify.c`, parser tests) and `native/reuse/` (`ParticleV3Loader`, `NpAdapterValidate`).

These tools are not interchangeable with a generic game build: several consume a user-provided APK or generated game jar, and many produce ignored/regenerable artifacts. A clean checkout therefore needs the appropriate JDK, Node/Rust for the launcher UI, Gradle/Java dependencies, Python, shell utilities, and network access for uncached tool downloads. Windows additionally needs the embedded or build-time MSYS/PortableGit path and the platform-specific native/runtime assets.

### 13.2 CI workflows

The repository has separate automation responsibilities. `launcher-ui-ci.yml` type-checks and Vite-builds the React frontend on Ubuntu and Windows. `launcher-tauri.yml` builds the native Tauri windowed app on both OS families but documents that the executable needs `dhlauncher.jar` and `runtime/jdk` beside it. `launcher-release.yml` assembles the complete game-free package, prebuilds host Spine natives, embeds JDK/Python/tooling, runs extracted-package checks, and attaches release artifacts. `directory-keepalive.yml` pings Supabase every three days on the free plan, while the Supabase `pg_cron` migration purges directory rows older than 30 minutes. `upload_to_ia.yml` handles the repository's Internet Archive upload workflow and should be reviewed before changing preservation artifacts.

CI proves build/package invariants, not complete gameplay. The UI workflow explicitly leaves front-to-daemon E2E as a local verification requiring the Java daemon and browser; the Tauri workflow separately proves the windowed build. This distinction must remain visible in release status.

## 14. AI-Assisted Development & Tooling

The repository treats AI work as a controlled engineering process rather than an invisible code-generation shortcut. `MEMORY.md` records durable architecture and current state; `JOURNAL.md` records chronological experiments, failures, fixes, and verification; `CLAUDE.md` contains contribution rules; `.claude/settings.json` installs a compaction-resume hook.

Source comments often preserve the reason for a change, the observed failure mode, the reproduction condition, and the chosen invariant. This is especially visible in Windows launcher packaging, APK repacking, particle parsing, strict login, and bundle lifecycle code. That historical context is valuable for future agents and humans, but it must always be reconciled against current source.

Recommended AI practice is therefore: read instructions and memory first, inspect code before proposing changes, make one subsystem change at a time, add or update a probe/test, preserve diagnostic logs, and report unknowns explicitly.

### 14.1 Pilotage automatisé and “auto-pilot” tooling

The repository contains substantial **development automation**, but it should not be described as a general autonomous AI agent or as an in-game production feature. The automation is deterministic, opt-in, disabled by default, and intended for headless verification, discovery, diagnosis, and repeatable interaction with the original game APIs.

The main game-side controller is `desktop-port/src/main/java/dhdesktop/TutorialDriver.java`. It reads the tutorial pointers exposed by the game (`TutorialHelper.getPointers`), resolves the named actors in the scene graph, and injects taps through the real `DhInput` path. It is deliberately designed not to guess screen coordinates when the game identifies a target. It handles stacked modal windows, reward dialogs, crafting/equipment flows, tutorial back-navigation, hero selection, campaign entry, post-victory progression, and combat continuation. Its comments explicitly distinguish a tutorial-designated target from a fallback “tap to continue” action.

`DesktopLauncher.java` exposes the corresponding development controls through JVM properties, forwarded by `desktop-port/run-desktop.sh` and `run-online.sh`:

| Control | Purpose | Status / limitation |
|---|---|---|
| `dh.autotap=N` / `DH_AUTOTAP` | Periodically invokes the tutorial driver and uses a center tap only when no active tutorial target exists. | DEV-only, off by default; timing-sensitive. |
| `dh.autofight=1` / `DH_AUTOFIGHT` | Enables the original game's auto-attack API during combat. | Uses game behavior, not a rewritten combat AI; DEV-only. |
| `dh.autoequip=1` / `DH_AUTOEQUIP` | Detects equipable items through game logic and follows/recovers the equipment flow. | DEV-only; equipment failures are tracked and skipped. |
| `dh.gosignin=1` / `DH_GOSIGNIN` | Navigates to Sign In and claims the action when the screen is available. | DEV-only and bounded by tutorial/navigation state. |
| `dh.clickfile=<path>` / `DH_CLICKFILE` | Reads manual commands or `x,y` clicks from a file and injects them through real game input. | Manual-assistance path, not autonomous reasoning. |
| `dh.tutodrive.debug=1` / `DH_TUTODBG` | Logs screens, modal windows, targets, and driver decisions. | Diagnostic only. |
| `dh.tutorec=1` / `DH_TUTOREC` | Records periodic exhaustive tutorial state/pointers and synchronized numbered captures. | Produces evidence for debugging, not a gameplay feature. |
| `dh.mapprobe`, `dh.probeactor`, `dh.playlevel` | Probes campaign map hit-testing and enters a configurable level for investigation. | Experimental discovery controls. |
| `dh.taphold=N` | Holds an injected tap for multiple frames for controls that reject a one-frame press. | Compatibility workaround for input timing. |
| `dh.combatspike=1` | Runs the real game's headless combat measurement path. | Performance/oracle probe; can block the render thread and is not production behavior. |

The driver has important boundaries. It is not a machine-learning policy, it does not infer arbitrary UI semantics, it relies on game-exposed tutorial names and APIs, and it has known special cases for screens where pointers are absent or the scene is not represented by ordinary clickable actors. Manual `dh.clickfile` remains necessary for flows not yet covered by the deterministic driver. The source and journal describe tutorial automation as a verification instrument, not as a player-facing “autopilot”.

There is a separate launcher automation stack documented in `docs/WINDOWS_PILOTING.md`. `tools/dh-debug-launch.ps1` starts the real packaged Windows Tauri executable with WebView2 remote debugging enabled, while `tools/cdp_drive.mjs` attaches through Chrome DevTools Protocol and can list targets, inspect the DOM, click visible text or CSS elements, type into React-compatible inputs, press keys, wait for elements, capture console/errors, and take screenshots. This exercises the actual Tauri-to-daemon bridge rather than a Vite development proxy. `tools/dh-watch-daemon.ps1` observes the Windows process and collects Application Error/Windows Error Reporting context when the packaged daemon disappears.

The CDP tooling is likewise **local, opt-in, and test-only**. The debug port is not opened for an ordinary player; native Windows dialogs outside WebView2 are not covered; and `tauri-driver`/WebDriver is only documented as a heavier future alternative. These tools should be included in any future E2E test plan, but they must not be confused with an AI agent capable of independently operating the full application.

## 15. Testing & Debugging Infrastructure

The repository contains a broad testing surface, including tests referred to by source comments and historical records such as `MnemonicIdentityTest`, `AccountStoreTest`, `SessionAuthTest`, `AuthServiceTest`, `AuthFlowTest`, `HandshakeRoundTrip`, `BuildDataGenTest`, `ServerBundleTest`, `ClientBundleTest`, and `ApkBuildProbe`. There are scripts for smoke runs, strict-auth seeding, online runs, tutorial automation, screenshots, combat spikes, map probes, and Spine/particle diagnostics.

`tools/screentool/contract.sh` compiles and runs `ScreenContract` and `ModeGraph` against the game jar and server classes. It can discover the union of classes for a mode before extracting screen contracts, addressing the limitation of inspecting one class in isolation.

Particle diagnostics include Java/native lockstep, vertex diffs, render-unidbg fallback, tag-specific dumps, timing diagnostics, and path counters. These are the right kinds of tools for a port where visual similarity alone is insufficient.

The headless verification stack is explicitly layered in `docs/HEADLESS_VERIFICATION.md`: static screen contracts; wire round-trip checks; `server/smoke/ClientOracle` executing real client predicates and send validations; GL-free `HeadlessCombat`/`CombatSpikeDriver`; and finally real in-game rendering, which remains irreducible. `ClientOracleR1Test` is intended to catch client crashes such as the historical collection-star bounds issue, while `SendValidationTest` checks both legitimate acceptance and anti-cheat refusal. Headless green is not equivalent to in-game green: layout, visible actors, VFX, input targets and rendering-only crashes still require the real client.

Status: **substantial infrastructure exists; full coverage is unknown**. The next team should preserve test artifacts and record exact OS, JDK, APK, generated-artifact, and dependency versions for every result.

## 16. Deployment

There are three deployment models:

1. **Development/local:** source checkout, generated jars, local Python content service, Java server, and desktop client.
2. **Generated standalone bundles:** server or client package produced from a user APK, with scripts and runtime files.
3. **Launcher distribution:** game-free package containing `dhlauncher.jar`, embedded JDK, embedded Python, tooling, optional PortableGit, UI executable, and directory configuration.

The launcher release CI intentionally builds per operating system rather than cross-compiling the runtime. Linux uses Ubuntu 22.04 to avoid unnecessarily new GLIBC requirements; Windows uses Windows runners. The repository's distribution philosophy excludes the game and assets from the delivered software.

Cloud deployment is not equivalent to package distribution. A cloud/VPS deployment still needs provisioning, public addressing, firewall/ports, TLS, process supervision, directory configuration, backups, and operator controls. The design documents call for a guided flow and deployment script, but a complete cloud product was not found in the inspected evidence.

## 17. Known Issues / Limitations

1. **Documentation lag.** `docs/LAUNCHER.md` says UI code is not yet written and describes APK work as future, while `LauncherDaemon`, React views, `BuildManager`, and APK scripts now exist. `docs/DISTRIBUTION.md` similarly retains “future” language beside later status notes. These documents need a status refresh.
2. **No complete end-to-end proof.** The presence of smoke tests does not prove all screens, progression, combat, reconnect, persistence, and network cases.
3. **Internet incomplete.** Directory probing improves safety but does not provide a fully hardened public protocol.
4. **Remote strict play incomplete.** `PlayManager` explicitly documents that remote strict injection of `loginRequestID` needs a client hook.
5. **Particle parity incomplete.** The Java engine has a comparison mode but remains an alternate backend, not a proven replacement.
6. **APK fragility.** Patching is tied to recognizable classes/strings and Android packaging behavior; variants and future versions may fail.
7. **Runtime dependencies.** Even with embedded launcher runtimes, generated server/client builds can require downloads during generation and OS-specific assets/toolchains during development.
8. **Security review needed.** Loopback binding, token flow, pinned TLS, environment secrets, public directory signatures, admin proxying, and side-loaded APK behavior all merit a dedicated threat model.
9. **Error UX.** Several UI paths collapse detailed daemon failures into short generic messages and require the user to inspect logs.
10. **Legal/distribution boundary.** The repository's model is user-supplied APK and no redistribution of game binaries/assets. Any release process must preserve this boundary.
11. **Configuration drift.** Environment variables and generated bundle paths are numerous; a versioned configuration reference and migration strategy are needed.
12. **Unverified current checkout.** This Bible is based on reading, not running. Recorded historical test claims should be rerun before a release decision.

## 18. Remaining Work

### 18.1 Internet networking protocol

Complete the protocol for both PC self-hosting and remote/cloud hosting. Define TLS and certificate behavior, public endpoint roles, port/firewall requirements, dynamic DNS/IP refresh, IPv4/IPv6 behavior, NAT/CGNAT fallback, rate limiting, abuse isolation, reconnect semantics, and operator authentication. Finish remote strict play so the authenticated launcher session is securely bound to the client handshake. Provide a documented cloud deployment path with process supervision, secrets, backups, and monitoring.

### 18.2 Complete the Java particle port

Build a representative effect corpus and compare Java output against unidbg/native for lifecycle, timing, vertex positions, two-color values, UVs, blend modes, pages, Z behavior, flipbooks, transparency, gradients, and disposal. Resolve the `BelowZ` and multiply/Z-offset differences visible in the dispatch code. Only promote Java as default after repeatable in-game parity evidence.

**Concrete starting point (from the 2026-09-15 hub crash, see §8.1).** The first defect to fix is not a *rendering* discrepancy but an outright **effect-creation failure**: for at least one real effect set — the hub PORT glow (`world/env/mainscreen/vfx/mainscreen_port_*_glow.np`) — the Java backend produces no usable effect, so the game never gets a `ParticleEffectRenderable` component. This is a sharper and more tractable target than pixel parity, because the failure is binary and reproducible: load that specific `.np` set through `JavaParticleEngine` and determine why creation fails (parsing, atlas/region resolution, or handle registration), comparing against the unidbg path on the same files. Fixing creation is a prerequisite to any parity work, and it is also the gate for ever restoring Java as the default — which must not happen until the hub is verified in game.

### 18.3 APK reliability

Test multiple current APK/XAPK sources, ABI combinations, Android versions, signing/install paths, picker and fixed-target modes, interrupted downloads, cache reuse, and runtime login. Make tool versions and checksums explicit, improve failure messages, and define supported APK compatibility. Confirm launcher integration from file selection through generated artifact installation and first run.

### 18.4 Full end-to-end game testing

Create a matrix covering first launch, onboarding, account creation, restore, content download, tutorial, navigation, every major screen/mode, combat, persistence, reconnect, server restart, multiple clients, strict/permissive mismatch, asset cache failure, and long sessions. Capture missing screens, crashes, incorrect states, server/client mismatches, and UI defects with reproducible logs and artifacts.

### 18.5 Launcher completion and UX

Synchronize the design documents with the actual UI. Finish onboarding, account restore, secure optional local credential storage, server discovery/ping presentation, remote-host guidance, build cancellation/retry, log export, platform-specific installation guidance, and actionable errors. Automate more manual steps while keeping dangerous actions explicit. Add a clear capability matrix so users know whether they are creating a server, a desktop client, or an APK side-load.

### 18.6 Operations and maintainability

Add database migration/versioning, backup/restore procedures, directory cleanup policy, metrics, structured logs, release provenance, dependency pinning, reproducible CI artifacts, and a security response process. Establish a release checklist that reruns historical tests rather than relying on journal assertions.

## 19. Recommended Next Steps

1. Refresh `docs/LAUNCHER.md`, `docs/DISTRIBUTION.md`, and the root README against current source so historical “not implemented” statements are clearly labeled.
2. Re-run, without code changes, the existing headless/auth/bundle/launcher probes on a clean checkout and record exact results.
3. Establish a single capability/status table in the repository and update it whenever a subsystem changes.
4. Finish remote strict login integration before advertising public multiplayer.
5. Treat unidbg as the particle oracle and build a parity-driven Java backend test corpus.
6. Define supported APK versions and automate a matrix of patch/install/runtime tests.
7. Perform a complete local vertical slice from a clean user APK through generated server/client and first playable session.
8. Add an Internet staging environment only after the threat model and deployment procedure are documented.
9. Improve launcher error reporting and cancellation before broad user testing.
10. Keep all future changes source-anchored, logged, and accompanied by an explicit evidence status.

## 20. Repository Map / Quick Reference

| Path | Role |
|---|---|
| `server/java/dhserver/` | Authoritative server, auth, persistence, directory, admin, protocol glue |
| `server/content_server.py` | Manifest/assets/login/auth proxy HTTP service |
| `server/smoke/` | Headless and protocol smoke programs/scripts |
| `desktop-port/src/` | Desktop Java port and compatibility shims |
| `desktop-port/build.gradle` | LWJGL, libGDX, unidbg, Spine, audio dependencies |
| `desktop-port/run-online.sh` | Local server/content/client orchestration |
| `launcher-ui/src/` | React frontend, screens, state, API client, i18n, styling |
| `launcher-ui/src-tauri/` | Tauri desktop shell |
| `server/java/dhlauncher/LauncherDaemon.java` | Loopback launcher HTTP daemon |
| `server/java/dhlauncher/HostManager.java` | Local/bundle server lifecycle and publication |
| `server/java/dhlauncher/BuildManager.java` | APK-to-server/client/APK background build orchestration |
| `server/java/dhlauncher/PlayManager.java` | Generated client lifecycle |
| `tools/extract_game_data.sh` | APK data extraction |
| `tools/decompile.sh` | APK dex/game jar decompilation |
| `docs/ASSETS.md` | Manifest, archive.org asset mirror, extracted data and asset provenance |
| `docs/RECON.md` | APK/package/protocol reconnaissance and AssetUpdater findings |
| `game-data/stats/` | Regenerable extracted balancing/stat tables used by the server |
| `game-data/strings/` | Regenerable extracted localized `.properties` strings |
| `game/` | Committed base APK input (`disney-heroes-12.1.0.apk`) |
| `libs/` | Committed game/dependency jars plus generated or cached runtime tooling |
| `tools/reframe/` | ASM frame repair for generated jars |
| `tools/patch_apk.sh` | Fixed APK redirection and signing |
| `tools/apk_inject_picker.sh` | Directory-backed mobile picker injection |
| `mobile-build/` | APK build/injection output, including the universal picker artifact |
| `tools/screentool/` | Screen contracts and mode graph discovery |
| `tools/audit/`, `tools/fetch_assets.sh`, `tools/gen_bip39.sh` | Auditing, asset acquisition, and generated BIP39 source |
| `tools/cdp_drive.mjs`, `tools/dh-debug-launch.ps1`, `tools/dh-watch-daemon.ps1` | Real Windows launcher/CDP driving and crash observation |
| `mobile/` | Picker/auth/info mobile injection sources |
| `native/` | Native bridges, host Spine, JNI headers/build scripts |
| `native/tools/`, `native/reuse/`, `native/oracle/` | Native reverse engineering, particle certification, and oracle harnesses |
| `native/unidbg/` | ARM/bionic/JNI emulation path |
| `supabase/functions/register-server/` | Signed, externally probed directory registration |
| `supabase/migrations/` | Directory cleanup and scheduled stale-server purge |
| `tools/build_launcher.sh` | Game-free launcher package assembly |
| `.github/workflows/launcher-release.yml` | Linux/Windows CI package build and extracted-package verification |
| `.github/workflows/launcher-ui-ci.yml` | React typecheck and Vite build on Linux/Windows |
| `.github/workflows/launcher-tauri.yml` | Tauri windowed build on Linux/Windows |
| `.github/workflows/directory-keepalive.yml` | Scheduled Supabase directory keep-alive |
| `docs/PRINCIPLES.md` | Engineering principles |
| `docs/ARCHITECTURE.md` | Target architecture and boundaries |
| `docs/PROTOCOL.md` | Recovered content/game protocol |
| `docs/SHIMS.md` | Shim registry and limitations |
| `docs/HEADLESS_VERIFICATION.md` | Verification strategy |
| `MEMORY.md` | Durable historical/project state |
| `JOURNAL.md` | Detailed chronological engineering record |
| `CLAUDE.md` | Repository contribution rules |

## Closing Assessment

The repository is a substantial preservation engineering project with a real server, real protocol reuse, real authentication, real launcher orchestration, real packaging logic, and unusually rich diagnostics. It is not yet safe to summarize it as a finished revival. The correct handover position is: **local/LAN-oriented implementation with many historically verified slices, a promising but incomplete public-directory/network layer, experimental rendering parity work, fragile APK compatibility, and a launcher that has moved from design into implementation but still needs product-level validation.**

That distinction should guide every subsequent change and every public claim.


## Final Handover Checklist — Remaining Work That Must Not Be Forgotten

The Bible now covers the instruction checklist as follows: current source is primary; memory and journal are treated as historical evidence; server, client, rendering, particles, launcher, APK, networking, persistence, build, AI/automation, testing, deployment, administration and live-ops are described; repository paths and class names are cross-referenced; historical documentation discrepancies are recorded; and the status language avoids treating unknown or unexecuted behavior as working.

The remaining release-blocking work is still the following:

1. **Finish Internet hosting for both self-hosted PCs and cloud/VPS deployments**, including secure transport, remote strict sessions, firewall/NAT/DNS/TLS guidance, rate limiting, monitoring, and deployment/recovery procedures.
2. **Finish and promote the Java particle backend only after parity evidence**, retaining unidbg/native as the oracle until lifecycle, vertex, UV, color, blend, Z, flipbook and timing behavior agree in-game.
3. **Make APK generation dependable for ordinary users**, covering version compatibility, XAPK/split handling, Smali patching, repackaging, signing, installation, picker runtime, launcher integration and Android-device testing.
4. **Complete the game-system matrix**, especially post-tutorial flows and the modes explicitly still listed in `docs/HEADLESS_VERIFICATION.md` as requiring contract, logic, wire, oracle and in-game validation.
5. **Run a complete end-to-end validation from a clean checkout and user APK**, including UI, server, client, persistence, reconnect, multi-user, content cache and long-session cases.
6. **Finish launcher productization**, including coherent onboarding/account restore, actionable errors, build cancellation/retry, logs, platform installation, cloud guidance and synchronization of stale design documents.
7. **Restore and version historical content separately from backend code**, including manifest/archive provenance, strings, stat-sync limitations, event rotations, rewards, offers, shops, battle-pass variants and asset completeness.
8. **Complete social/community behavior**, including guild economy, offline delivery, global/VIP chat, social moderation, rankings and the remaining guild interactions; the current native slice is primarily guild chat and shared guild state.
9. **Maintain the capability matrix by platform and evidence type**, recording code, smoke/headless, in-game, E2E, Windows, Linux, Android and Internet status without inferring one column from another.
10. **Close operational and security gaps**, including schema migration, backups, audit/retention policy, secret handling, dependency pinning, release provenance, threat-model mitigations, operator runbooks and incident recovery.

Until these items are completed and independently rerun, the correct project status remains: **substantial implementation with historically verified local slices, but not a fully validated Internet-ready consumer release**.
