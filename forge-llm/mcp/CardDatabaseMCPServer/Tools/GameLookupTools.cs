using System.ComponentModel;
using System.Text;
using CardDatabaseMCPServer.Data;
using CardDatabaseMCPServer.Models;
using ModelContextProtocol.Server;

namespace CardDatabaseMCPServer.Tools;

/// <summary>
/// Compact, game-oriented card lookup for LLM agents that are PLAYING a game (as opposed to building a deck).
///
/// ADDED by the forge-llm integration; not part of the original CardDatabaseMCPServer. searchCards returns up to
/// 50 cards with prices, legalities and usage statistics, which is far too much to put in front of a model that is
/// in the middle of a game and has a small context window. This returns just the Oracle text and the rulings.
/// </summary>
[McpServerToolType]
public class GameLookupTools
{
    private const int MaxRulingChars = 2500;
    private const int MaxSuggestions = 6;

    private readonly CardDatabaseContext _db;
    private readonly ILogger<GameLookupTools> _logger;

    public GameLookupTools(CardDatabaseContext db, ILogger<GameLookupTools> logger)
    {
        _db = db;
        _logger = logger;
    }

    [McpServerTool(Name = "lookupCard")]
    [Description(
        "Looks up ONE Magic: The Gathering card by its exact name and returns its mana cost, type, current Oracle text and official rulings. " +
        "Use it during a game when you are unsure how a card (yours or the opponent's) works or interacts. Returns plain text.")]
    public string LookupCard(
        [Description("Exact card name, e.g. 'Lightning Bolt'. For a double-faced or split card either face name works.")]
        string cardName)
    {
        if (string.IsNullOrWhiteSpace(cardName))
        {
            return "Error: cardName is required.";
        }

        try
        {
            var wanted = cardName.Trim().ToLowerInvariant();
            var printings = _db.Cards
                .Where(c => c.Name != null && (c.Name.ToLower() == wanted
                                               || (c.FaceName != null && c.FaceName.ToLower() == wanted)
                                               || (c.AsciiName != null && c.AsciiName.ToLower() == wanted)))
                .Take(40)
                .ToList();

            if (printings.Count == 0)
            {
                return NotFound(cardName, wanted);
            }

            var card = printings[0];
            // the other face of a double-faced card has a different FaceName but the same Name
            var allRows = card.Name == null ? printings : _db.Cards.Where(c => c.Name == card.Name).Take(80).ToList();
            var sb = new StringBuilder();
            AppendFace(sb, card);

            // Other faces of a multi-face card share the Name but differ in Side/FaceName.
            foreach (var face in allRows
                         .Where(p => p.FaceName != card.FaceName)
                         .GroupBy(p => p.FaceName)
                         .Select(g => g.First()))
            {
                sb.AppendLine("--");
                AppendFace(sb, face);
            }

            AppendRulings(sb, allRows);
            return sb.ToString().TrimEnd();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "lookupCard failed for {CardName}", cardName);
            return $"Error looking up '{cardName}': {ex.Message}";
        }
    }

    private static void AppendFace(StringBuilder sb, Card card)
    {
        sb.Append(card.FaceName ?? card.Name);
        if (!string.IsNullOrWhiteSpace(card.ManaCost))
        {
            sb.Append(' ').Append(card.ManaCost);
        }
        sb.AppendLine();
        sb.Append(card.Type);
        if (!string.IsNullOrWhiteSpace(card.Power) || !string.IsNullOrWhiteSpace(card.Toughness))
        {
            sb.Append(' ').Append(card.Power).Append('/').Append(card.Toughness);
        }
        if (!string.IsNullOrWhiteSpace(card.Loyalty))
        {
            sb.Append(" Loyalty ").Append(card.Loyalty);
        }
        sb.AppendLine();
        // MTGJSON stores line breaks in rules text as a literal backslash followed by 'n'
        sb.AppendLine(string.IsNullOrWhiteSpace(card.Text) ? "(no rules text)" : card.Text.Replace("\\n", "\n"));
    }

    private void AppendRulings(StringBuilder sb, List<Card> printings)
    {
        // Rulings are stored per printing; take them from the first printing that has any.
        List<CardRulings> rulings = new();
        foreach (var printing in printings.Take(12))
        {
            var uuid = printing.Uuid;
            rulings = _db.CardRulings.Where(r => r.Uuid == uuid).ToList();
            if (rulings.Count > 0)
            {
                break;
            }
        }

        if (rulings.Count == 0)
        {
            sb.AppendLine().AppendLine("Rulings: none.");
            return;
        }

        sb.AppendLine().AppendLine("Rulings:");
        var used = 0;
        // the same ruling is often stored once per face or printing
        foreach (var r in rulings.OrderBy(r => r.Date).Where(r => !string.IsNullOrWhiteSpace(r.Text)).DistinctBy(r => r.Text!.Trim()))
        {
            var when = r.Date is { Year: > 1900 } d ? $"{d:yyyy-MM-dd}: " : "";
            var line = $"- {when}{r.Text!.Trim()}";
            if (used + line.Length > MaxRulingChars)
            {
                sb.AppendLine("- (further rulings omitted)");
                break;
            }
            sb.AppendLine(line);
            used += line.Length;
        }
    }

    private string NotFound(string cardName, string wanted)
    {
        var suggestions = _db.Cards
            .Where(c => c.Name != null && c.Name.ToLower().Contains(wanted))
            .Take(60)
            .ToList()
            .Select(c => c.Name!)
            .Distinct()
            .Take(MaxSuggestions)
            .ToList();

        return suggestions.Count == 0
            ? $"No card named '{cardName}' was found."
            : $"No card named exactly '{cardName}'. Did you mean: {string.Join("; ", suggestions)}?";
    }
}
