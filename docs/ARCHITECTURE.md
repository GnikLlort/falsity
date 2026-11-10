# Architecture

## Goals

1. **Separate responsibilities.** Entity logic, AI behaviour, configuration,
   rendering, conversations, memory, personality and horror direction each live
   in their own package. No god class.
2. **The server is authoritative.** Behaviour, bonding, orders, positions,
   conversations and memory are decided on the server. Clients receive synced
   entity data and entity events, and render.
3. **Client code never reaches the dedicated server.** Loom's split source sets
   (`src/main` vs `src/client`) enforce this at compile time: anything importing
   `net.minecraft.client.*` is in `src/client` and cannot be referenced from
   `src/main`.
4. **Nothing is faked.** A setting only exists if the feature behind it exists.
5. **The AI suggests, the server decides.** Groq produces dialogue and at most
   one allowlisted suggestion (sit / follow / greet). Every game action goes
   through validated Java code.

## Source sets

| Source set | Contents | Ships to |
| --- | --- | --- |
| `src/main` | Registration, entity, AI goals, config, commands, intelligence | client **and** dedicated server |
| `src/client` | Model, renderer, render state, animations, client entrypoint | client only |
| `src/test` | Unit tests (JUnit 5, Fabric Loader JUnit) | never shipped |
| `src/gametest` | Server game tests, run by a real Minecraft server | never shipped |

## Server side (`src/main/java/com/falsity`)

```
FalsityMod            entrypoint: load config, register, wire managers, log
FalsityRegistries     entity type, attributes, spawn egg, creative tab
entity/
  FalsityEntity       the mob: bond state, interaction, ticking, persistence,
                      memory hooks, horror staring
  FalsityAttributes   default attribute supplier
  FalsityGoals        goal priorities in one place
behaviour/
  CompanionBehaviour  pure follow/teleport rules (unit tested)
  FollowOwnerGoal     walks Falsity after its owner
ai/
  GroqClient          async Groq chat-completions client (stubbed transport)
  ConversationManager validates, asks Groq, falls back, records memory
  PromptBuilder       system prompt from personality + memory + horror stage
  AIResponseParser    dialogue + allowlisted action suggestion
  OfflineFallback     scripted personality lines (never empty, never throws)
  ApiKeyProvider      env > secrets.json > config, never logs the key
config/FalsityConfig  JSON config: pure serialisation + clamped values + atomic save
memory/
  PlayerMemory        one player's facts, recents, summary, personality, horror
  MemoryManager       synchronized cache, save-on-mutate, per-player isolation
  MemoryStorage       atomic JSON files under falsity-memory/<uuid>.json
  MemorySummarizer    local, deterministic summary (no API call)
personality/
  PersonalityProfile  nine traits (0-100), gradual evolution, prompt text
  PersonalityManager  facade: apply events by name, persist via memory
  EmotionalState      derived mood (never stored, never hostile)
horror/
  HorrorStage         Innocence..Nightmare (this milestone caps at Strange)
  HorrorProgression   pure tension -> stage rules (unit tested)
  HorrorManager       per-player tension/stage, persisted in memory
  HorrorEvent         significant-event notes Falsity can refer back to
command/FalsityCommands   /falsity summon|talk|memory|personality|horror
```

### Why Falsity is a `PathfinderMob`, not a `TamableAnimal`

Vanilla's `TamableAnimal` brings breeding, sitting AI, collars, and a whole
ownership model tuned for wolves and cats. Falsity needs ownership, orders and
later a relationship system of its own, so it extends `PathfinderMob` and owns
its bond logic. That also means later horror stages can change how the bond
behaves without fighting vanilla assumptions.

### Server-authoritative ownership

The owner's UUID is **server-side state only**, stored on the entity as a string
and saved with it. Nothing about the owner is synced to clients: the client
does not need it, and keeping it off the network means a damaged or unexpected
value can never break a client's copy of the entity. `getOwnerUuid()` still
parses defensively and logs if the stored value is unusable.

