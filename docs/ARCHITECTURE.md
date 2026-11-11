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
   one allowlisted action request. Every game action goes through validated
   Java code, and every fact about the world (a position, a distance, what was
   found) is read from the game by the server, never taken from the model.
6. **Nothing fails silently.** A request that cannot be carried out is answered
   with the reason; a Groq request that fails is logged with its cause and
   what to do about it.

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
  GroqClient          async Groq chat-completions client: request shapes,
                      retries, failure classification, log-safe diagnostics
  ConversationManager routes a message: local order, world question, Groq or
                      offline; pacing and queueing; delivery on the server thread
  ConversationPacer   per-player cooldown, one request in flight, waiting messages
  ConversationHistory the last few exchanges per player, sent along as context
  PromptBuilder       system prompt: identity, mood, memories, verified facts, rules
  AIResponseParser    dialogue + one allowlisted action request, tolerant of
                      the ways models write the tag
  ReplyComposer       what is finally said: the handler's words for facts and
                      refusals, the model's words otherwise
  OfflineFallback     scripted lines that answer what was said (never empty)
  ApiKeyProvider      env > secrets.json > config; says why a key was not usable
  AiStatusReport      the text of /falsity ai status and /falsity ai test
actions/
  ActionType          the allowlist of actions and their arguments
  ActionRequest       a validated request; ActionResult what happened
  ActionExecutor      the single gate between "asked" and "the world changed"
  IntentRecognizer    recognises orders, item requests and world questions
                      in plain chat, without Groq
  ConfirmationManager yes/no before large or destructive requests
  companion/          follow, come, sit, stand, stay, greet, protect, fight, ...
  items/              ItemRequestParser ("a crafting table and 64 sticks"),
                      ItemCatalog + ItemResolver (names to real item ids),
                      ItemPlanner (limits, confirmation, wording),
                      ItemService (permission, cooldown, delivery)
world/
  WorldQueryRecognizer  which question is being asked, clause by clause
  WorldService          reads the answer from the live world
  TerrainScanner        ore and cave scans over loaded chunks (pure, tested)
  StructureCatalog, OreCatalog   the names players use, mapped to game ids
  Directions, WorldAnswers       numbers into sentences
  FactGuard             replaces a reply that states unverified coordinates
config/FalsityConfig  JSON config: pure serialisation + clamped values +
                      versioned migration + atomic save
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
chat/                 NaturalChatListener, ChatDetector, CompanionFinder,
                      ConversationContextManager (see "Natural chat")
building/             BuildPlanner, BuildValidator, BuildManager, ...
command/FalsityCommands   /falsity summon|talk|memory|personality|horror|ai|reload
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

### Conversations: routed, paced, always answered

Two paths reach `ConversationManager.requestConversation`: ordinary player chat
that addresses Falsity (see *Natural chat* below) and the `/falsity talk`
command. Right-click petting never triggers a request. A message is routed in
this order:

1. **Something the server can do itself.** `IntentRecognizer` recognises
   orders ("follow me"), item requests and world questions. These run at once
   through `ActionExecutor`, with no cooldown and no Groq request, and the
   handler's own words are the reply. This is why orders work with no key, no
   network and inside the conversation cooldown.
2. **Conversation without Groq.** With `groq.enabled` off or no key,
   `OfflineFallback` answers immediately. It looks at what was said (a
   greeting, a question, thanks, a feeling), and never says the same line twice
   in a row.
3. **Conversation with Groq.** `ConversationPacer` allows each player one
   request per `conversation.cooldownSeconds` and one request in flight. A
   message that arrives too early is not dropped: it waits (`Outcome.QUEUED`),
   later messages are merged into it, and the queue is drained from the server
   tick. Only a wait longer than twenty seconds is refused, with the remaining
   time.

A Groq request carries a prompt built by `PromptBuilder`: identity,
personality and mood, the memory summary plus keyword-matched relevant
memories (never the full history), a line of **verified facts** that
`WorldService` has just read from the world (dimension, position when allowed,
biome, time, weather), and the rules, which come last and are never the part
that is cut when the prompt is long. `ConversationHistory` adds the last
`groq.historyTurns` exchanges so replies stay on topic.

