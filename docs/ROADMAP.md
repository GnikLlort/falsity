# Roadmap

Falsity is built in milestones, and each one has to be *demonstrable* before the
next starts. Nothing on this page is aspirational filler: the "done" marks are
backed by a green CI run, and everything else is either absent from the code or
listed under "not built yet".

**Rule for every milestone:** `./gradlew build` passes (which includes the
server-side game tests), and the thing being claimed can be spawned and observed.
No milestone merges without that.

---

## M1 — Foundation ✅ done

**Goal:** a real entity that exists in the world, looks like Falsity, moves and
animates, and can be befriended.

Delivered:

- `falsity:falsity`, registered with attributes, a spawn egg in the creative
  *Spawn Eggs* tab, and `/falsity summon`.
- An original 11-part, 16-box model and 64×64 texture, both generated from
  `tools/model/falsity_model.json` by `tools/generate_assets.py`, so geometry and
  art cannot drift apart.
- Custom renderer + render state + model layer; vanilla keyframe idle animation
  (breathing, ear sway, tail flick) and procedural walk, head-tracking and
  blinking.
- Server-authoritative bonding, petting, sit/follow orders, catch-up when left
  behind, persistence across saves, and a JSON config with clamped values.
- Verification: unit tests for the config and the follow arithmetic, server game
  tests covering spawn, ticking, bonding, orders and walking to the owner, and a
  green `./gradlew build` in CI.

Not in this milestone: sounds, AI conversation, memory, horror.

---

## M2 — Intelligence: Groq, memory, personality, horror foundation ✅ done

**Goal:** Falsity can hold a conversation, remembers each player, has a
consistent evolving personality, and has the first harmless step of horror
progression. (This milestone was scoped as "Milestone 2: Groq Intelligence,
Conversations, Persistent Memory and Personality" and delivers roadmap M3 + M4
plus the M5 foundation.)

Delivered:

- Server-side, asynchronous Groq client for the OpenAI-compatible
  `https://api.groq.com/openai/v1/chat/completions` endpoint (Java `HttpClient`,
  never on the server tick). Configurable model (default `openai/gpt-oss-20b`),
  timeout, temperature and token limits.
- API key from `GROQ_API_KEY`, then git-ignored `secrets.json`, then config
  (empty by default). Never logged, never sent to clients, never in errors.
- `/falsity talk <message>` with per-player cooldowns and a server-wide rate
  limit. Petting never triggers a request. Silence is preserved: Falsity only
  speaks when spoken to, plus a welcome-back line after long absences.
- Offline fallback personality: every failure path (no key, offline, timeout,
  rate limit, bad response) answers from scripted lines. Falsity never goes
  silent.
- AI output is dialogue plus at most one allowlisted suggestion
  (sit / follow / greet), applied through the entity's own methods. Unknown
  actions are rejected; the model can never run commands or touch game state.
- Persistent per-player memory (`falsity-memory/<uuid>.json`): first meeting,
  counts, facts, recent interactions, local summary, personality and horror
  state. Atomic writes, corruption-proof, capped, isolated per player. Only
  keyword-matched relevant memories enter prompts. `/falsity memory` inspects,
  `/falsity memory clear <player>` (op) wipes.
- Personality: nine traits (curiosity, affection, attachment, playfulness,
  jealousy, suspicion, loneliness, fear, hostility) starting from config
  defaults and evolving gradually per interaction, persisted per player.
  `/falsity personality` inspects.
- Horror foundation: five-stage architecture with tension→stage rules, but
  capped at Strange (stage 1). Behaviours are harmless: rare staring and
  occasionally recalling an old fact. `/falsity horror` inspects,
  `/falsity horror set <player> <0-4>` (op) tests stages. Falsity never
  becomes hostile.
- Verification: unit tests for Groq parsing/failures, AI validation, fallback,
  memory, personality, horror, cooldowns, key resolution and prompts; game
  tests for memory creation/persistence, personality evolution, horror caps,
  offline conversations and isolation. No test needs a real key or network.

