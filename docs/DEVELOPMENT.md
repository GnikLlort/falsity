# Developing Falsity

Everything you need to build, test and change this mod — including the parts
that are easy to get wrong on 26.3.

## What you need

| Tool | Version | Why |
| --- | --- | --- |
| JDK | **25** | Minecraft 26.3 and Fabric Loom 1.19 both target Java 25. The build sets `options.release = 25`. |
| Gradle | **9.8.2** | Pulled by the wrapper. Never install it separately; run `./gradlew`. |
| Python | 3.9+ with **Pillow** | Only for the asset tools in `tools/`. The game and the build do not need Python at runtime. |

No other SDKs, mod loaders or decompilers are required.

## First build

```bash
./gradlew build
```

That single command

1. compiles the common source set (`src/main/java`),
2. compiles the client source set (`src/client/java`),
3. runs the JUnit tests (`src/test/java`),
4. compiles and runs the server-side game tests (`src/gametest/java`) in a real
   headless Minecraft server, then
5. writes the jar to `build/libs/`.

If a game test fails, the build fails: Loom wires `runGameTest` into `build`.
That is deliberate — a mod whose entity cannot spawn is not a working mod.

## The commands, one at a time

| Command | What it does |
| --- | --- |
| `./gradlew compileJava` | Compiles the common (server + shared) sources. Fastest syntax/API check. |
| `./gradlew compileClientJava` | Compiles the client sources against the client-only Minecraft classes. |
| `./gradlew test` | Runs the plain JUnit tests: config, Groq parsing/failures, AI validation, fallback, memory, personality, horror, cooldowns, key resolution, prompts. |
| `./gradlew runGameTest` | Runs the game tests in a headless dedicated server. |
| `./gradlew build` | Everything above, plus the jar. |
| `./gradlew runClient` | Development client, with the mod loaded. |
| `./gradlew runServer` | Development dedicated server. |
| `python3 scripts/report_test_failures.py build/test-results/test` | Names the failing unit tests from the JUnit XML reports. |

`./gradlew tasks` lists the rest.

## Source sets

Loom's `splitEnvironmentSourceSets()` is enabled, so client-only code cannot be
loaded on a server and cannot accidentally reference server-only types:

| Source set | Path | Runs on |
| --- | --- | --- |
| common | `src/main/java` | both sides |
| client | `src/client/java` | client only |
| test | `src/test/java` | plain JVM, no game |
| gametest | `src/gametest/java` | headless server, part of `build` |

The gametest source set is created by this block in `build.gradle`:

```groovy
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "falsity-gametest"
        eula = true
    }
}
```

Everything a game test needs is registered in
`src/main/java/com/falsity/FalsityRegistries` and initialised by the normal
mod entrypoint, which the test server loads like any other server.

**A new game test class must also be registered.** The test classes are
listed as entrypoints of the `falsity-gametest` mod in
`src/gametest/resources/fabric.mod.json`:

```json
"entrypoints": {
    "fabric-gametest": [
        "com.falsity.gametest.FalsityGameTest",
        "com.falsity.gametest.FalsityIntelligenceGameTest"
    ]
}
```

Without that line the class compiles, the build stays green, and the tests
are silently never run. The server log prints how many tests it ran
("11 tests are now running"); if that number is lower than you expect, the
class is not registered.

## The asset pipeline

The model geometry and the texture are generated, never hand-edited. One design
file is the single source of truth:

```
tools/model/falsity_model.json          design: parts, boxes, colours, UV budget
        │
        ├── tools/generate_assets.py    → FalsityModelGeometry.java
        │                               → textures/entity/falsity/falsity.png
        │                               → textures/item/falsity_spawn_egg.png
        │                               → assets/falsity/icon.png
        └── (--preview)                 → docs/images/falsity_model_preview.png
```

```bash
python3 -m pip install pillow          # once, e.g. in a virtualenv
python3 tools/generate_assets.py       # regenerate everything
python3 tools/generate_assets.py --check   # fail if anything is stale (CI runs this)
python3 tools/generate_assets.py --preview # also re-render the preview sheet
python3 tools/validate_assets.py       # static resource checks (CI runs this)
```

Because the same tool computes the UV layout, paints the texture and writes the
Java geometry, the model and its art cannot drift apart. `--check` makes the
generated files reproducible: if you edit a generated file by hand, CI fails
until you either regenerate or change the design file.

If you change the model, always look at the preview before committing. It is a
small z-buffered rasteriser written for exactly this purpose, and it is QA
tooling — it ships with the repo, not with the mod.

