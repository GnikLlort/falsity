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
  1 when this milestone lands.
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