The request is sent with `HttpClient.sendAsync`; the server tick never blocks.
The reply is scheduled back onto the server thread (`MinecraftServer.execute`),
the player and entity are re-checked, and then:

- `AIResponseParser` separates the dialogue from at most one action tag and
  validates the tag against `ActionType`;
- an accepted action runs through `ActionExecutor` like any other;
- `ReplyComposer` decides what is said. For anything factual (a world answer),
  pending (a confirmation question) or failed (a refusal), the handler's words
  replace the model's, so the player is never told something happened that did
  not;
- `FactGuard` replaces a reply that states coordinates or distances which are
  not in the verified facts, so the model cannot invent a location.

Any failure (timeout, 429, 5xx, empty or malformed body, no network) lands on
the offline line for that message, and is logged at WARN with the request's
diagnostics and a sentence of advice.

### Groq client: what is sent, what is retried, what is reported

`GroqClient` is pure Java behind a `Transport` interface, so every branch is
unit tested without a network, and its real HTTP code is tested against a
local server.

- **Reasoning models.** `openai/gpt-oss-*` models think before they answer and
  the thinking is billed against the completion budget; with a small budget
  they return an empty reply. For them the request uses
  `max_completion_tokens` = `maxTokens + reasoningHeadroomTokens`,
  `reasoning_effort` and `include_reasoning: false`. Other models get the plain
  `max_tokens` request.
- **Retries, each at most once per request.** HTTP 400 to a request with
  reasoning settings: repeat without them, and remember that for the model. An
  empty reply with `finish_reason=length`: repeat with a larger budget. A
  connection that fails immediately: repeat on a fresh connection. Timeouts and
  error statuses are never repeated.
- **Failures are classified** (`GroqClient.Failure`): `NO_KEY`, `UNAUTHORIZED`
  (401), `FORBIDDEN` (403), `MODEL_UNAVAILABLE`, `BAD_ENDPOINT`,
  `RATE_LIMITED` (with `retry-after`), `TIMEOUT`, `NETWORK_ERROR`,
  `INVALID_RESPONSE`, `HTTP_ERROR`. `AiStatusReport.adviceFor` has one sentence
  of advice for each.
- **Diagnostics** (`GroqClient.Diagnostics`) record status, duration, attempts,
  finish reason, token usage and Groq's request id. They never contain the key
  or any message text: details come only from Groq's error JSON or from a fixed
  description of the network failure, and anything resembling a key is
  redacted.
- **Status** is tracked per server session, so `/falsity ai status` can tell
  "configured" from "has actually answered".

The API key is resolved by `ApiKeyProvider` on every message (environment
variable, then `secrets.json` in the game directory or `config/`, then the
config field), so a corrected file takes effect without a restart. It is sent
only as a `Bearer` header, never logged, never in chat, and never synced to
clients. All Groq calls are server-side. See [GROQ.md](GROQ.md).

### Natural chat: command-free, mentioned or warm

`NaturalChatListener` registers Fabric's `ServerMessageEvents.CHAT_MESSAGE`,
which fires after a message is broadcast — so normal chat is never rewritten,
forwarded or blocked. Per message it: strips the `<Player>` prefix, skips
`/`-commands and over-long lines, then handles, in order:

1. a pending confirmation ("yes" / "no"), even without a name mention;
2. cancelling an active build on a negative or STOP_BUILD intent;
3. otherwise routing only when the message mentions Falsity by name
   (`ChatDetector.mentions`, case-insensitive whole word) or continues a warm
   conversation (`ConversationContextManager`, timeout-bounded).

`CompanionFinder` finds the Falsity *bonded to that player* within range, so a
player can never make an unbonded or another player's Falsity answer. Only
addressed or contextual messages reach `ConversationManager.requestConversation`
— ordinary chatter is never sent to Groq.

### Action system: allowlisted, server-authoritative

`ActionType` is the allowlist: an action is executed only if its type is
declared and only with declared argument keys (`ActionType.allowsArgs`).
`ActionExecutor` routes a validated `ActionRequest` to its `ActionHandler`,
turns any handler exception into a failure (never a crash, never a false
success), and is the single gate between "someone asked" and "the world
changed". Requests come from two places: `IntentRecognizer`, which reads the
player's own words, and `AIResponseParser`, which reads the model's reply.
`ConfirmationManager` gates large or destructive requests behind a player's
yes/no with a TTL.