## Configuration

Falsity reads `config/falsity.json` from the game directory (server-side). The
file is created with defaults on first launch. `FalsityConfig` is a plain data
holder:

- `fromJson(String)` / `toJson()` are pure and unit tested,
- `sanitise()` clamps every value into a usable range, so a hand-edited file can
  never produce a mob that follows at 900 blocks per tick,
- `load(Path)` / `save(Path)` are the only methods that touch disk, and saving is
  atomic (temp file + move).

Rules for adding settings:

1. Only add a setting when the behaviour it controls exists. A config key that
   does nothing is a lie in a config file.
2. Clamp it in `sanitise()` and cover the clamp with a unit test.
3. Never put secrets in this file. See below.

## Secrets and Groq setup

The Groq API key is resolved by `ApiKeyProvider` in this order (first
non-empty wins):

1. the `GROQ_API_KEY` environment variable (recommended for servers),
2. `secrets.json` in the game directory — copy `docs/secrets.example.json`,
   replace the placeholder, and keep it out of git (`.gitignore` already
   excludes `secrets.json`),
3. the `groq.apiKey` field in `config/falsity.json` (ships empty).

The key is never logged, never written back to disk by the mod, never sent to
a client, and never included in error messages. All API calls happen
server-side and off the game threads (`HttpClient.sendAsync`); replies are
scheduled back onto the server thread before any game state is touched.

**How to get a key:** create a free account at <https://console.groq.com>,
open *API Keys*, and create a key. The default model is `openai/gpt-oss-20b`;
Groq retires models without much notice, so if replies stop arriving, check
the console for the current model list and update `groq.model` in
`config/falsity.json`. A full annotated example is in
`docs/falsity.example.json`.

**Manual test with a real key (never automated):**

1. Set the key via one of the three methods above, then start the server.
2. Summon and bond Falsity, stand within 16 blocks, and run
   `/falsity talk Hello, Falsity!`. A reply should arrive within a few
   seconds, prefixed with `<Falsity>`.
3. Run `/falsity talk remember my favourite block is diamonds`, then talk
   again about diamonds: Falsity should refer back to it.
4. Run `/falsity memory` and `/falsity personality` to inspect what it kept.

**Testing offline fallback:** set `groq.enabled` to `false` in
`config/falsity.json` (or leave the key empty) and repeat step 2. Falsity
answers from scripted lines with no network involved. To test failure paths
without a real key, the unit tests stub the HTTP transport
(`GroqClientTest`): timeouts, 429s, 5xx, malformed bodies and missing keys
all map to the fallback.

## How CI verifies the mod

`.github/workflows/build.yml` runs on every push:

```
compileJava → compileClientJava → test → build (which runs the game tests)
```

Each stage is its own step with `set -o pipefail`, so a failing Gradle task
cannot be hidden by the `tee` that writes the log. The log is also posted back
as a **commit comment**, so you can read the exact compiler or test output
without downloading an artifact:

```bash
gh api repos/<owner>/<repo>/commits/$(git rev-parse HEAD)/comments --jq '.[].body'
```

What a green run proves:

- both source sets compile against real 26.3 classes,
- the pure logic tests pass (Groq parsing/failures, AI validation, fallback,
  memory, personality, horror, cooldowns, key resolution, prompts, config),
- a real dedicated server loads the mod, spawns Falsity, ticks it, bonds it to a
  player, obeys sit/follow, measures it walking towards its owner, creates and
  persists memory, evolves personality, caps horror progression, and answers
  conversations offline.

When a unit test fails, CI names it. `scripts/report_test_failures.py` reads
the JUnit XML reports and prints the failing class, method and message, and
that output is included in the commit comment alongside the counts. A build
that says "81 tests completed, 2 failed" is not a useful report; "FAILED
com.falsity.memory.PlayerMemoryTest.daysSinceSeenCountsCorrectly" is.

What it does **not** prove: how the mob looks. Rendering needs a client, so the
visual check is `./gradlew runClient` plus the generated preview, and the
`docs/images/falsity_model_preview.png` sheet in the README.

## 26.3 notes that will save you an hour

These are the API facts this project had to discover against the compiled game,
and they are what the code relies on:

- **Identifier** — `Identifier.fromNamespaceAndPath(ns, path)`. There is no
  `new Identifier(...)` / `ResourceLocation` constructor left.
