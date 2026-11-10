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

## M2 — Presence and a networking spine

**Goal:** Falsity should feel alive before it can *think*, and the mod needs a
safe way to tell clients about it.

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

## M3 — Groq conversation engine

**Goal:** Falsity can hold a short, in-character conversation.

Planned:

- Server-side, asynchronous client for the OpenAI-compatible
  `https://api.groq.com/openai/v1/chat/completions` endpoint, using Java's HTTP
  client. Never on the server tick thread, never on the render thread.
- API key read from `secrets.json` in the game directory (git-ignored). Never
  logged, never in `config/falsity.json`, never sent to a client, never in an
  error message.
- Prompt building with a hard token budget, a per-player rate limit, request
  timeouts and cancellation when the player logs off.
- Graceful degradation: if the key is missing, the request fails or the service
  is unreachable, Falsity answers from a small offline set of lines instead of
  breaking the game. The failure is logged once, without the key.
- Chat delivered as in-game messages, with a config switch to turn talking off.

Acceptance: a scripted game test stubs the HTTP layer (no network in CI) and
proves the request shape, the timeout path, the rate limit and the offline
fallback. A manual test with a real key is documented in
`docs/DEVELOPMENT.md` but never automated.

---

## M4 — Persistent memory

**Goal:** Falsity remembers the player between sessions — and only what it needs.

Planned:

- Per-player, per-world memory storage (name, first meeting, notable events,
  last conversation topics), saved through the world's data storage.
- A summarisation step with a size cap so a long-lived world cannot grow without
  bound; old trivia decays, important events stay.
- Memory is server-side only and never leaves the machine except as part of a
  prompt to the configured API.
- A `/falsity memory` admin command to inspect and clear a player's memory.

Acceptance: reconnect after a restart and Falsity refers to something from the
previous session; a corrupt memory file is discarded with a warning instead of
crashing the server.

---

## M5 — The horror director

**Goal:** five stages that escalate on their own schedule:
**Innocence → Strange → Suspicion → Fear → Nightmare.**

Planned:

- A director that tracks, per player, a stage and a tension value driven by
  time spent together, distance, night, isolation and what the player has said.
- Each stage changes behaviour, sound, animation and dialogue *policy* — not
  just text. Stage transitions are logged for debugging and configurable
  (including turning escalation off entirely).
- Multiplayer-safe by design: the director is server-authoritative and per
  player, and a scare aimed at one player never overrides another's stage.
- A `/falsity stage` admin command for testing every stage on demand.

Acceptance: a game test walks one player through all five stages and asserts the
ordering, that a second player's stage is untouched, and that escalation can be
disabled by config.

---

## M6 — Stalking and jumpscares

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

## M7 — Transformation and polish

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