Not in this milestone: sounds, networking spine, horror beyond Strange,
transformation, stalking, jumpscares.

---

## M3 — Interactive companion: natural chat, actions and building

**Goal:** remove the `/falsity talk` requirement so Falsity answers ordinary
chat, and let it actually *do* things in the world — hand over items and build
structures — under the same personality, with no second AI and no aggressive
horror.

Delivered (on the `arena/10942be9-falsity` branch):

- **Command-free natural chat.** `NaturalChatListener` hooks Fabric's
  `ServerMessageEvents.CHAT_MESSAGE`, so it fires after a message is broadcast
  and never rewrites or forwards chat. It strips the `<Player>` prefix, skips
  `/`-commands, and answers only when the message addresses Falsity by name
  (case-insensitive whole-word) or continues a warm conversation that is still
  within the configured timeout. Only the Falsity *bonded to that player* and
  within range answers; other players' chatter is never sent to Groq. A
  pending confirmation ("yes") or an active build ("stop building") is handled
  even without a name mention. Configurable timeout, max length, and range.
- **Real Groq integration, reused.** The existing `GroqClient`,
  `ConversationManager`, `PromptBuilder`, `AIResponseParser`, `ApiKeyProvider`,
  `OfflineFallback`, `MemoryManager`, `PersonalityManager` and `HorrorManager`
  are the single AI implementation. Requests are asynchronous and never block
  the tick; the reply is scheduled back onto the server thread.
- **World action system** (`com.falsity.actions`). An allowlisted
  `ActionType` enum and `ActionExecutor` route validated `ActionRequest`s to
  handlers. Unknown types and undeclared argument keys are rejected before any
  game state is touched. Companion handlers cover follow/stop, come, sit,
  stand, greet, look-at, walk-to, stay, protect, fight/stop-fight and
  threat-reporting. `ConfirmationManager` gates destructive requests behind a
  player's yes/no with a TTL.
- **Item assistance.** `ItemAssistance` resolves a player's description against
  the real `BuiltInRegistries.ITEM` — it never invents an id or substitutes a
  different item. `GiveItemHandler` enforces a configurable permission mode
  (creative / operator / owner / unrestricted / none), a per-request quantity
  cap and a cooldown, asks for confirmation above a count threshold, splits
  stacks by the real max-stack size, and reports partial fills and overflow
  honestly.
- **Building system** (`com.falsity.building`). `BuildPlanner` turns a request
  into a bounded `BuildPlan`; `BuildValidator` enforces block-count, dimension,
  distance and loaded-chunk limits, refuses dangerous blocks (command blocks,
  TNT, lava, fire, spawners…) and refuses to overwrite solid structures, and —
  when inventory-based — checks the player actually owns the materials.
  `BuildManager` places a small number of blocks per tick on the server thread,
  consumes materials, reports progress at a configured cadence, cancels
  cleanly, and reports failures honestly. Large builds are confirmed first.
- **Memory and personality** flow through the same managers as before; an
  action result that is pending or failed overrides the model's prose so the
  player is never told an action succeeded when it did not.
- **Config.** `config/falsity.json` gains `chat`, `items` and `building` blocks
  with sensible clamped defaults; existing files keep working (missing keys fall
  back to defaults). No aggressive horror is introduced this milestone.

Not in this milestone: sounds, networking spine, horror escalation,
transformation, stalking, jumpscares.

### 0.2.2 — making M3 dependable

No new feature area: the same companion, made to do what it was already
supposed to do. Each item below was a reported or found defect.

- **Falsity repeated itself.** Offline lines ignored what the player said.
  They now answer the kind of message (greeting, question, thanks, feeling) and
  never repeat the previous line; with Groq, the last few exchanges are sent
  along so replies stay on topic.