- **Positioning** — `Entity#moveTo` no longer exists. Use `snapTo(Vec3)`,
  `snapTo(x, y, z, yRot, xRot)`, or `teleportTo(x, y, z)`; `setPos` only moves
  the entity's position without `yRot`/`xRot`.
- **Goal selectors are protected** — a helper class cannot add goals to a `Mob`.
  `FalsityGoals.addGoals(entity, selector)` is called from the entity's own
  `registerGoals()`.
- **Do not invent synched-data ids.** In 26.3 the slot range available to an
  entity subclass is not free for the taking: hand-rolled accessors failed at
  spawn with `Data value id is too big with 16! (Max is 15)`. Falsity therefore
  syncs state that clients need through mechanisms the game already syncs —
  `Pose.SITTING` for sitting — and keeps everything else server-side until the
  horror-stage networking adds a payload with an explicit schema.
- **Saving** — `ValueOutput#putString/putBoolean/putInt` and
  `ValueInput#getStringOr/getBooleanOr/getIntOr`. The old `CompoundTag` methods
  on entities are gone.
- **Rendering** — `MobRenderer<E, S>` with `extractRenderState`; the model never
  touches the entity. `LayerDefinition.create(mesh, 64, 64)`, and model layers are
  registered with `ModelLayerRegistry.registerModelLayer`.
- **Creative tabs** — `CreativeModeTabEvents.modifyOutputEvent`, not
  `ItemGroupEvents`.
- **Commands** — permission gates use `source.permissions().hasPermission(
  Permissions.COMMANDS_MODERATOR)`.
- **Command argument types** — `StringArgumentType` and `IntegerArgumentType`
  are no longer in `net.minecraft.commands.arguments`; use Brigadier's
  (`com.mojang.brigadier.arguments`), which is what vanilla 26.3 itself does.
  `EntityArgument` is still Minecraft's own class. `MessageArgument` replaced
  the old string argument for chat messages and caps input at 256 characters.
- **Finding entities** — `Level.getEntitiesOfClass` is gone. Use
  `getEntities(except, aabb, predicate)` and cast the first argument to
  `Entity`, otherwise the `null` also matches the `EntityTypeTest` overload and
  the call is ambiguous.
- **Game test helper** — `makeMockPlayer(GameType)` players are *not* registered
  with the level, so `level.getPlayerByUUID` cannot find them. Anything that
  resolves an owner through the level must be able to work from a cached player
  reference for a test to be able to drive it (that is why `FalsityEntity#getOwner`
  caches the player it bonded with).

## Adding behaviour

1. Put movement AI in `src/main/java/com/falsity/behaviour` as a `Goal`, and keep
   the thresholds in `CompanionBehaviour` (pure, unit tested). Put dialogue,
   memory, personality and horror logic in their own packages (`ai`,
   `memory`, `personality`, `horror`) as pure classes with stubbed seams
   (transport, clock, random) so they stay unit testable without a game.
2. Register goals in `FalsityGoals.addGoals` with an explicit priority and
   `EnumSet` of flags.
3. If clients need to know about it, prefer an existing synced mechanism; if
   there is none, add a payload in the networking package rather than a
   guessed synched-data id.
4. Add a unit test for the pure logic and a game test that drives the real
   method, not a copy of the logic. A new game test **class** also has to be
   added to the `fabric-gametest` entrypoints in
   `src/gametest/resources/fabric.mod.json`, or it compiles and never runs.
5. Update `docs/ARCHITECTURE.md` if the structure changed, and
   `docs/ROADMAP.md` if a milestone moved.

## Known limitations (Milestone 2)

- Groq models are retired without much notice. If conversations stop working
  but offline lines still arrive, check the model id in the Groq console.
- Memory is per-server-installation (`<gameDir>/falsity-memory/`), not
  per-world-save: on an integrated server shared across worlds, a player's
  memory follows them between worlds. On a dedicated server (one world per
  install) this is equivalent to per-world.
- Fact extraction is simple pattern matching (`my name is...`, `i like...`,
  `remember...`). It is not NLP and will miss phrasings it does not know.
- Horror is capped at Strange: staring and odd recollections only. The
  director, stalking and transformation are later milestones.
- No sounds yet: Falsity is silent apart from chat lines.

## Style

- Tabs for indentation, matching the rest of the codebase.
- Javadoc explains *why*, not *what*: the code already says what.
- No TODO comments in place of features. If something is not implemented, it is
  absent and listed in the roadmap.
- No third-party model, texture, sound or code copied from another mod.
