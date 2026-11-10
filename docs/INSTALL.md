# Installing Falsity

Falsity is a Fabric mod. You need the Fabric loader and (for Groq conversations)
a Groq API key.

## 1. Build the JAR

From the repository root:

```bash
./gradlew build
```

The jar is written to:

```
build/libs/falsity-<version>.jar
```

`<version>` is the value of `version` in `gradle.properties` (currently `0.2.1`),
so the file is `build/libs/falsity-0.2.1.jar`.

`./gradlew build` compiles the mod, runs the unit tests, runs the server-side
game tests, and produces the jar. If any test fails the build stops and no jar
is written.

> **This sandbox could not run Gradle.** The build environment here has no
> access to the Maven/Fabric dependency repositories (and no `javac`), so the
> jar was **not** produced here. The command above is the exact command to build
> it wherever the dependencies are reachable. The source on this branch is
> syntactically valid (checked) and the pure-logic classes were reviewed against
> the project's own 26.3 API notes in `docs/DEVELOPMENT.md`.

## 2. Install on a client

1. Install the Fabric loader for Minecraft 26.3 (https://fabricmc.net/use).
2. Drop `falsity-<version>.jar` into your `.minecraft/mods` folder.
3. (Optional, for conversations) Add your Groq key — see *Secrets* below.
4. Launch the game with the Fabric profile.

## 3. Install on a dedicated server

1. Install Fabric server for Minecraft 26.3.
2. Drop `falsity-<version>.jar` into `<server>/mods`.
3. (Optional) Add `GROQ_API_KEY` to the server environment, or create
   `<server>/secrets.json` — see *Secrets* below.
4. Start the server. Falsity registers on initialisation; no command is needed
   to talk to it once a companion is bonded.

## 4. Talking to Falsity

Once a Falsity is bonded to you (right-click it), you can:

- **Just type in chat**, addressing it by name — for example
  `Falsity, can you come here?` or `Falsity, give me 32 diamonds`. It hears
  ordinary chat; no `/falsity talk` command is required. It only answers when
  you mention its name or you are still in a warm conversation, and only the
  Falsity bonded to you within range answers.
- Use `/falsity` for the admin/debug commands (summon, memory, personality,
  horror, reload config, etc.).

The mod is configured through `config/falsity.json` (see
`docs/falsity.example.json` for every option). The new `chat`, `items` and
`building` blocks control natural chat, item assistance and building
respectively; all are on by default with conservative, clamped settings.

## Secrets: the Groq API key

The key is resolved in this order:

1. the `GROQ_API_KEY` environment variable,
2. a git-ignored `secrets.json` in the game/server directory
   (see `docs/secrets.example.json`),
3. the empty-by-default `groq.apiKey` field in `config/falsity.json`.

The key is never logged, never synced to clients, and used only on the server
side. Without a key, Falsity still answers from its offline personality lines and
honours local requests (follow, sit, give items, build) through
`IntentRecognizer` — so the mod is fully usable without any key.
