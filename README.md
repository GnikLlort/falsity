# Falsity

An original AI horror companion for **Minecraft Java 26.3**, built with **Fabric**.

Falsity begins as a small, harmless-looking companion: a plush creature with a
teal beanie, oversized glossy eyes and long drooping ears. It wanders, watches
you, and bonds with the first player who reaches out to it. What happens after
that is the rest of this project.

> **Status: Milestone 2 (intelligence).** Falsity now talks (Groq AI with an
> offline fallback), remembers each player across sessions, has a persistent
> evolving personality, and has the first, harmless step of its horror
> progression. It is still cute. It is still harmless. That is the point.

![Falsity, first form](docs/images/falsity_model_preview.png)

---

## What works today

| Feature | State |
| --- | --- |
| Fabric 26.3 mod loads | Yes — verified by the game-test server in CI |
| Original entity `falsity:falsity` | Yes |
| Original 3D model (11 parts, 16 boxes) | Yes — generated from a design file |
| Original texture (64×64, hand-painted by tooling) | Yes |
| Custom renderer, render state, model layer | Yes |
| Idle animation (breathing, ear sway, tail flick) | Yes — vanilla keyframe animation |
| Walk animation, head tracking, blinking | Yes — procedural, driven by the render state |
| Spawning | Spawn egg (creative *Spawn Eggs* tab) and `/falsity summon` |
| Bonding, petting, sit/follow orders | Yes — server-authoritative |
| Groq conversations (`/falsity talk`) | Yes — async, server-side, with cooldowns and rate limits |
| Offline fallback personality | Yes — Falsity always answers, even with no key or no network |
| Persistent per-player memory | Yes — `falsity-memory/<uuid>.json`, isolated per player |
| Evolving personality (9 traits) | Yes — persists per player, drifts with interactions |
| Horror progression (Innocence → Strange) | Yes — architecture + staring + unsettling lines; capped, harmless |
| Configuration file | Yes — `config/falsity.json` (see `docs/falsity.example.json`) |
| Transformation, stalking, jumpscares | **Not implemented** — see [ROADMAP](docs/ROADMAP.md) |

---

## Requirements

- **Java 25** to build and run. Minecraft 26.3 requires it, and so does Fabric
  Loom 1.19.
- **Gradle 9.8.2** — supplied by the wrapper, no local install needed.
- **Minecraft 26.3** with **Fabric Loader 0.20.1** and **Fabric API 0.165.2+26.3**
  to play the built jar.
- **A Groq API key (optional).** Without one, Falsity uses its offline lines.
  See [Groq setup](#groq-setup-optional) below.

All versions are declared in [`gradle.properties`](gradle.properties) and were
checked against <https://fabricmc.net/develop> on 2026-11-10.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. The same task compiles both source sets, runs the
unit tests and runs the server-side game tests: a real headless server loads the
mod, spawns Falsity, ticks it, bonds it to a player, exercises the sit/follow
orders, measures it walking towards its owner, and verifies memory, personality,
horror and offline conversations. If any of that fails, the build fails.

How the verification is reported, and what it does and does not prove, is
described in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Running it

```bash
./gradlew runClient   # development client
./gradlew runServer   # development server
```

In game, either

- take the **Falsity Spawn Egg** from the *Spawn Eggs* creative tab, or
- run `/falsity summon` (permission level 2), or
- run `/summon falsity:falsity`.

Then:

- **Right-click Falsity** — the first player to do so bonds with it.
- **Shift + right-click** — tell it to stay, or to follow again.
- **Right-click while bonded** — pet it; it heals slightly and plays its greeting.
- **`/falsity talk <message>`** — talk to Falsity (must be within 16 blocks).
  Replies come from Groq when configured, otherwise from offline lines.
- **`/falsity memory`** — see what Falsity remembers about you.
- **`/falsity personality`** — see Falsity's personality towards you.
- **`/falsity horror`** — see Falsity's current stage towards you.
- **`/falsity memory clear <player>`** (op) — wipe a player's memory.
- **`/falsity horror set <player> <0-4>`** (op) — set a horror stage (testing).

Try saying `remember my favourite block is diamonds`, then talk again later:
Falsity will refer back to it.

## Groq setup (optional)

Falsity talks through [Groq](https://console.groq.com)'s OpenAI-compatible
API. It works without a key (offline lines), but with one it holds real
conversations.

1. Create a free API key at <https://console.groq.com>.
2. Put it in **one** of these places (first match wins):
   - the `GROQ_API_KEY` environment variable (recommended for servers), or
   - `secrets.json` in the game directory — copy `docs/secrets.example.json`
     and replace the placeholder (this file is git-ignored; never commit it), or
   - the `groq.apiKey` field in `config/falsity.json` (ships empty; do not
     commit a real key).
3. Optionally change `groq.model` in `config/falsity.json`. The default is
   `openai/gpt-oss-20b`. Groq retires models without much notice: if replies
   stop arriving, check the Groq console for the current model list.

The key is only ever used server-side, as a `Bearer` header to Groq. It is
never logged, never sent to clients, and never written back to disk.

**Offline mode:** set `groq.enabled` to `false` in `config/falsity.json`, or
simply leave the key empty. Falsity answers from its scripted personality
instead. Nothing breaks; the game never waits on the network.

## How memory works

Each player gets one file: `<gameDir>/falsity-memory/<uuid>.json`. It holds
first meeting, conversation and pet counts, durable facts (names,
preferences, things you asked it to remember), recent interactions, a local
summary, the personality profile and the horror stage. Files are written
atomically; a corrupt file is discarded with a warning instead of crashing
the server. Only relevant memories (keyword-matched to your message) are
sent to Groq — never the whole history. Facts and recent notes are capped
(see `memory` in the config), so memory cannot grow without bound.

## Design notes

Falsity's model and texture are both generated from a single design file,
[`tools/model/falsity_model.json`](tools/model/falsity_model.json):

```bash
python3 -m pip install pillow
python3 tools/generate_assets.py            # regenerate model geometry + textures
python3 tools/generate_assets.py --preview  # also render a preview sheet
python3 tools/generate_assets.py --check    # verify nothing is stale (used by CI)
python3 tools/validate_assets.py            # static resource checks
```

Because one tool computes the UV layout, paints the texture and writes the Java
geometry, the model and its art cannot drift apart. The preview renderer is a
small z-buffered rasteriser used to review the creature without launching the
game — it is QA tooling, not part of the mod.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the code is organised and why
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — environment setup, commands, tooling
- [docs/ROADMAP.md](docs/ROADMAP.md) — milestones and what is deliberately not built yet
- [docs/falsity.example.json](docs/falsity.example.json) — annotated config example
- [docs/secrets.example.json](docs/secrets.example.json) — secrets file template

## Licence

MIT — see [LICENSE](LICENSE). All art, geometry and code in this repository is
original to this project.
