# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ⚠️ FIRST, EVERY SESSION — reprise procedure (non-negotiable, user-mandated)

Before writing any code or running any tool — **especially after context compression** — reconstruct
state by READING, never guessing. Read in full: `MEMORY.md` (recovery doc, top entries = current
state), `git log --oneline -25`, the latest `JOURNAL.md` entries, `docs/SHIMS.md`, `docs/PRINCIPLES.md`,
`docs/PROTOCOL.md`, `docs/SERVER_PLAN.md`, `docs/ARCHITECTURE.md`, `docs/SCREEN_PIPELINE.md`,
`docs/HEADLESS_VERIFICATION.md`, and the **doc of the mode currently in progress** (see MEMORY's latest
entry — e.g. `docs/EXPEDITION.md`). Then enumerate the §1-§8 rules + tips/commands, and take stock.
Any compression handoff MUST tell the successor to apply this procedure first (explicit user requirement).

## The mission

Private, fully-authoritative server for *Disney Heroes: Battle Mode* (v12.1.0 APK). **Reuse the game's
own classes/data — never reimplement rules.** The server loads the game's `.tab` data + executes the
game's logic classes; the desktop client is the real game running on a hand-written platform backend.
Every mode must be verified **IN-GAME** (real client → our server → persistence → display), not just headless.

## Non-negotiable rules (`docs/PRINCIPLES.md` §1-§8)

1. **Minimal game mods** — only the platform layer + non-semantic bytecode normalization. Never patch game logic.
2. **No rustine (band-aid)** — a shim is either REAL or explicitly noted PARTIAL/NO-OP with its risk (`docs/SHIMS.md`). Fix causes, not symptoms.
3. **Server is authoritative — it reads & EXECUTES game code+data** (codec, `MessageFactory`, `*Helper`/`*Stats`). Write only glue (wire round-trip orchestration), never the rule.
4. **Never invent a value/rule** — extract from `.tab` (via `tools/extract_game_data.sh`) or bytecode. `game.jar` classes are used as-is.
   - §4bis: fidelity verified against the original game (screenshots, bytecode).
5. Multi-server from the start. 6. Complete & faithful persistence. 7. Reproducibility (heavy artifacts are script-regenerated & gitignored).
   **The model identifier NEVER appears in a commit/PR/artifact — chat only.**
8. **IN-GAME verification is MANDATORY.** A handler proven headless (🟢) is NOT done until confirmed in-game (✅). Work on FACTS (test/log/capture/bytecode), never unverified plausible explanations.

## Build / test / run

Server + smoke tests use the **reframed** jar `libs/game-framed.jar` (dex2jar bytecode rewritten with valid
StackMapTable via `tools/reframe/`, so `-Xverify:none` is not needed). Common classpath:
`libs/game-framed.jar:libs/commons-logging.jar:libs/sqlite-jdbc.jar:libs/slf4j-api.jar:libs/joda-time.jar`.

```bash
# Regenerate artifacts (gitignored, copyright): decompiled jar + extracted data
tools/decompile.sh game/disney-heroes-12.1.0.apk        # → libs/game.jar (dex2jar)
tools/extract_game_data.sh game/disney-heroes-12.1.0.apk # → game-data/stats/*.tab (+ strings)

# Full server regression (compiles server/java + all smoke tests, runs the assertive suite)
server/smoke/regression.sh                               # ~100+ self-contained tests

# Run ONE smoke test manually (set -Ddh.stats so game data resolves):
export JAVA_TOOL_OPTIONS=; FRAMED=libs/game-framed.jar
CPF="$FRAMED:libs/commons-logging.jar:libs/sqlite-jdbc.jar:libs/slf4j-api.jar:libs/joda-time.jar"
OUT=$(mktemp -d); javac -cp "$CPF:server/smoke" -d "$OUT" $(find server/java -name '*.java') server/smoke/<Test>.java
java -cp "$CPF:$OUT" -Ddh.stats=game-data/stats <Test>

# Launch the FULL STACK for in-game verification (content+login :8080, game TCP :8081, real client):
cd desktop-port && ./run-online.sh          # builds client via gradle, reframes, Xvfb + unidbg spine
```
`server/smoke/` holds BOTH the assertive regression tests (in `regression.sh`'s `TESTS=(...)` array —
no-arg, self-contained) AND parameterized DEV tools/probes (excluded from regression; they take args).
Add every new assertive test to `regression.sh`.