- **Groq was silent and nobody could tell why.** Failures were swallowed and
  the startup log claimed a connection it had never made. Every failed request
  is now logged with its cause and the fix; `/falsity ai status` and
  `/falsity ai test` report what is configured and what has actually been
  observed. The request for reasoning models (`openai/gpt-oss-*`) reserves room
  for thinking, which the old fixed `max_tokens` did not, and an empty reply is
  retried with more room. Keys in the wrong place or shape are found or named.
  A wrong `groq.endpoint`, a refusal on the way (403) and each kind of network
  failure are told apart from a bad key. See [GROQ.md](GROQ.md).
- **Messages were dropped during the cooldown.** They now wait their turn.
  Orders, item requests and world questions never wait and never need Groq.
- **Item requests collapsed to one item.** "A crafting table and 64 sticks"
  delivers both, in the amounts asked for. Unknown names are reported, never
  replaced; limits refuse the whole request and say which limit. A count the
  model sent as a JSON number used to be read as 1.
- **Words anywhere in a sentence triggered actions.** A command must start its
  clause; plans, feelings, stories and idioms are conversation.
- **Falsity could not say where anything is, or made it up.** Questions about
  position, biome, time, weather, structures, ores and caves are answered by
  the server from the world. The model is given those facts and no others, and
  a reply that states coordinates it was not given is discarded. Coordinates
  respect `world.shareCoordinates` and the `reducedDebugInfo` game rule.
- **Settings that did nothing or said the wrong thing.** Old config files keep
  their owner's choices while outdated defaults move forward (`configVersion`);
  `autonomy.exploration` and `autonomy.resourceAssistance` now do what they say.

Verification: 482 unit tests and 35 game tests in CI. Still unverified by any
automated run: the real Groq service accepting the request, which needs a key
(`GroqLiveTest` runs when one is provided and is reported as skipped
otherwise), and anything visual.

### 0.3.0 — a personality of its own

Falsity gets a personality that can be read, edited and reloaded, and the
horror progression gets a voice. See [PERSONALITY.md](PERSONALITY.md).

- **A personality file.** `config/falsity/personality.txt` is created with a
  default and sent with every conversation. Saving it is enough for the next
  reply to use it; `/falsity personality reload` and `/falsity reload` do it on
  demand. A missing, empty or unreadable file falls back to the built-in
  default and is left untouched.
- **A layered prompt.** Rules, personality, horror stage, relationship, memory,
  world, then the conversation and the message, each layer with its own bound
  so none crowds out another.
- **A voice for every horror stage.** The stage still comes from the horror
  system alone. Each of its five stages now has directions for how the
  personality shows, written as a change to the expression and never as a new
  character. `horror.allowUnsettlingLines`, which nothing read, now keeps the
  voice at the first stage when off. The cap stays at Strange; the three later
  voices wait for M4 to raise it.
- **Falsity knows where it stands with you.** How long you have known each
  other, how often you have talked and petted, how it feels, how long you were
  away and whether your recent words were kind or harsh are all told to the
  model, and all read off what is counted. A return after days away used to go
  unnoticed unless the first thing you did was pet it.
- **It remembers more of what you tell it about yourself**, picks up where the
  last conversation left off after a restart, and is told when you are hurt or
  hostile mobs are close.
- **It can speak first**, rarely, to its owner, with Groq only. This ends the
  rule from M2 that Falsity only speaks when spoken to; `chat.speaksFirst`
  restores it.
- **Fewer remarks taken for questions.** "I like this time of day" and "let me
  look around" are conversation, and a follow-up that calls another player by
  name is left alone.

Verification: 663 unit tests and 55 game tests in CI. Still unverified by any
automated run: how a real model follows the personality and the stage
directions, and whether the larger prompt (about 1,000 tokens of system text
per message, roughly twice the previous size) fits comfortably inside a given
Groq plan's limits.

---

## M3 — Presence and a networking spine

**Goal:** Falsity should feel alive, and the mod needs a safe way to tell
clients about it.

Planned:

