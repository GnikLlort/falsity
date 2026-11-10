# Falsity

An original AI horror companion for **Minecraft Java 26.3**, built with **Fabric**.

Falsity begins as a small, harmless-looking companion: a plush creature with a
teal beanie, oversized glossy eyes and long drooping ears. It wanders, watches
you, and bonds with the first player who reaches out to it. What happens after
that is the rest of this project.

> **Status: Milestone 1 (foundation).** The creature exists, is rendered with an
> original model and texture, spawns, moves, animates and can be befriended.
> There is **no AI, no horror system and no transformation yet** — those are
> later milestones and are not faked anywhere in the code.

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
| Configuration file | Yes — `config/falsity.json` |
| Groq AI, memory, horror engine, transformation | **Not implemented** — see [ROADMAP](docs/ROADMAP.md) |

---

## Requirements

- **Java 25** to build and run. Minecraft 26.3 requires it, and so does Fabric
  Loom 1.19.
- **Gradle 9.8.2** — supplied by the wrapper, no local install needed.
- **Minecraft 26.3** with **Fabric Loader 0.20.1** and **Fabric API 0.165.2+26.3**
  to play the built jar.

All versions are declared in [`gradle.properties`](gradle.properties) and were
checked against <https://fabricmc.net/develop> on 2026-11-10.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. The same task compiles both source sets, runs the
unit tests and runs the server-side game tests: a real headless server loads the
mod, spawns Falsity, ticks it, bonds it to a player, exercises the sit/follow
orders and measures it walking towards its owner. If any of that fails, the build
fails.

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

## Licence

MIT — see [LICENSE](LICENSE). All art, geometry and code in this repository is
original to this project.
