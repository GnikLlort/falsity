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

`<version>` is the value of `version` in `gradle.properties` (currently `0.3.0`),
so the file is `build/libs/falsity-0.3.0.jar`.

`./gradlew build` compiles the mod, runs the unit tests, runs the server-side
game tests, and produces the jar. If any test fails the build stops and no jar
is written.

Building needs Java 25 and access to the Fabric and Maven repositories. If you
would rather not build it yourself, the GitHub Actions run of every push builds
the same jar and attaches it to the run as the `falsity-jar` artifact.

## 2. Install on a client

1. Install the Fabric loader for Minecraft 26.3 (https://fabricmc.net/use).
2. Drop `falsity-<version>.jar` into your `.minecraft/mods` folder.
3. (Optional, for conversations) Add your Groq key — see *Secrets* below.
4. Launch the game with the Fabric profile.

In single-player the "server" is your own game, so the key goes into your
`.minecraft` folder (or its environment), next to `mods/`.

## 3. Install on a dedicated server

1. Install Fabric server for Minecraft 26.3.
2. Drop `falsity-<version>.jar` into `<server>/mods`.
3. (Optional) Add `GROQ_API_KEY` to the server environment, or create
   `<server>/secrets.json` — see *Secrets* below.
4. Start the server. Falsity registers on initialisation; no command is needed
   to talk to it once a companion is bonded.
5. If you added a key, run `/falsity ai test` once as an operator. It sends one
   request and says either that Groq works or why it does not. The startup log
   line `Falsity ready (...)` says where the key was found, or which file it
   looked for and did not find.

On a server the key and the network connection are the server's: a key on your
own PC does nothing, and the server machine must be allowed to open outgoing
HTTPS connections to `api.groq.com`.

## 4. Talking to Falsity

Once a Falsity is bonded to you (right-click it), you can:

- **Just type in chat**, addressing it by name — for example
  `Falsity, can you come here?`, `Falsity, give me a crafting table and 64 sticks`
  or `Falsity, where is the nearest village?`. It hears ordinary chat; no
  `/falsity talk` command is required. It only answers when you mention its
  name or you are still in a warm conversation, and only the Falsity bonded to
  you within range answers.
- Orders, item requests and questions about the world are carried out by the
  server at once, with or without Groq. Everything else is conversation: with
  Groq it is a real reply, without it a scripted line.
- If you write again before Falsity has answered, or within
  `conversation.cooldownSeconds`, the message waits its turn and is answered
  next. Nothing is dropped silently: a message that is too long, or addressed
  to Falsity while yours is out of range, gets a short note saying so.
- With Groq set up, Falsity sometimes says something first, when you are close
  and nobody has been talking. You can simply answer; its name is not needed.
  `chat.speaksFirst` switches this off.
- Use `/falsity` for the other commands: `summon`, `memory`, `personality`,
  `horror`, and for operators `ai status`, `ai test`, `reload` and
  `personality reload`.

The mod is configured through `config/falsity.json` (see
`docs/falsity.example.json` for every option). The `chat`, `conversation`,
`items`, `world` and `building` blocks control natural chat, how often Groq is
asked, item assistance, questions about the world and building respectively;
all are on by default with conservative, clamped settings. After editing the
file, `/falsity reload` applies it without a restart.

Who Falsity is and how it talks is a second file, `config/falsity/personality.txt`.
It is created with a default the first time the mod starts. Edit it in any text
editor and save: the next thing Falsity says uses it. An empty or missing file
falls back to the default, so Falsity is never left without a personality.
[PERSONALITY.md](PERSONALITY.md) describes the file and everything around it.

The limits that matter most in play:

| Setting | Default | Meaning |
| --- | --- | --- |
| `items.permissionMode` | `OWNER` | Who may ask for items: `NONE`, `CREATIVE`, `OPERATOR`, `OWNER` or `UNRESTRICTED`. |
| `items.maxPerRequest` | `64` | Most of one item in one request. Asking for more refuses the whole request and says why. |
| `items.maxTypesPerRequest` | `8` | Most different items in one request. |
| `items.confirmAboveCount` | `128` | A request for more items than this in total asks "yes or no" first. |
| `items.cooldownSeconds` | `15` | Pause between two item requests of one player. |
| `world.locatePermission` | `OWNER` | Who may ask Falsity to find structures, ores and caves. What a player could read off their own screen (position, biome, time, weather) is open to anyone Falsity answers. |
| `world.locateCooldownSeconds` | `5` | Pause between two searches of one player. |
| `world.structureSearchRadiusChunks` | `64` | How far a structure search reaches. |
| `world.scanRadius` | `24` | How far, in blocks, Falsity looks for ores and caves. Only chunks that are already loaded are read. |
| `world.shareCoordinates` | `true` | Whether answers may contain coordinates. Also off when the `reducedDebugInfo` game rule is on. |
| `conversation.cooldownSeconds` | `3` | Minimum time between two Groq requests of one player. |
| `chat.speaksFirst` | `true` | Whether Falsity may say something without being spoken to. Needs Groq; each remark is one request. |
| `chat.speaksFirstMinutes` | `10` | Least minutes between two such remarks to one player (2 to 240). |
| `horror.allowUnsettlingLines` | `true` | Whether what Falsity says may turn unsettling as its horror stage rises. Off keeps its voice at the first stage; the stage itself is not affected. |

## Secrets: the Groq API key

The key is resolved in this order; the first usable one wins:

1. the `GROQ_API_KEY` environment variable,
2. a git-ignored file named exactly `secrets.json` in the game/server
   directory, or in its `config/` folder (see `docs/secrets.example.json`):

   ```json
   { "groqApiKey": "gsk_your_key_here" }
   ```

3. the empty-by-default `groq.apiKey` field in `config/falsity.json`.

The entry may also be called `apiKey` or `groqKey`, in any capitalisation.
Quotes, a leading `Bearer ` and stray spaces or line breaks around a pasted key
are removed. A file with the wrong name (`secrets.json.txt` is the usual one on
Windows, where file extensions are hidden), an empty or unreadable file, and a
key that still holds the placeholder text are each named in the startup log
and in `/falsity ai status`, which also prints the exact path it looked at.

The key is never logged, never synced to clients, and used only on the server
side. Without a key, Falsity still answers from its offline personality lines and
honours local requests (follow, sit, give items, world questions, build) through
`IntentRecognizer` — so the mod is fully usable without any key.

**If Falsity only gives short scripted answers,** Groq is not answering. Run
`/falsity ai test` and look the result up in [GROQ.md](GROQ.md), which lists
every cause with its fix.