Sitting is the one piece of state the client genuinely needs (it changes the
pose the renderer draws). It is expressed as vanilla's `Pose.SITTING`, which the
game already syncs, instead of a custom synched-data slot — fewer moving parts,
and no hand-rolled data accessor whose numeric id could ever collide. A proper
client sync layer arrives with the horror-stage networking, where it will be a
payload with an explicit schema rather than a guessed entity-data id.

Bonding, sitting and catch-up teleports all happen inside
`FalsityEntity.interactWith` / `tick`, on the server only. Game tests drive
`interactWith` directly, which keeps the real logic under test instead of a copy
of it.

### Conversations: async, validated, always answered

`/falsity talk <message>` is the only path that can reach Groq. Right-click
petting never triggers a request. The flow is:

1. Validate on the server thread: talking enabled, message length, per-player
   cooldown (`ConversationManager.tryStartRequest`), server-wide rate limit
   (`GroqClient.GroqRateLimiter`, sliding window).
2. Build a small prompt (`PromptBuilder`): identity, personality description,
   mood, memory summary plus keyword-matched relevant memories (never the full
   history), and a subtle horror hint only at Strange or beyond.
3. Send asynchronously via `HttpClient.sendAsync`. The server tick never
   blocks. Timeouts, 429s, 5xx, malformed bodies and network errors all map
   to a `Result.Failed` and land on the offline fallback.
4. Schedule the reply back onto the server thread (`MinecraftServer.execute`),
   re-check that the player and entity still exist, then deliver dialogue as a
   chat message and apply at most one validated suggestion through the
   entity's own methods (`setSitting`, `greet`).

The API key is resolved by `ApiKeyProvider` (environment variable, then
`secrets.json`, then config) and sent only as a `Bearer` header. It is never
logged, never in chat, and never synced to clients. All Groq calls are
server-side.

### Memory: per-player files, bounded, corruption-proof

Each player has one JSON file (`<gameDir>/falsity-memory/<uuid>.json`) holding
first meeting, counts, facts, recent interactions, summary, personality and
horror state. `MemoryManager` caches them (synchronized, because async
callbacks touch the cache off the server thread) and saves on every mutation,
so a crash loses at most the interaction in flight. Writes are atomic
(temp + move). A corrupt file is discarded with a warning and treated as
"no memory yet" — Falsity forgets that player instead of crashing.

Facts and recents are capped (config `memory`); the oldest entries drop
first. `MemorySummarizer` rebuilds a short summary locally (no API call, so
summarising can never leak memory). `PlayerMemory.relevantMemories` scores
entries by keyword overlap with the player's message, so prompts carry only
useful context. Players are keyed by UUID everywhere: one player's memories
can never leak into another's.

### Personality and horror: separate from conversation

Personality (`PersonalityProfile`) is nine 0–100 traits starting from config
defaults and drifting by small deltas per event (bond, pet, conversation,
order, return, absence, stranger, startle), scaled by `evolutionRate`.
`EmotionalState` is derived on demand, never stored. Horror
(`HorrorProgression`) is pure tension→stage math: tension grows by 1 per
interaction (scaled by `progressionSpeed`), stages are thresholds, and the
configured `maxStage` (clamped to 1 in this milestone) caps everything.
`HorrorManager` persists tension/stage per player in memory. Behaviours are
harmless: staring (stop + look for a few seconds, rare, cooldown-gated) and
occasionally recalling an old fact ("I still remember..."). Nothing can
damage, trap or move the player, and hostility is tracked but never acted on.

### Ticking budget

`tick()` does almost nothing: a few countdown decrements, one distance check,
and a rare staring check gated by a 6000-tick cooldown. Pathfinding is
re-issued only every ten ticks by `FollowOwnerGoal`, and only while Falsity
is actually following. Memory and personality writes happen on interaction
(bond, pet, order, talk), never per tick. There is no per-tick search of any
kind.

## Client side (`src/client/java/com/falsity/client`)