**Recognising what the player said.** A command has to start its clause: "sit
down" is an order, "I think I will sit down for a bit" is not. Statements,
stories and idioms ("give me a moment", "I need a break") are conversation.
"I want to build a house" is a plan being shared, not a build order. The
tables in `IntentConversationTest` and `WorldQueryRecognizerTest` pin these.

**Reading the model's reply.** The prompt teaches one form,
`[action:{"type":"give_items","args":{"items":"1 crafting table, 64 sticks"}}]`,
but models vary it, so the parser accepts:

- the tag in any letter case or spacing, over several lines, or without its
  closing bracket; a line `ACTION: {json}`; nested JSON (the tag is found by
  matching braces, not by a pattern);
- a bare word for actions without arguments (`[action:sit]`);
- type names in any spelling (`give-item`, `giveItem`);
- `quantity`, `amount` or `number` for `count`, and `name` for `item` or
  `structure`;
- a count written as a JSON number or as text; a list of items written as a
  JSON list.

It refuses unknown types and arguments, malformed JSON, and a bare word for an
action that needs arguments. A refused tag never reaches chat: the words are
still spoken, and a warning is logged. Only the first accepted tag counts, and tags
inside a model's `<think>` block are ignored. The spoken line is tidied: one
line, no markdown, no "Falsity:" label, at most 300 characters ending on a
sentence.

### Item assistance: every item asked for, or the reason why not

A request passes through four pure, unit-tested steps before the game is
touched:

1. `ItemRequestParser` splits "a crafting table and 64 sticks" into entries
   with amounts ("a", "a stack of", "two", "64").
2. `ItemResolver` maps each name to a real item id using `ItemCatalog`
   (plurals, British and American spellings, common names such as "wood" or
   "food", material sets such as "diamond tools" or "iron armour"). A name it
   does not know stays unknown: no id is invented and nothing is substituted.
   The unit tests run it against the complete vanilla item list.
3. `ItemPlanner` applies the limits: at most `items.maxPerRequest` of one
   item, at most `items.maxTypesPerRequest` kinds, confirmation above
   `items.confirmAboveCount` in total, and a short list of items that need
   creative mode or operator rights. A request over a limit is refused as a
   whole, with the limit in the reply; unknown names are reported while the
   known ones are still delivered.
4. `ItemService` checks `items.permissionMode` and the cooldown, resolves ids
   against `BuiltInRegistries.ITEM`, splits stacks by the real maximum stack
   size, and reports what fitted and what did not when the inventory is full.

A request in which nothing resolves is not carried out at all. With Groq it
becomes conversation (with a note to the model that this is not an item it can
hand over); offline, Falsity says it does not know the item.

### World questions: read from the game, never guessed

`WorldQueryRecognizer` turns a question into a `WorldQuery`: position, biome,
dimension, time, weather, block below, spawn, surroundings, a structure, an
ore, or a cave. `WorldService` answers it from the live world and
`WorldAnswers` words the result with a direction, a distance and, when
allowed, coordinates.

- Things a player could read off their own screen are open to anyone Falsity
  answers. Searches (structures, ores, caves) reveal what the player cannot
  see, so they require `world.locatePermission` and respect
  `world.locateCooldownSeconds`.
- A structure search is the one `/locate` runs
  (`ChunkGenerator.findNearestMapStructure`), bounded by
  `world.structureSearchRadiusChunks`. A structure that only exists in another
  dimension is explained instead of searched for.
- Ore and cave scans (`TerrainScanner`) read only chunks that are already
  loaded, within `world.scanRadius`, so a question can never load or generate
  terrain.
- Coordinates are left out when `world.shareCoordinates` is off or the world's
  `reducedDebugInfo` game rule is on; the answer keeps direction and distance.
- The model is given the same verified facts and no others. If it is asked
  where something is and has no verified location, the prompt tells it to say
  it does not know, and `FactGuard` drops a reply that states numbers anyway.

### Building: planned, validated, placed gradually