- Original sound events (soft chirps, footsteps, a hum that changes with stage)
  with a volume/mute config and subtitles so the mod is playable without audio.
- Sit and walk animation states that are actually distinct, plus a look-at-player
  idle that reads as attention rather than random wandering.
- A versioned custom-payload channel (`falsity:state`) with an explicit schema,
  so client-visible state is a designed packet instead of a guessed synched-data
  id. Sending is server-side only; handlers validate size and version.
- Night-time appearance tweaks (eye glow via emissive overlay, no fullscreen
  effects).

Acceptance: with two players connected, both see the same animation state, and a
client that refuses the payload channel simply gets no animation instead of an
error.

---

## M4 — The horror director

**Goal:** five stages that escalate on their own schedule:
**Innocence → Strange → Suspicion → Fear → Nightmare.**

Planned:

- A director that tracks, per player, a stage and a tension value driven by
  time spent together, distance, night, isolation and what the player has said.
- Each stage changes behaviour, sound, animation and dialogue *policy* — not
  just text. Stage transitions are logged for debugging and configurable
  (including turning escalation off entirely). Raise the `maxStage` cap beyond
  1 when this milestone lands. The dialogue side is already in place: since
  0.3.0 every stage has its own voice in `StageDirections`, so raising the cap
  is all it takes for conversation to follow.
- Multiplayer-safe by design: the director is server-authoritative and per
  player, and a scare aimed at one player never overrides another's stage.
- A `/falsity stage` admin command for testing every stage on demand (the
  `/falsity horror set` command from M2 is the seed of this).

Acceptance: a game test walks one player through all five stages and asserts the
ordering, that a second player's stage is untouched, and that escalation can be
disabled by config.

---

## M5 — Stalking and jumpscares

**Goal:** the part players will remember, with the ability to switch it off.

Planned:

- Stalking: appearing at the edge of vision, following at a distance, vanishing
  when looked at directly — all resolved server-side and validated against line
  of sight, so it cannot be "faked" by a hacked client.
- Scares: sound cues, screen-shake-free alternatives, and a jumpscare that is
  configurable in intensity and can be disabled per player.
- Safety rules: never teleport on top of the player, never break blocks, never
  trap, never act while the player is in a menu or dead, and a cooldown so a
  scare cannot repeat in a loop.
- Accessibility: subtitles, a "no sudden sounds" mode, and a documented
  `safeMode` config.

Acceptance: game tests assert cooldowns, that scares are skipped when disabled,
that both a stalking target and an unrelated player are handled correctly, and
that nothing in the system can move a player.

---

## M6 — Transformation and polish

**Goal:** the cute-to-nightmare transformation as the payoff, and a release.

Planned:

- The second form: a real model/animation transition (not a texture swap),
  generated by the same asset pipeline, with a stage-linked trigger.
- Performance pass: no per-tick async work, no leaking futures, bounded memory
  and prompt sizes; profiled with a server and a client running together.
- Final documentation pass: README claims match reality, config reference,
  troubleshooting, and a compatibility table.
- Release: tagged version, jar published as a build artifact, changelog.

Acceptance: the transformation is observable in game, the profile shows no
server-tick regression, and the README's "what works" table has no row that a
tester cannot reproduce.

---

## Deliberately out of scope

These are not planned, and the code does not pretend otherwise:

- Copying models, textures, sounds or code from any other mod. All art and code
  here is original.
- Client-side-only "fake" behaviour that a server cannot verify.
- Sending the API key anywhere near a client, a log line or a chat message.
- Turning the player's world into a hazard: Falsity does not grief, trap, or
  damage the player's builds.
- A config key for a feature that does not exist yet.

## How changes are accepted

1. The change is on a feature branch, not `main`.
2. `./gradlew build` passes, including the game tests.
3. The thing being claimed can be spawned and seen in game (or, for client-only
   work, in `./gradlew runClient`).
4. The change is reviewed and merged by the maintainer. Nothing merges on its
   own.