```
FalsityClient            client entrypoint: model layer + renderer registration
model/
  FalsityModelGeometry   GENERATED geometry (do not edit by hand)
  FalsityModel           parts, and the per-frame animation
  ModEntityModelLayers   ModelLayerLocation
renderer/FalsityRenderer MobRenderer: render state creation and texture
state/FalsityRenderState LivingEntityRenderState subclass
animation/FalsityAnimations  keyframe animations + blink curve
```

### The render-state split

Since Minecraft's render-state rewrite, a renderer extracts everything it needs
into an `EntityRenderState`, and the model animates from that copy. Falsity
follows that design exactly: `FalsityRenderer.extractRenderState` copies the two
`AnimationState`s, the entity id (used as a blink phase) and the sitting flag;
`FalsityModel.setupAnim` never touches the entity.

### Animation strategy

Two techniques, chosen per motion type:

- **Vanilla keyframes** (`AnimationDefinition`, `AnimationChannel`, `Keyframe`)
  for looping idle motion and the one-shot greeting. Keyframes are easier to read
  and tune than code, and they are part of the game, so no GeckoLib-style
  dependency is needed — which matters because such libraries lag Minecraft
  releases.
- **Procedural animation** for anything that depends on the world every frame:
  walk cycle from `walkAnimationPos`/`walkAnimationSpeed`, head tracking from
  `yRot`/`xRot`, blinking from age plus entity id, and the sitting pose.

Blinking is derived, not stored: `FalsityAnimations.blinkAmount` is a pure
function of age and id, so two Falsities never blink together and nothing has to
be synced.

### Texture and geometry are generated together

`tools/model/falsity_model.json` is the design. `tools/generate_assets.py`:

1. shelf-packs every box into the 64×64 atlas (tallest first, deterministic),
2. paints the texture per face, including decals (face, eyes, muzzle, ear stitch),
3. writes `FalsityModelGeometry.java` with the matching `texOffs`,
4. writes the spawn-egg sprite and the mod icon.

Because UVs are computed once and baked into both outputs, a model change cannot
silently break its texture. CI runs `--check` and fails if either output is stale.

## Configuration

`config/falsity.json`, written atomically (temp file + move) so a crash cannot
truncate it. On load every value is clamped by `sanitise()`; a hand-edited file
cannot make Falsity teleport every tick, spam Groq, or skip past the horror
cap. A corrupt file is left untouched and defaults are used for the session, so
the user can still see and fix their mistake. See
`docs/falsity.example.json` for a commented-by-structure example.

Secrets do not live here. The Groq API key is read from the `GROQ_API_KEY`
environment variable first, then from git-ignored `secrets.json` in the game
directory (see `docs/secrets.example.json`), and only then from the empty-by-
default `groq.apiKey` field. The key is never logged, never synced, and used
only on the server side.

## Testing

| Layer | What it proves | Where |
| --- | --- | --- |
| Unit tests | Groq request/response parsing, failure mapping, timeouts, rate limits; AI action validation; offline fallback; memory creation/limits/retrieval/isolation; storage round-trip + corruption; personality evolution/clamping; horror thresholds/caps; cooldowns; key resolution; prompt bounds; config parsing/clamping | `src/test` |
| Game tests | entity registers, spawns, has attributes, ticks, bonds, obeys orders, walks to its owner; memory created on bond and persisted; personality evolves on pet; horror starts at Innocence and stays capped; offline conversation records memory; per-player isolation | `src/gametest` |
| Static asset validation | JSON validity, entrypoints exist, textures exist and match the model's UV bounds, translation keys present | `tools/validate_assets.py` |
| Preview render | the geometry and texture actually compose into the intended creature | `tools/generate_assets.py --preview` |

No test needs a real Groq key or network access: the HTTP transport is
stubbed and the game tests exercise the offline path. A manual test with a
real key is documented in [DEVELOPMENT.md](DEVELOPMENT.md).

Nothing in the test suite can prove how the model *looks* in the running client;
that requires a client with a display. See [DEVELOPMENT.md](DEVELOPMENT.md) for
what remains a manual check.
