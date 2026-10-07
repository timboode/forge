# Card-data MCP server used by forge-llm

`CardDatabaseMCPServer/` is a copy of the source of the **CardDatabaseMCPServer** project from
`DeckScorer/MTG.DeckScorer/`, taken from its working tree on 2026-10-04 (HEAD was `210dee4`, "Consume oss models",
with uncommitted local changes), vendored here so that forge-llm
can offer card lookups (Oracle text, official rulings) to the model that is playing a game.
It serves the MTGJSON `AllPrintings.sqlite` database over MCP (streamable HTTP).

Only the source is committed. Not included: `bin/`, `obj/`, logs, `AllPrintings.sqlite` (about 500 MB; the server
downloads a fresh copy from mtgjson.com on first start and refreshes it weekly) and the generated price/frequency
databases.

## Differences from the original

All additions; nothing the original does has been removed or changed.

| Change | Why |
|---|---|
| `Tools/GameLookupTools.cs` - new MCP tool **`lookupCard(cardName)`** | `searchCards` returns up to 50 cards with prices, legalities and usage statistics - far too much text for a model in the middle of a game with a small context window. `lookupCard` returns one card's mana cost, type, Oracle text (all faces) and rulings in a few hundred tokens, and suggests names when there is no exact match. |
| `Program.cs` - registers `GameLookupTools` | exposes the tool |
| `Program.cs` - environment variable **`CARDDB_SKIP_IMPORTS=1`** skips the price and card-frequency imports at startup | those need network access and are irrelevant to playing a game; start-up goes from minutes to seconds |
| `.gitignore` | keeps build output and the databases out of git |

The server also exposes the original deck-building tools (`searchCards`, `beginNewDeck`, ...). forge-llm's opencode
agent has every tool except `lookupCard` switched off, so a model playing a game cannot call them.

## Running it

Requires the .NET 10 SDK.

```
cd forge-llm/mcp/CardDatabaseMCPServer
CARDDB_SKIP_IMPORTS=1 dotnet run -c Release -- 3041        # MCP endpoint: http://127.0.0.1:3041/
```

The first start downloads `AllPrintings.sqlite` into the build output directory. To reuse a database you already
have, publish and drop it next to the executable:

```
dotnet publish -c Release -o ../../../out/card-mcp
cp /path/to/AllPrintings.sqlite ../../../out/card-mcp/
CARDDB_SKIP_IMPORTS=1 ../../../out/card-mcp/CardDatabaseMCPServer.exe 3041
```

(The server's default port is 3020; use another one if something else - e.g. a repo-context gateway - already
listens there.) Then give forge-llm the URL: `--mcp-url http://127.0.0.1:3041/`.

## Not used: MCPNaturalLanguageCardKnowledgeBaseTools

The sibling project `MCPNaturalLanguageCardKnowledgeBaseTools` is not consumed: its single tool runs a second LLM
agent behind the call. That is slow and costly inside a game, and pointless for the small-context local models forge-llm
is being developed with. `lookupCard` covers what a player needs mid-game.