`BuildPlanner` turns a request into a bounded `BuildPlan` of `BlockPlacement`s
in front of the player. `BuildValidator` is the server's safety net: it enforces
block-count, dimension and distance limits, requires loaded chunks, refuses a
forbidden block set (command blocks, TNT, lava, fire, spawners…), refuses to
overwrite solid structures, and — when inventory-based — checks the player owns
the materials. `BuildManager` places `blockPlacementsPerTick` blocks per tick on
the server thread via `setBlockAndUpdate`, consumes materials, reports progress
at a configured cadence, cancels cleanly, and reports failures honestly. Builds
larger than `confirmAboveBlocks` are confirmed first.

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

The config has these blocks: `companion`, `groq`, `conversation`, `memory`,
`personality`, `horror`, `chat`, `items`, `building`, `autonomy` and `world`.
`conversation` paces Groq requests (cooldown, requests per minute, message
length). `chat` controls natural-chat enablement, the conversation context
timeout, the mention/response range and the max message length. `items`
controls item assistance enablement, the permission mode (creative / operator /
owner / unrestricted / none), the per-item and per-request caps, the
confirmation threshold and a cooldown. `world` controls world questions: who
may search, how far, how often, and whether coordinates are shared. `building`
controls build enablement, the per-tick placement rate, block/dimension/distance
limits, the confirmation threshold, the progress cadence and whether builds
consume the player's inventory or generate materials. `autonomy` switches whole
kinds of help off. Missing keys fall back to defaults, so an existing
`falsity.json` keeps working unchanged.

`configVersion` records which defaults a file was written with. When a default
changes for a reason (the conversation cooldown went from ten seconds to
three), a file that still holds the old default is moved to the new one on
load, while a value the owner chose is kept. `/falsity reload` re-reads the file
and applies it in place; an unreadable file leaves the running settings as they
were.

Secrets do not live here. The Groq API key is read from the `GROQ_API_KEY`
environment variable first, then from git-ignored `secrets.json` in the game
directory or in `config/` (see `docs/secrets.example.json`), and only then from
the empty-by-default `groq.apiKey` field. The key is never logged, never synced,
and used only on the server side. When a key is present but unusable (a misnamed
file, placeholder text, a stray space), the reason is logged once and shown by
`/falsity ai status`.

## Testing

| Layer | What it proves | Where |
| --- | --- | --- |
| Unit tests (482) | Groq request shapes, retries, failure classification, redaction and status; the client's real HTTP code against a local server; key lookup in every place and shape; reply parsing against the ways models write tags; prompt rules and bounds; pacing, queueing and history; offline replies; intent recognition tables; item parsing, resolution against the full vanilla item list, limits and wording; world-question recognition, directions, terrain scans, answer wording and the fact guard; config parsing, clamping and migration; memory, personality, horror, chat detection, confirmations | `src/test` |
| Game tests (35) | On a real server: entity registers, spawns, ticks, bonds, obeys orders, walks to its owner; memory, personality and horror persist; multi-item requests deliver every item, unknown items are never invented, limits create nothing; position, biome, structure and ore answers match the world; hidden coordinates stay hidden; switched-off help is declined; whole conversations from chat message to prompt, reply, action and memory with only the network replaced | `src/gametest` |
| Live Groq tests (2, opt-in) | Groq accepts the default request and returns something Falsity can say. They run only when `GROQ_API_KEY` is set and are reported as skipped otherwise | `GroqLiveTest` |
| Static asset validation | JSON validity, entrypoints exist, textures exist and match the model's UV bounds, translation keys present | `tools/validate_assets.py` |
| Preview render | the geometry and texture actually compose into the intended creature | `tools/generate_assets.py --preview` |

Apart from the two opt-in live tests, no test needs a real Groq key or network
access: the HTTP transport is scripted, or pointed at a server on the same
machine, and the game tests script the network. That also marks the limit of
what the suite proves: until the live tests or `/falsity ai test` have run with
a real key, Groq's acceptance of the request is not a tested fact. See
[GROQ.md](GROQ.md).

Game test classes are registered as `fabric-gametest` entrypoints in
`src/gametest/resources/fabric.mod.json`; a class that is not listed there
compiles but is never run, so the server log's "N tests are now running"
count is the thing to check when a new test seems to be missing.

Nothing in the test suite can prove how the model *looks* in the running client;
that requires a client with a display. See [DEVELOPMENT.md](DEVELOPMENT.md) for
what remains a manual check.