### In-game piloting (launch env vars + method)
`run-online.sh` reads env: `DH_CLICKFILE` (a file whose lines are consumed & truncated each frame >90 as
pilot commands), `DH_TIMEOUT`, `DH_FRAMES=0` (run until timeout), `DH_SHOT`/`DH_SHOTEVERY` (periodic PPM
screenshots, default `desktop-port/build/manual.ppm`), `DH_KILL_OLD=1`. Drive by appending commands to the
clickfile: `nav <DESTINATION>`, `fire x,y` (bottom-left origin), plus mode pilots in
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`. Logs: `/tmp/dh_game.log`
(server), `/tmp/dh_run.log` (client) — always `grep -a` (binary-ish). Convert PPM→PNG to view.

**Pilot pattern (B-bis):** for flaky UI, add a pilot that calls the client's REAL API
(`ClientActionHelper`/`ClientExpeditionHelper`/screen methods via reflection), same path as the UI button —
never guess coordinates for critical actions.

### ⚠️ Critical shell constraint
Foreground `sleep` is BLOCKED (exit 144 / SIGSTKFLT). To wait: use `run_in_background`, or a bash
busy-loop `until grep -q MARKER log` / `until [ mtime advanced ]` bounded by `$SECONDS`. `pkill`/`kill`
often exit 144 but still execute; the client runs under `timeout 600 java …` — killing the wrapper leaves
the child java, so kill child PIDs too.

## Architecture (big picture)

- **`libs/game.jar`** = the decompiled APK (`com.perblue.heroes.*` + libGDX), used **as-is**. Not committed.
  **`libs/game-framed.jar`** = same, reframed (valid frames). Client side has `libs/game-logic-framed.jar`.
- **`server/java/dhserver/`** = the authoritative server. `ServerContext` installs the game's stat opener
  (`StatFileHelper.setExt` → reads `game-data/stats/*.tab`) and a **headless `GameMain` shim** at `DH.app`
  (allocated without ctor; many game classes route through `DH.app`). `LoginServer` reuses the game's NIO
  server + codec + `MessageFactory`; handlers rebuild game `User`/`IndividualUser` from wire via
  `ClientNetworkStateConverter`, execute game logic on them, then re-serialize. `UserStore` = SQLite persistence.
  - **Write-through vs resync**: most setters write straight into the wire `extra` object the server passed in
    (auto-persisted). Fields kept off `extra` (heroes, `UserFlag` counts, diamonds, campaign statuses, friendships,
    missions, team level, name) need explicit `resync*` methods (closed set, validated by wire round-trip).
  - **Server-authoritative blobs**: state with no client-side builder (Arena ladder, Surge, Challenges,
    Expedition run) is generated server-side and stored as per-user/guild wire BLOB columns.
- **`desktop-port/`** = the hand-written platform backend (`dhbackend/`: Application/GL/Input/Files/Audio/Net,
  mirror of DragonSoul's `dsbackend/`) + the client launcher/driver (`dhdesktop/`). Spine/particles run the
  ORIGINAL ARM native in-process via **unidbg** (`native/`), no reimplementation. libGDX runs on LWJGL3 + Mesa.
- **`server/content_server.py`** = the HTTP content/login endpoint (`/login` → game-server address; serves
  `index.txt`-referenced content) so the real client's `ServerType.LIVE` reaches our stack.
- **`tools/screentool/`** = the industrialized per-mode pipeline (#73/#74): `contract.sh --mode <seed>`
  (`ModeGraph` discovers a mode's message classes → `ScreenContract`), `ModeGraph --logic` (lists static
  `*Helper`/`*Stats` IUser methods = server entry points to execute), and the headless verification stack
  `WireCheck` (wire round-trip, catches wrong-typed List/Map fields) + `ClientOracle` (runs the client's
  send-validation/render code headless). In-game (level 4) is the last, always-required net.

## Client-authoritative combat

Combat (campaign/friendship/expedition/surge) is played on the CLIENT, which sends the outcome
fire-and-forget; the server **re-runs the authoritative logic** (`recordOutcome`/`doRaid`/…) to advance
progression + credit rewards. Item loot is client-reported (PARTIAL §4bis/#25 — server rolls its own but
falls back to client loot rather than falsely reject on RNG divergence).

## Git

Work on the designated feature branch (see MEMORY / session config; currently
`claude/disney-heroes-port-rhhtuj`). Commit/push regularly (container is ephemeral). Commit messages end with a
`Co-Authored-By:` + `Claude-Session:` trailer; **never** put the model identifier in commits/PRs/artifacts.
Keep `MEMORY.md` (short history + current state) and `JOURNAL.md` (detailed log) updated as you go.
