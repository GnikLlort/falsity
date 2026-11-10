# Architecture

## Goals

1. **Separate responsibilities.** Entity logic, AI behaviour, configuration,
   rendering and (later) AI conversations and horror direction each live in their
   own package. No god class.
2. **The server is authoritative.** Behaviour, bonding, orders and positions are
   decided on the server. Clients receive synced entity data and entity events,
   and render.
3. **Client code never reaches the dedicated server.** Loom's split source sets
   (`src/main` vs `src/client`) enforce this at compile time: anything importing
   `net.minecraft.client.*` is in `src/client` and cannot be referenced from
   `src/main`.
4. **Nothing is faked.** A setting only exists if the feature behind it exists.

## Source sets

| Source set | Contents | Ships to |
| --- | --- | --- |
| `src/main` | Registration, entity, AI goals, config, commands | client **and** dedicated server |
| `src/client` | Model, renderer, render state, animations, client entrypoint | client only |
| `src/test` | Unit tests (JUnit 5, Fabric Loader JUnit) | never shipped |
| `src/gametest` | Server game tests, run by a real Minecraft server | never shipped |

## Server side (`src/main/java/com/falsity`)

```
FalsityMod            entrypoint: load config, register, log
FalsityRegistries     entity type, attributes, spawn egg, creative tab
entity/
  FalsityEntity       the mob: synced state, interaction, ticking, persistence
  FalsityAttributes   default attribute supplier
  FalsityEntityData   synced data accessors (owner, sitting)
  FalsityGoals        goal priorities in one place
behaviour/
  CompanionBehaviour  pure follow/teleport rules (unit tested)
  FollowOwnerGoal     walks Falsity after its owner
config/FalsityConfig  JSON config: pure serialisation + clamped values + atomic save
command/FalsityCommands   /falsity summon
```

### Why Falsity is a `PathfinderMob`, not a `TamableAnimal`

Vanilla's `TamableAnimal` brings breeding, sitting AI, collars, and a whole
ownership model tuned for wolves and cats. Falsity needs ownership, orders and
later a relationship system of its own, so it extends `PathfinderMob` and owns
its bond logic. That also means later horror stages can change how the bond
behaves without fighting vanilla assumptions.

### Server-authoritative ownership

The owner's UUID is stored in a synced data accessor as a **string**, not as a
UUID object. A malformed value can therefore never throw while a client decodes
it; `getOwnerUuid()` parses defensively and logs once if the value is unusable.

Bonding, sitting and catch-up teleports all happen inside
`FalsityEntity.interactWith` / `tick`, on the server only. Game tests drive
`interactWith` directly, which keeps the real logic under test instead of a copy
of it.

### Ticking budget

`tick()` does almost nothing: two countdown decrements and one distance check.
Pathfinding is re-issued only every ten ticks by `FollowOwnerGoal`, and only
while Falsity is actually following. There is no per-tick search of any kind.

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
cannot make Falsity teleport every tick or walk at absurd speed. A corrupt file
is left untouched and defaults are used for the session, so the user can still
see and fix their mistake.

Secrets do not live here. The Groq API key (Milestone 3) will be read from a
separate, git-ignored file, never logged, never synced, and used only on the
server side.

## Testing

| Layer | What it proves | Where |
| --- | --- | --- |
| Unit tests | config parsing/clamping; follow/teleport rules | `src/test` |
| Game tests | entity registers, spawns, has attributes, ticks, bonds, obeys orders, walks to its owner | `src/gametest` |
| Static asset validation | JSON validity, entrypoints exist, textures exist and match the model's UV bounds, translation keys present | `tools/validate_assets.py` |
| Preview render | the geometry and texture actually compose into the intended creature | `tools/generate_assets.py --preview` |

Nothing in the test suite can prove how the model *looks* in the running client;
that requires a client with a display. See [DEVELOPMENT.md](DEVELOPMENT.md) for
what remains a manual check.
