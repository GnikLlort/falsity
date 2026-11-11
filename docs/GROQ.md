# Groq: setting it up and finding out why it is not answering

Falsity talks through [Groq](https://console.groq.com)'s OpenAI-compatible
chat API. A key is optional: without one, or whenever a request fails, Falsity
answers from its scripted offline lines, and orders, item requests and
questions about the world still work, because the server recognises those
itself.

If Falsity only ever gives short scripted answers, Groq is not answering. This
page is about finding out why in one command instead of guessing.

## 1. Get a key

Create a free key at <https://console.groq.com/keys>. It starts with `gsk_`.

## 2. Put it in one place

The first place that holds a usable key wins:

1. the `GROQ_API_KEY` environment variable of the server process
   (recommended for dedicated servers). It is read from the environment the
   game was started with, so after setting it restart the server, or the
   launcher as well as the game;
2. a file named exactly `secrets.json` in the game directory (the folder that
   contains `mods/` and `config/`), or in `config/`:

   ```json
   { "groqApiKey": "gsk_your_key_here" }
   ```

   `docs/secrets.example.json` is a template. The file is git-ignored;
3. the `groq.apiKey` field in `config/falsity.json`.

What is tolerated, because these are the usual ways a correct key ends up
unused:

- the entry may also be called `apiKey` or `groqKey`, in any capitalisation and
  with `_`, `-` or spaces (`GROQ_API_KEY`, `groq-api-key`), or be written as
  `{"groq": {"apiKey": "..."}}`;
- quotes around the key, a leading `Bearer `, and stray spaces, line breaks or
  invisible characters from copying are removed;
- a file saved by Notepad as UTF-16 or with a byte-order mark is read.

What is reported instead of silently ignored, at startup and in
`/falsity ai status`:

- a file called `secrets.json.txt`, `secrets.txt`, `secret.json` or
  `secrets.example.json` ("rename it");
- a file that is empty, is not valid JSON, has no key entry, or still holds the
  placeholder text;
- a key that still contains a space or another character a key cannot have.

The key is used only on the server, only as the `Authorization` header of the
request to `groq.endpoint`. It is never written to the log, to chat, to a
client or back to disk, and error messages that echo it are redacted.

## 3. Check it

Both commands need operator permission.

```
/falsity ai test
```

sends one short request with the current settings and prints the outcome in
one line: either `Groq works. It answered "..."` with the timing and token
usage, or `Groq test failed: ...` followed by `->` and what to do about it.

```
/falsity ai status
```

prints what is configured and what has actually been observed since the server
started:

```
--- Falsity AI status ---
Groq: configured, not verified yet. Run /falsity ai test, or just talk to Falsity.
Model: openai/gpt-oss-20b (max tokens 150 + 512 for reasoning at effort low, timeout 15 s, history 6 turns)
API key: found in secrets.json (56 characters; not shown)
Pacing: cooldown 3 s, at most 10 requests a minute, 0 waiting
This session: 0 requests, 0 answered, 0 failed
```

"Configured" is not "working". The first line only says `WORKING` after Groq
has really answered, and the startup log never claims a connection it has not
made. After a failure the report adds the last failure and the advice for it.
When no key was found, the `API key:` line names the exact `secrets.json` path
that was looked at.

Every failed request is also written to the server log as a warning with the
same details, so `logs/latest.log` shows why a particular reply was a scripted
one. After changing `config/falsity.json`, `/falsity reload` applies it without
a restart; a new key in `secrets.json` or a changed file is picked up on the
next message without any command.

## 4. What the answer means

| The report says | What it means | What to do |
| --- | --- | --- |
| `Groq: OFF` | `groq.enabled` is `false`. | Set it to `true`, then `/falsity reload`. |
| `Groq: NO API KEY` | None of the three places holds a usable key. The note says what was found instead. | Fix the place named in the report. |
| `failure=UNAUTHORIZED` (status 401) | Groq does not accept the key. | Create a new key and replace the old one. |
| `failure=FORBIDDEN` (status 403) | The request was refused. Either the key may not use this model (project permissions in the Groq console), or something between the server and Groq turned the request away: a VPN, proxy, firewall or a blocked region. `turned away before reaching Groq` in the detail means the second. | Check the project's model permissions, or try from another network. A new key does not fix a blocked network. |
| `failure=MODEL_UNAVAILABLE` | Groq does not offer `groq.model` (any more) to this key. Groq retires models with little notice. | Pick a current model in the Groq console and set `groq.model`. |
| `failure=BAD_ENDPOINT` | `groq.endpoint` is not a chat-completions address: it is malformed, Groq answered "unknown URL", or the address redirects elsewhere. | Remove the setting or set it to `https://api.groq.com/openai/v1/chat/completions`. |
| `failure=RATE_LIMITED` | Groq's own limit for the account (status 429), or the local `conversation.maxRequestsPerMinute`. The detail says which; `retry_after` says how long. | Wait, talk less often, or see the limits of your account in the Groq console. |
| `failure=TIMEOUT` | Groq was reached but did not answer within `groq.timeoutSeconds`. | Try again; raise `groq.timeoutSeconds` if it keeps happening. |
| `failure=NETWORK_ERROR` | The server could not reach Groq at all. The detail says how: `cannot find api.groq.com` (no DNS, usually no internet), `connection ... refused`, `could not connect ... in time` (blocked), `secure connection ... failed` (a proxy or security software intercepting HTTPS, or an outdated Java certificate store). | Fix the network path from the *server* machine to `api.groq.com`. |
| `failure=INVALID_RESPONSE` | Groq answered, but with no text. With a reasoning model this means it used the whole budget thinking. | Raise `groq.reasoningHeadroomTokens` or `groq.maxTokens`. |
| `failure=HTTP_ERROR` | Any other error status. The detail is Groq's own code and message. | Follow Groq's message; status 5xx is on Groq's side. |

On a hosted server the key and the network are the host's, not your PC's: the
environment variable or `secrets.json` has to be on the server, and the server
has to be allowed to make outgoing HTTPS connections. In single-player the
"server" is your own game, and the game directory is your `.minecraft` folder
(or the instance folder of your launcher). Behind an HTTP proxy,
start Java with its usual proxy options (`-Dhttps.proxyHost=... -Dhttps.proxyPort=...`).

## 5. What a message costs

Only conversation goes to Groq. "Follow me", "give me 16 torches" and "where is
the nearest village?" are answered by the server at once and cost nothing.

A conversation message sends the system prompt (who Falsity is, its mood, the
memories relevant to the message, facts the game has just verified, the rules),
up to `groq.historyTurns` earlier exchanges, and the message itself: roughly
400 to 650 tokens of prompt for the first message, more as the conversation
goes on. The answer is budgeted at `groq.maxTokens` (150). The default model,
`openai/gpt-oss-20b`, is a reasoning model: it thinks before it answers and
the thinking counts against the same budget, so for such a model the request
allows `groq.maxTokens` plus `groq.reasoningHeadroomTokens` (150 + 512) for
thinking and answer together, and asks for `low` effort. A typical answer uses
far less than the allowance. `/falsity ai status` shows the real numbers of
the last request as `tokens=prompt+completion=total`.

Groq's free tier limits requests and tokens per minute and per day, per
model. If `RATE_LIMITED` shows up in normal play, lower `groq.historyTurns` or
`groq.reasoningHeadroomTokens`, or choose a model with a larger allowance.

## 6. Settings

In `config/falsity.json` (every value is clamped to a safe range on load):

| Setting | Default | Meaning |
| --- | --- | --- |
| `groq.enabled` | `true` | Master switch. `false` means offline lines only. |
| `groq.apiKey` | `""` | Last-resort place for the key. Prefer the environment variable or `secrets.json`. |
| `groq.model` | `openai/gpt-oss-20b` | The Groq model id. |
| `groq.endpoint` | `https://api.groq.com/openai/v1/chat/completions` | The address. The base address `https://api.groq.com/openai/v1`, a missing `https://` and a trailing slash are put right on load. |
| `groq.timeoutSeconds` | `15` (2 to 60) | How long to wait before using an offline line. |
| `groq.maxTokens` | `150` (16 to 1024) | Longest reply. |
| `groq.temperature` | `0.7` (0 to 2) | Higher is more varied. |
| `groq.reasoningEffort` | `low` | `low`, `medium`, `high`, or empty to send no reasoning settings. Only used for `openai/gpt-oss` models. |
| `groq.reasoningHeadroomTokens` | `512` (0 to 4096) | Extra room for a reasoning model's thinking. |
| `groq.historyTurns` | `6` (0 to 12) | Earlier exchanges sent along, so replies stay on topic. |
| `conversation.cooldownSeconds` | `3` (0 to 300) | Minimum time between two Groq requests of one player. A message sent sooner waits its turn and is answered next; it is not dropped. |
| `conversation.maxRequestsPerMinute` | `10` (1 to 30) | Server-wide ceiling on Groq requests. |
| `conversation.maxMessageLength` | `256` (16 to 1024) | Longer messages are refused with a note. |

## 7. What the client does on its own

- A model that rejects the reasoning settings (status 400) is asked again once
  without them, and that is remembered for the model.
- A reply that came back empty because the budget ran out is asked for again
  once with a larger budget.
- A connection that fails immediately (typically one that the other side
  closed while it sat unused) is tried once more. Timeouts and error statuses
  are never repeated.
- Redirects are not followed, so the key is never carried to another address.
- `http://api.groq.com` is turned into `https://`: the key is not sent
  unencrypted.

## 8. How this is tested, and what is not

- `GroqClientTest`, `GroqClientDiagnosticsTest` and `GroqClientNetworkTest`
  script the HTTP layer and cover every failure in the table above.
- `GroqHttpTransportTest` runs the client's real HTTP code against a server on
  the same machine: the method, the headers and the UTF-8 body on the wire, a
  real timeout, a refused connection, a redirect and a dropped connection.
- `FalsityGroqPipelineGameTest` runs whole conversations on a real Minecraft
  server (chat message in, prompt, reply, action, memory) with only the network
  replaced.
- `GroqLiveTest` asks the real Groq service with the default settings. It needs
  a key, so it only runs when `GROQ_API_KEY` is set, and is reported as
  **skipped** otherwise:

  ```bash
  GROQ_API_KEY=gsk_... ./gradlew test --tests '*GroqLiveTest*'
  ```

  CI passes a repository secret of that name to the tests when one exists
  (*Settings → Secrets and variables → Actions → New repository secret*).

Until `GroqLiveTest` or `/falsity ai test` has been run with a real key, that
Groq accepts the request exactly as this version sends it is an expectation,
not a tested fact: the request follows Groq's published API, and the client
falls back as described above when part of it is refused.
