# forge-llm - LLM-controlled players for Forge

An LLM agent can pilot a Forge seat. The seat is a normal AI player whose three most important decisions -
what to do with priority, which creatures attack, and how to block - are handed to a `DecisionAgent`. Every
other prompt the engine can raise (mulligans, discards, scry, X values...) stays with the built-in AI.

Status: **working end to end.** Stub agents (no model needed) drive the tests; `OpencodeAgent` plays through a real
model via [opencode](https://opencode.ai) - developed against a local Gemma E2B (16k context) in LM Studio, intended for
a large-context hosted model later. See "Using a real model through opencode".

## Keeping this mergeable with upstream Forge

* The module is **additive**: no existing source file is modified. The only change outside `forge-llm/` is
  an opt-in Maven profile in the root `pom.xml` (`-Pllm`), modelled on the existing `ios` profile - the
  default build does not even see this module.
* It extends two public forge-ai types (`LobbyPlayerAi`, `PlayerControllerAi`) and calls public engine APIs.
* Everything that reaches into forge-ai helpers written for the built-in AI (`ComputerUtilAbility`,
  `ComputerUtilMana`, `AiController.canPlaySa`...) goes through **one file**: `bridge/AiBridge.java`. If an
  upstream refactor breaks us, that is where to look first.
* Nothing in Forge refers to this module; deleting `forge-llm/` and the profile removes the feature entirely.

## Layout (`forge.llm.*`)

| Package   | Role |
|-----------|------|
| `agent`   | The seam to the model: `DecisionAgent`, `AgentRequest/Choice`, `ChoiceParser` (text replies -> choice), `RecordingAgent` (transcript decorator), `TimeLimitedAgent` (bounds how long a model call may hold the game), `stub/*` (heuristic and pass-only stand-ins). |
| `action`  | **Hard logic.** `PriorityActionEnumerator` / `CombatActionEnumerator` work out what the player can legally do right now - and, for things that look usable but are not, why not. `ActionExecutor` turns a chosen action into something the engine can play. |
| `state`   | Renders the game from one player's point of view (own hand only, other zones public), the decklist, the deduced library composition, and the match log. |
| `prompt`  | `PromptBuilder` + `SystemPrompt`: a full prompt for the first decision of a session, small delta prompts afterwards. |
| `context` | `ContextManager`: the memory lifecycle (below). Pure strings + `DecisionAgent`, no game types. |
| `control` | `PlayerControllerLlm` / `LobbyPlayerLlm` (the integration point), `PriorityGate` (when is the model worth waking), `LlmPlayerConfig`, `LlmStats`. |
| `bridge`  | `AiBridge` - the only coupling to forge-ai internals. |
| `opencode`| `OpencodeAgent` (the real-model `DecisionAgent`), `OpencodeServer` (launches/stops `opencode serve`), `OpencodeClient` (HTTP), `OpencodeConfigBuilder` (the config + lean `forge-player` agent injected into opencode), `OpencodeSettings`. |
| `run`     | `PlayVsLlm` (**play in the Forge GUI** against LLM opponents), `LlmMatchRunner` (headless games, modelled on `forge sim`), `OpencodeCheck` (setup sanity check), plus the shared `RunOptions`, `MatchSetup`, `DeckSource`, `Format`, `Seat`, `GameLauncher`. |

## How a decision flows

1. The engine asks the seat for priority (`chooseSpellAbilityToPlay`) / attackers / blockers.
2. **Hard logic first.** The enumerator lists every legal land/spell/ability (timing, zone, costs payable,
   legal target exists). Nothing legal besides passing -> pass automatically, **the model is never called**.
3. **Gate.** `PriorityGate` decides whether the situation merits a call: all own-turn steps by default; on
   opponents' turns only when something of theirs is on the stack, or in the declare-attackers /
   declare-blockers / end-step windows. A per-turn budget caps runaway calls.
4. The prompt is built: the **numbered legal actions**, plus a **"CANNOT DO RIGHT NOW"** list with engine-
   derived reasons ("cannot pay {2}{B} (2 untapped mana sources...)", "sorcery-speed: only usable during your
   own main phase", "no legal target").
5. The agent answers with action ids. Answers are validated (ids exist, attacks/blocks are legal under the
   combat rules); an invalid answer is sent back with the problem spelled out (`maxRetries`).
6. The action is executed. After it resolves the engine asks for priority again, so the **action list is
   recomputed from the new game state every single time**.
7. Any trouble - agent exception or timeout (`decisionTimeoutSeconds`), no valid answer, an action the engine
   refuses - falls back to the built-in AI's own decision. A broken model can slow a game but never wedge it.
   Because the built-in AI reports success even when it failed to play something, an action counts as played
   only if the game state actually changed; one that fails twice in a turn is not offered again that turn.

## Context / memory lifecycle

* **Own turn:** one session stays open; the first prompt is complete (rules, memory, full decklist, deck
  minus seen cards = library contents, whole match log, board, card texts), later prompts are deltas (new
  log lines, current board, new options).
* **After the turn:** the transcript is compressed (`DecisionAgent.summarize`) into `previousTurnSummary`,
  replacing the previous one (which is handed to the summarizer so memory carries forward).
* **Opponents' turns:** responses use a separate session whose context is the last-turn summary. Instant-
  speed activity is logged separately.
* **When the next own turn begins:** that instant-speed log is compressed into `instantSummary`; both
  summaries go into the first prompt of the new turn (and the instant summary is folded into the turn
  summary when that turn ends).

## Running it

Needs JDK 17+ (`JAVA_HOME` is used when set) and the Forge build. Build once from the repository root, then use the
launch scripts in `forge-llm/scripts` - below, `llm` stands for `forge-llm/scripts/llm.cmd` on Windows and
`forge-llm/scripts/llm.sh` elsewhere:

```
mvn -Pllm -pl forge-llm -am compile                 # also writes forge-llm/target/classpath.txt for the scripts

llm play  [options]               # play in the Forge window against LLM opponents (below)
llm run   [options]               # headless games: results, statistics, --transcript <dir>
llm check [options]               # does opencode + the model answer? (no game)

mvn -Pllm -pl forge-llm -am test                    # unit + integration tests
```

Options are shared by all three (`forge.llm.run.RunOptions` documents them): `--format constructed|commander`,
`--seats human,llm,ai,ai`, `--deck1 .. --deckN` (a .dck file, a name from your Forge deck library, or `random`),
`--agent heuristic|pass|opencode`, `--seed`, `--transcript <dir>` (every prompt the model saw and every answer),
plus the `--oc-*` / `--mcp-url` options below. Seats without a deck get a random deck that ships with Forge
(the 180 commander precons for Commander).

## Playing a game against the LLM yourself (Commander or Constructed)

```
llm play --deck1 "<your deck>" --mcp-url http://127.0.0.1:3041/
llm play --list-decks                    # names to use for --deck1 (marks decks that cannot be played)
llm play --seats human,llm,llm,ai --deck1 "<your deck>"
```

This opens the normal Forge window and starts a match straight away with you in your seat; the other seats are LLM-
or AI-driven. Defaults: Commander, seats `human,llm,ai,ai`, the opencode agent with the model from
`--oc-model` (LM Studio's Gemma unless told otherwise; see "Using a real model through opencode" for DeepSeek).
Exactly one seat must be `human`. `--format constructed` plays a normal 1v1 (`--seats human,llm`).

Things to expect: the opponent's turns look like the built-in AI's but with pauses while the model thinks (a local model
can take a minute per decision; with a hosted model, seconds). Closing the window stops the opencode server it started.
Add `--transcript out/` to read afterwards what each LLM seat was shown and what it answered. It does not
touch Forge's own lobby or any Forge source: it starts the desktop application the way `forge.view.Main` does and hands
a ready-made table to Forge's `HostedMatch`.

## Commander and multiplayer

LLM seats work in Commander (and any other number of seats) the same way as in a 1v1:

* The table is set up as Forge's lobby does it (base game type Constructed + the Commander variant): 40 life, a command
  zone, commander damage and commander tax are the engine's.
* Prompts add what a Commander player needs to see: the rules reminder and the number of opponents, every player's
  commander with its zone and how often it was cast (and so the extra `{2}` per cast), the commander damage each player
  has taken from which commander (21 is lethal), the commander section of the decklist, and the engine's own bookkeeping
  cards are hidden from the command zone.
* Casting a commander from the command zone is a normal option in the action list; the tax is noted on it and is already
  part of the "can you afford it" check.
* Attacks offer every opponent (and planeswalker) as a defender per creature; blocks only list attackers that attack
  you. Options are annotated where the engine's rules would otherwise reject an answer: creatures that must attack, and
  attackers that can only be blocked by two or more creatures together.
* Identical offers (a commander castable through two permissions) are listed once.

Commander tables are far larger than a 1v1 prompt-wise (three opponents' boards): use a large-context model for real
games; with a 16k local model the compact-prompt restart will fire often.

## Using a real model through opencode

`OpencodeAgent` talks to an `opencode serve` process that this JVM launches (on a free loopback port, behind a random
password) and stops again. Nothing of your own opencode setup is read or modified: the server gets private
config/data/state directories, its configuration in an environment variable (`OPENCODE_CONFIG_CONTENT`, so API keys
borrowed from your config never touch the disk) and `OPENCODE_DISABLE_CLAUDE_CODE` / `OPENCODE_DISABLE_PROJECT_CONFIG`.
The latter two matter: opencode otherwise also appends your Claude Code `~/.claude/CLAUDE.md` and any
`AGENTS.md`/`CLAUDE.md` it finds above its working directory to every request's system prompt - about 1,300 tokens on the
development machine, and it would be sent to whatever provider is in use. (Downloaded provider SDKs are cached in
`<tmp>/forge-llm-opencode-cache` between runs; per-run directories left behind by a killed run are swept after a day.)

**Requirements:** `opencode` on the PATH (tested with 1.18.29) and a model: by default LM Studio serving
`google/gemma-4-e2b` at `http://127.0.0.1:1234/v1`.

### Why a purpose-built agent

A first message through opencode's stock agent cost **10,667 input tokens** - its coding system prompt and a dozen tool
definitions - which is most of a 16k context window. The injected `forge-player` agent has the game instructions
as its prompt, **every built-in tool switched off** and, if a card server is configured, a single tool
(`lookupCard`): a short question and its answer then occupy about **1,250 tokens** of the window (about 1,500 with the tool).
Summaries use a second agent, `forge-summarizer`, with its own short instructions and no tools.

### Context window management

opencode keeps one conversation per forge-llm session (one per own turn; one per opponent turn for responses). The
agent tracks the conversation size from the token counts opencode reports and never sends a request that would
overflow the model's window (`--oc-context`, or what opencode knows about the model). Instead it throws
`ContextOverflowException`; the controller then starts a fresh session with a **compact** full prompt (decklist, memory
and board, but only the most recent ~40 log lines and no library listing) and carries on. If even that does not fit, the
built-in AI decides. With a large-context model none of this ever triggers.

### Card lookup (MCP)

`forge-llm/mcp/CardDatabaseMCPServer` (see its README) serves card data from MTGJSON. Start it and pass
`--mcp-url`; the model then has `mtgcards_lookupCard(cardName)` - exact Oracle text and official rulings - and nothing else.
Small models rarely use it; the prompt already contains the text of every card on the table.

### Trying it

```
# 1. (optional) card server; see forge-llm/mcp/README.md
CARDDB_SKIP_IMPORTS=1 dotnet run -c Release --project forge-llm/mcp/CardDatabaseMCPServer -- 3041

# 2. check the setup without a game: starts opencode, asks a canned question, a follow-up and a summary
llm check [--oc-model ...] [--mcp-url http://127.0.0.1:3041/]

# 3. headless game (slow with a local model - minutes per turn), or play yourself with "llm play" (see above)
llm run --agent opencode --mcp-url http://127.0.0.1:3041/ --format commander --seats llm,ai,ai --transcript out/ --timeout 1800 --max-log-lines 80
```

**A different model** (e.g. a hosted DeepSeek with a 500k window) - reuse the providers from your own opencode config:

```
--oc-model <provider>/<model> --oc-provider-config ~/.config/opencode/opencode.json --oc-context 500000 [--oc-variant high]
```

Only the `provider` and `disabled_providers` sections of that file are copied - not its MCP servers or plugins.
`--oc-url <url>` attaches to a server you already run instead (password from the `OPENCODE_SERVER_PASSWORD`
environment variable; the server must already define the `forge-player` and `forge-summarizer` agents - see
`OpencodeConfigBuilder` - and forge-llm refuses to attach if it does not).

Credentials that opencode keeps outside its config file (added with `opencode auth login` or `/connect`) live in its data
directory, which an isolated server does not see. Use a provider that has its key in the config file (or `{env:NAME}`),
or run with a config that does.

**When something is slow or wrong:** `--oc-log-level DEBUG --oc-work-dir <dir>` keeps opencode's log in `<dir>/opencode.log`;
`--oc-keep-sessions` leaves the conversations in opencode for inspection; `--transcript` records every prompt and answer.

## Tests

* `ChoiceParserTest`, `PriorityGateTest`, `ContextManagerTest` - pure unit tests.
* `OpencodeAgentTest`, `OpencodeConfigBuilderTest` - the opencode integration against a fake opencode server (HTTP, auth, sessions, overflow, summaries, config).
* `OpencodeLiveTest` - against a real opencode and model; skipped unless `FORGE_LLM_LIVE=true`.
* `RunOptionsTest` - command-line parsing, seats, defaults.
* `LlmGameIntegrationTest` - complete games on the real engine: every offered action executes, the agent is
  never asked when only passing is possible, prompts/sessions/memory have the designed shape, and a throwing
  or garbage-answering agent degrades to the built-in AI without wedging the game.

## MVP limitations (by design, listed so they are not surprises)

* Targets: abilities with exactly one target get one action per legal target, chosen by the agent. Multi-target
  abilities, modes of modal spells, X values, kicker and similar are still decided by the built-in AI helper.
* Mana is paid automatically (the agent sees available mana, not individual lands).
* Mulligans, discards, scry, trigger targets and other mid-resolution choices are the built-in AI's.
* Only Constructed and Commander are set up by the runner/launcher (Oathbreaker, Brawl, Planechase, ... are not). Teams are not supported.
* A game with an LLM seat in the GUI has no "thinking" indicator beyond Forge's normal priority highlight.
* Card text in prompts comes from the card scripts (Oracle text); rulings are available to a real model only through the optional `lookupCard` tool (below).

## Next steps

1. Tune prompts and reply handling against the large-context model (reasoning variants, answer format compliance).
2. Per-target / mode / X selection by the agent (extends `PriorityActionEnumerator` + `ActionExecutor`).
3. Optionally, tool-style play (`take_action` as an MCP tool) instead of reply parsing.

## Things worth knowing about real models

* **AI simulation copies.** Opposing AIs that simulate copy the running game; `LobbyPlayerLlm` recognises
  such copies (created while the real game is live) and gives them the plain AI controller, so simulated
  games never reach the model.
* **Hidden information.** Prompts contain your own hand and public zones only; opponents' face-down cards
  are shown without ids, and cards you cannot look at (e.g. exiled face down by an opponent) are not
  subtracted from the "cards still in your library" list.
* **Prompt size.** The full prompt includes the whole match log (`LlmPlayerConfig.maxLogLines`, default
  unlimited). Cap it for very long games.
* **Wall-clock.** A simulated turn costs several agent calls; `maxConsultationsPerTurn` and the gate windows
  in `LlmPlayerConfig` are the knobs, and `decisionTimeoutSeconds` bounds each call.
