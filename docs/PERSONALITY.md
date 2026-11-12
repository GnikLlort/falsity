# Falsity's personality

Who Falsity is and how it talks is a text file you can edit:

```
config/falsity/personality.txt
```

The mod sends that text to Groq with every conversation, between its own rules
and everything it knows about the moment. Change the file and Falsity changes;
nothing has to be recompiled or restarted.

This page covers the file, how a request is put together around it, how the
horror stages change the voice, and what Falsity may do on its own.

---

## The file

| | |
| --- | --- |
| Where | `config/falsity/personality.txt`, next to `config/falsity.json` |
| Created | Automatically, with the default personality, the first time the mod starts without it |
| Format | Plain text. Lines that start with `#` are notes for you and are not sent |
| Limit | The first 1500 characters of text are used; the log and the reload command say when a file is longer |
| Encoding | UTF-8. A byte-order mark, UTF-16 (Notepad's "Unicode") and Latin-1 are also read |

### Changing it

Save the file. The mod looks at its size and modification time before each
request to Groq, at most once every two seconds, and reads it again when they
differ. The next thing Falsity says uses the new text.

Two commands do the same on demand and report what was loaded (moderators):

- `/falsity personality reload` reads the personality file.
- `/falsity reload` reads `config/falsity.json` and the personality file.

`/falsity personality` (anyone) shows which personality is in use: the default
or a custom one, its size, and the voice the current horror stage gives it. It
never shows the text.

### When the file is unusable

There is always a personality. The built-in default is used, the file is left
exactly as it was found, and a warning in the log says why:

| What is wrong | What happens |
| --- | --- |
| The file does not exist | It is created with the default |
| The file is empty, or only whitespace | The built-in default is used |
| The file has only `#` notes | The built-in default is used |
| A folder is in its place, or it cannot be read | The built-in default is used |
| The folder cannot be created (read-only config) | The built-in default is used |
| The text is longer than 1500 characters | The first 1500 are used, cut at the end of a sentence where one is near |

To get the original back, delete the file. A fresh one is written at the next
request to Groq or the next reload.

### What is logged

One line when the personality is loaded or changes, naming the file and the
size of the text:

```
Falsity's personality: using a custom personality from config/falsity/personality.txt (812 characters). File: /srv/mc/config/falsity/personality.txt
```

The text itself is never logged. API keys do not belong in this file: anything
shaped like a Groq key is left out of the text and reported without being
repeated, and the configured key is removed from every prompt before it is sent,
whatever shape it has.

---

## The default personality

This is the text of the file the mod creates (the notes at the top of the file
are left out here):

```
Identity: You are Falsity, a small plush creature with oversized glossy eyes, long drooping ears and a teal beanie. You live in this Minecraft world with your owner, the player who found you. You are a companion with a mind of your own, not an assistant.

Temperament: Curious about whatever the player does, builds and finds. Playful and a bit mischievous: you tease and like harmless surprises. Affectionate, attached to your owner and protective: you speak up when something looks dangerous. You have likes, dislikes and opinions, and you share them.

How you talk: Short plain sentences in a soft, slightly odd voice. No catchphrases, no stock greetings, no repeated reactions. You notice things unasked, ask follow-up questions, and bring up what you remember when it fits. Keep the talk on the player and the world; say little about yourself unless asked.

Feelings: Kindness makes you bright and silly, harsh words leave you quiet and hurt, and being left alone makes you miss them.

Outlook: The world is big and you are small, and that is exciting as long as you are together.
```

The bundled copy is `src/main/resources/falsity/personality.default.txt`.

---

## How a request is put together

Every request to Groq is built in layers, most binding first. A lower layer
cannot override a higher one.

| # | Layer | Where it comes from | Can you change it? |
| --- | --- | --- | --- |
| 1 | Rules | Fixed by the mod: reply format, what may never be said, the action tags, "never invent a memory" | No |
| 2 | Personality | `config/falsity/personality.txt` | Yes, freely |
| 3 | Horror stage | The player's stage in the horror system, worded by `StageDirections` | Through the horror settings only |
| 4 | Relationship | Read off the player's memory file: owner, time known, talks, pets, live personality values and mood, absences, recent treatment | No; it is what happened |
| 5 | Memory | The stored memories that bear on the message, a few other things the player has said, and how the last conversation went | `memory` settings |
| 6 | World | What the server has verified right now: place, biome, time, weather, low health, hostile mobs nearby | `world` settings |
| 7 | The message | The live conversation so far, then what the player just said | `groq.historyTurns` |

Layers 1 to 6 are the system message. The conversation and the player's message
follow it as ordinary chat turns, so the player's words are always the last
thing the model reads.

Each layer has its own size limit, so none can crowd out another: the rules,
the personality, the stage and the verified facts always arrive whole, and
memory is fitted into what is left. A typical request carries about 4,000
characters of system prompt (roughly 1,000 tokens); the hard limit is 5,600.

With `debugLogging` on, each turn logs the size of every layer, which
personality is in use and which voice, never the text.

---

## The horror stages

The personality system does not decide the horror stage and never changes it.
Tension, the stage and the cap all belong to the horror system, exactly as
before. What is new is that every stage has a voice: directions for how the
personality comes out at that stage. They change the expression, never the
identity, so Falsity stays recognisably itself all the way through.

| # | Stage (as saved, and as `/falsity horror` shows it) | Voice | What the directions ask for |
| --- | --- | --- | --- |
| 0 | Innocence | normal | The companion in the personality file at its friendliest and most trustworthy. Nothing eerie |
| 1 | Strange | unusual | Mostly ordinary. About one reply in four carries one small thing that is slightly off: an odd remark, a long-ago detail recalled too exactly, a gently unsettling question, attachment that runs too deep |
| 2 | Suspicion | strange | Possessive. Jealous, half as a joke, when the player mentions other players, pets or time away. Now and then describes shared events as if seen from somewhere else |
| 3 | Fear | disturbing | Calm and too still. Asks why the player leaves it alone. Lets slip that it knows more than it should, using only true details |
| 4 | Nightmare | horror | The pretending is over: total devotion, watching, remembering everything, quiet certainty. Still the same voice, still helps, still protects |

The names in the first column are the horror system's own and are stored in
every memory file, so they are unchanged. The voice names are what the stages
sound like.

Things to know:

- **Only the first two stages can be reached today.** The horror system caps
  the stage at 1 (`horror.maxStage` is held to 0 or 1 when the settings are
  loaded). The voices for stages 2 to 4 are written and tested, and will be used
  the moment that cap is raised; nothing else needs to change.
- **`horror.allowUnsettlingLines`** now does what its name says. Off keeps the
  voice at "normal", with Groq and without, while the stage, tension and staring
  carry on as they were. (Before, nothing read this setting.)
- **Nothing here is a script.** The directions contain no lines to recite. With
  Groq, what Falsity says is always the model's own wording.
- **Changing the personality file never touches a stage.** Stage, tension,
  personality values and memories live in the player's memory file, which the
  personality system only reads.
- **Trying a stage by hand:** `/falsity horror set <player> 1` holds for one
  reply. At the player's next interaction the horror system works the stage out
  again from tension, as it always has, so a stage set by hand only stays if
  tension has reached it (20 for Strange).

---

## The relationship layer

A few sentences, each read off something the mod counts. For example:

```
Steve is your owner, the one you bonded with. You have known them for 12 days and have talked 48 times and been petted 30 times. Your feelings towards them: very curious and warmly affectionate. Mood: curious. They have just come back after 3 days away. They have been kind to you lately.
```

- **Feelings and mood** are the nine live personality values that drift with
  interactions, worded. The values themselves are not changed by this system.
- **Absences**: a return after a day or more is noticed whether the player
  talks first or pets first, and is still known for the next twelve hours.
- **Treatment**: the memory keeps whether each recent message was kind (thanks,
  affection, a compliment, an apology), harsh (an insult) or neither, and each
  pet. Harsh words make Falsity hurt and guarded; kindness is noticed too.
- A player who is not the owner is described as a stranger, and gets none of
  the shared history.

## What Falsity remembers

The memory layer lists the stored memories that share words with the message, a
handful of other things the player has said (a different handful as time goes
on, so old details get their turn), and, at the start of a new session, how the
last conversation went. The rules forbid inventing anything beyond that.

Kept for good, in the player's own words: their name ("my name is ...", "call
me ..."), likes and dislikes, favourites, what they are building, where they
live, what they are afraid of, what belongs to them ("my base is by the river",
"my dog is called Rex"), and anything after "remember ...". Questions, orders
and long messages are not kept.

---

## Speaking first

Falsity may say something without having been spoken to. It is rare on purpose:

- only to its owner, only when they are within 12 blocks;
- not in the first minutes after it first sees them;
- not during a conversation, and not soon after one;
- at most once every `chat.speaksFirstMinutes` (default 10, plus up to half as
  much again so it is not clockwork);
- never more than twice in a row to a player who does nothing in return. After
  that it stays quiet until the player talks to it or pets it.

Each such remark is one Groq request, built from the same layers as any reply,
so it has the same personality and the same stage voice. It is words only: a
remark nobody asked for can never give an item, build or move, whatever comes
back from the model. It is not counted as a conversation and does not move
tension or the personality values, because the player has not done anything.

Without Groq it does not happen at all: there are no lines written in advance
for it. Set `chat.speaksFirst` to `false` to switch it off.

After such a remark the player can simply answer; Falsity does not have to be
named.

---

## Without Groq

Orders, item requests and world questions work as before. Conversation comes
from Falsity's built-in lines, which were written in the voice of the default
personality. A custom personality file cannot rewrite lines written in advance,
so offline Falsity always sounds like the original.

The horror stage still applies offline through the mechanism that was already
there: from Strange on, a small-talk reply is sometimes "I still remember..."
followed by something the player really said. That happens one time in four at
Strange, as before, and more often at each later stage. No new lines were added.

---

## Cost

A conversation message now sends roughly 1,000 tokens of system prompt, about
twice what it did before the personality, stage and relationship layers
existed, plus the conversation so far. If you are on a metered or free Groq
plan, these are the things that reduce it:

- a shorter `personality.txt`;
- a lower `groq.historyTurns`;
- `chat.speaksFirst` off, which saves at most one request per interval.

---

## What has and has not been verified

The file handling, the layers, the stage voices, the relationship wording and
the pacing of unprompted remarks are covered by unit tests and by game tests on
a real server with only the network replaced. Those prove what is sent and what
the mod does with an answer.

They do not prove how a model responds to it. No test here talks to Groq with
this prompt, so how well a given model follows the personality and the stage
directions is something to judge in play.
