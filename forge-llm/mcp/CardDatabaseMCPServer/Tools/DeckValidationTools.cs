using System.ComponentModel;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using CardDatabaseMCPServer.Data;
using CardDatabaseMCPServer.Models;
using ModelContextProtocol.Server;

namespace CardDatabaseMCPServer.Tools;

/// <summary>
/// MCP tools for validating Magic: The Gathering Commander decks.
/// </summary>
[McpServerToolType]
public class DeckValidationTools
{
    private readonly CardDatabaseContext _dbContext;
    private readonly ILogger<DeckValidationTools> _logger;
    private readonly HttpClient _httpClient;

    // Basic land names that are exempt from singleton rule
    private static readonly HashSet<string> BasicLandNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "Plains", "Island", "Swamp", "Mountain", "Forest",
        "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp",
        "Snow-Covered Mountain", "Snow-Covered Forest", "Wastes"
    };

    /// <summary>
    /// Commander Spellbook API endpoint for bracket estimation.
    /// </summary>
    private const string BracketApiEndpoint = "https://backend.commanderspellbook.com/estimate-bracket";

    /// <summary>
    /// Initializes a new instance of the DeckValidationTools class.
    /// </summary>
    public DeckValidationTools(
        CardDatabaseContext dbContext,
        ILogger<DeckValidationTools> logger,
        IHttpClientFactory httpClientFactory)
    {
        _dbContext = dbContext;
        _logger = logger;
        _httpClient = httpClientFactory.CreateClient();
        _httpClient.Timeout = TimeSpan.FromSeconds(30);
    }

    /// <summary>
    /// Validates a Commander deck for color identity, format legality, and deck construction rules.
    /// </summary>
    /// <param name="commanderCardName">The exact name of the commander card.</param>
    /// <param name="deckList">A multi-line string where each line is formatted as "count cardName" (e.g., "1 Lightning Bolt").</param>
    /// <returns>JSON string containing validation results with isValid boolean and violations array.</returns>
    //[Description(
    //    "Validates a Commander deck for color identity, format legality, and deck construction rules. Returns validation results with detailed violation messages.")]
    public List<string> ValidateCommanderDeck(
        [Description("The exact name of the commander card")]
        string commanderCardName,
        [Description(
            "A multi-line string where each line represents a card in the 99-card deck, formatted as '<count> <cardName>' (e.g., '1 Lightning Bolt', '3 Island')")]
        string deckList)
    {
        var violations = new List<string>();
        _logger.LogInformation("Validating Commander deck with commander: {CommanderName}", commanderCardName);

        // Parse and validate commander
        var commander = _dbContext.Cards.FirstOrDefault(c => c.Name == commanderCardName);
        if (commander == null)
        {
            violations.Add($"Commander '{commanderCardName}' not found in database.");
            return violations;
        }

        // Validate commander is a valid commander
        var commanderViolations = ValidateCommanderCard(commander);
        violations.AddRange(commanderViolations);

        // Check commander format legality
        var commanderLegality = _dbContext.CardLegalities.FirstOrDefault(l => l.Uuid == commander.Uuid);
        if (commanderLegality == null || !IsLegalInCommander(commanderLegality))
        {
            violations.Add($"Commander '{commanderCardName}' is not legal in Commander format.");
        }

        // Get commander's color identity
        var commanderColorIdentity = ParseColorIdentity(commander.ColorIdentity);

        // Parse deck list
        var deckEntries = ParseDeckList(deckList);
        if (deckEntries.Count == 0)
        {
            violations.Add("Deck list is empty or could not be parsed.");
            return violations;
        }

        // Calculate total cards
        var totalCards = deckEntries.Sum(e => e.Count) + 1; // +1 for commander
        if (totalCards != 100)
        {
            violations.Add(
                $"Deck must contain exactly 100 cards (1 commander + 99 others). Current count: {totalCards}.");
        }

        // Track card counts for singleton validation
        var cardCounts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        // Validate each card in the deck
        foreach (var entry in deckEntries)
        {
            ValidateDeckCard(entry, commanderColorIdentity, cardCounts, violations);
        }

        // Check singleton violations
        foreach (var kvp in cardCounts)
        {
            if (kvp.Value > 1 && !BasicLandNames.Contains(kvp.Key))
            {
                violations.Add(
                    $"Singleton violation: '{kvp.Key}' appears {kvp.Value} times (maximum 1 copy allowed for non-basic lands).");
            }
        }

        _logger.LogInformation("Deck validation complete. Valid: {IsValid}, Violations: {ViolationCount}",
            violations.Count == 0, violations.Count);

        return violations;
    }

    /// <summary>
    /// Validates that all cards in a deck list match the commander's color identity constraints.
    /// </summary>
    /// <param name="commanderCardName">The exact name of the commander card.</param>
    /// <param name="deckList">A multi-line string where each line is formatted as "count cardName" (e.g., "1 Lightning Bolt").</param>
    /// <returns>A list of color identity violation messages. Returns an empty list if all cards are valid.</returns>
    public List<string> ValidateColorLegality(string commanderCardName, string deckList)
    {
        var violations = new List<string>();
        _logger.LogInformation("Validating color identity for deck with commander: {CommanderName}", commanderCardName);

        // Look up the commander card in the database
        var commander = _dbContext.Cards.FirstOrDefault(c => c.Name == commanderCardName);
        if (commander == null)
        {
            violations.Add($"Commander '{commanderCardName}' not found in database.");
            return violations;
        }

        // Parse the commander's color identity
        var commanderColorIdentity = ParseColorIdentity(commander.ColorIdentity);

        // Parse the deck list
        var deckEntries = ParseDeckList(deckList);
        if (deckEntries.Count == 0)
        {
            violations.Add("Deck list is empty or could not be parsed.");
            return violations;
        }

        // For each card in the deck, check if its color identity is a subset of the commander's color identity
        foreach (var entry in deckEntries)
        {
            var card = _dbContext.Cards.FirstOrDefault(c => c.Name == entry.CardName);
            if (card == null)
            {
                violations.Add($"Card '{entry.CardName}' not found in database.");
                continue;
            }

            var cardColorIdentity = ParseColorIdentity(card.ColorIdentity);

            // Check if card's color identity is a subset of commander's
            foreach (var color in cardColorIdentity)
            {
                if (!commanderColorIdentity.Contains(color))
                {
                    var commanderColors = commanderColorIdentity.Count > 0
                        ? string.Join(", ", commanderColorIdentity)
                        : "colorless";
                    violations.Add(
                        $"'{entry.CardName}' has color identity '{color}' which is not in commander's color identity ({commanderColors}).");
                    break; // Only report once per card
                }
            }
        }

        _logger.LogInformation("Color identity validation complete. Violations: {ViolationCount}", violations.Count);
        return violations;
    }

    /// <summary>
    /// Validates a Commander deck against Bracket 3 (High Power) constraints using the Commander Spellbook API.
    /// </summary>
    /// <param name="commanderCardName">The exact name of the commander card.</param>
    /// <param name="deckList">A multi-line string where each line is formatted as "count cardName" (e.g., "1 Lightning Bolt").</param>
    /// <returns>JSON string containing validation results with isValid boolean and violations array.</returns>
    //[McpServerTool(Name = "validateCommanderBracket3")]
    //[Description(
    //    "Validates a Commander deck against Bracket 3 (High Power) constraints. Checks for Game Changers (max 3), Mass Land Denial (none allowed), and early-game 2-card infinite combos (none allowed). Uses the Commander Spellbook API for bracket estimation.")]
    public async Task<string> ValidateCommanderBracket3(
        [Description("The exact name of the commander card")]
        string commanderCardName,
        [Description(
            "A multi-line string where each line represents a card in the 99-card deck, formatted as '<count> <cardName>' (e.g., '1 Lightning Bolt', '3 Island')")]
        string deckList)
    {
        var violations = new List<string>();

        try
        {
            _logger.LogInformation(
                "Validating Commander deck against Bracket 3 constraints. Commander: {CommanderName}",
                commanderCardName);

            // Parse deck into API format
            var (mainCards, commanders) = ParseDeckForBracketApi(commanderCardName, deckList);

            if (commanders.Count == 0)
            {
                violations.Add($"Commander '{commanderCardName}' could not be added to the validation request.");
            }

            var payload = new
            {
                main = mainCards,
                commanders = commanders
            };

            // Call Commander Spellbook API with retry logic
            BracketResponse? bracketResponse;
            try
            {
                bracketResponse = await CallBracketApiWithRetryAsync(payload);
            }
            catch (BracketValidationApiException ex)
            {
                _logger.LogError(ex, "Bracket validation API failed after all retries");
                return JsonSerializer.Serialize(new
                {
                    isValid = false,
                    violations = new[] { $"Bracket validation API failed: {ex.Message}" },
                    error = ex.Message
                }, new JsonSerializerOptions { WriteIndented = true });
            }

            // Validate Bracket 3 constraints
            var bracketResult = ValidateBracket3Constraints(bracketResponse);
            violations.AddRange(bracketResult.Violations);
            violations.AddRange(ValidateCommanderDeck(commanderCardName, deckList));

            _logger.LogInformation(
                "Bracket 3 validation complete. Valid: {IsValid}, Bracket: {BracketLabel}, Violations: {ViolationCount}",
                bracketResult.IsValidForBracket3, bracketResult.BracketLabel, violations.Count);

            return JsonSerializer.Serialize(new
            {
                isValid = bracketResult.IsValidForBracket3,
                bracketTag = bracketResult.BracketTag,
                bracketLabel = bracketResult.BracketLabel,
                violations
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error validating Commander deck against Bracket 3");
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Calls the Commander Spellbook bracket API with exponential backoff retry logic.
    /// </summary>
    private async Task<BracketResponse> CallBracketApiWithRetryAsync(object payload)
    {
        const int maxRetries = 3;
        var retryDelays = new[] { TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(4), TimeSpan.FromSeconds(8) };
        Exception? lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++)
        {
            try
            {
                _logger.LogInformation("Bracket validation API call attempt {Attempt}/{MaxRetries}", attempt + 1,
                    maxRetries);

                var response = await _httpClient.PostAsJsonAsync(BracketApiEndpoint, payload);
                response.EnsureSuccessStatusCode();

                var data = await response.Content.ReadFromJsonAsync<BracketResponse>();
                if (data == null)
                {
                    throw new InvalidOperationException("Failed to deserialize bracket response - null response");
                }

                return data;
            }
            catch (OperationCanceledException)
            {
                throw; // Don't retry on cancellation
            }
            catch (Exception ex)
            {
                lastException = ex;
                _logger.LogWarning(ex, "Bracket validation API call failed on attempt {Attempt}/{MaxRetries}",
                    attempt + 1, maxRetries);

                if (attempt < maxRetries - 1)
                {
                    _logger.LogInformation("Retrying bracket validation in {Delay} seconds...",
                        retryDelays[attempt].TotalSeconds);
                    await Task.Delay(retryDelays[attempt]);
                }
            }
        }

        throw new BracketValidationApiException(
            $"Bracket validation API failed after {maxRetries} attempts. Last error: {lastException?.Message}",
            lastException);
    }

    /// <summary>
    /// Validates the bracket response against Bracket 3 constraints.
    /// </summary>
    private static BracketValidationResult ValidateBracket3Constraints(BracketResponse data)
    {
        var violations = new List<string>();

        // Game Changers: max 3 allowed
        if (data.GameChangerCards.Count > 3)
        {
            var cardNames = string.Join(", ", data.GameChangerCards.Select(c => c.Name));
            violations.Add($"Too many Game Changers ({data.GameChangerCards.Count}/3 max): {cardNames}");
        }

        // Mass Land Denial: none allowed
        if (data.MassLandDenialCards.Count > 0)
        {
            var cardNames = string.Join(", ", data.MassLandDenialCards.Select(c => c.Name));
            violations.Add($"Mass Land Denial detected (0 allowed): {cardNames}");
        }

        // Definitely early-game 2-card infinite combos: none allowed
        if (data.DefinitelyEarlyGameTwoCardCombos.Count > 0)
        {
            var comboNames = data.DefinitelyEarlyGameTwoCardCombos
                .Select(c => string.Join(" + ", c.Uses.Select(u => u.Card.Name)));
            violations.Add(
                $"Definitely early-game 2-card infinite combos (0 allowed): {string.Join("; ", comboNames)}");
        }

        // Arguably early-game 2-card infinite combos: none allowed
        /*if (data.ArguablyEarlyGameTwoCardCombos.Count > 0)
        {
            var comboNames = data.ArguablyEarlyGameTwoCardCombos
                .Select(c => string.Join(" + ", c.Uses.Select(u => u.Card.Name)));
            violations.Add($"Arguably early-game 2-card infinite combos (0 allowed): {string.Join("; ", comboNames)}");
        }*/ // Only include strictest.

        return new BracketValidationResult
        {
            IsValidForBracket3 = violations.Count == 0,
            BracketTag = data.BracketTag,
            BracketLabel = MapBracketTag(data.BracketTag),
            Violations = violations
        };
    }

    /// <summary>
    /// Parses a deck list string into the format required by Commander Spellbook API.
    /// </summary>
    private static (List<object> mainCards, List<object> commanders) ParseDeckForBracketApi(string commanderName,
        string deckList)
    {
        var mainCards = new List<object>();
        var commanders = new List<object>();
        var cardPattern = new Regex(@"^\s*(\d+)x?\s+(.+?)\s*$");

        // Add commander
        commanders.Add(new { card = commanderName, quantity = 1 });

        foreach (var line in deckList.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmedLine = line.Trim();

            // Skip metadata, comments, section headers
            if (string.IsNullOrWhiteSpace(trimmedLine) ||
                trimmedLine.StartsWith("//") ||
                trimmedLine.StartsWith("#") ||
                trimmedLine.StartsWith("[") ||
                trimmedLine.StartsWith("Name="))
            {
                continue;
            }

            var match = cardPattern.Match(trimmedLine);
            if (match.Success)
            {
                var quantity = int.Parse(match.Groups[1].Value);
                var cardName = match.Groups[2].Value.Trim();

                // Check if this is a commander marker and skip (we already added the commander)
                if (cardName.Contains("[Commander]"))
                {
                    continue;
                }

                mainCards.Add(new { card = cardName, quantity = quantity });
            }
        }

        return (mainCards, commanders);
    }

    /// <summary>
    /// Maps Commander Spellbook bracket tags to user-friendly labels.
    /// </summary>
    private static string MapBracketTag(string bracketTag)
    {
        return bracketTag switch
        {
            "R" => "Ruthless (cEDH / Bracket 4)",
            "P" => "Powerful (High Power / Bracket 3)",
            "S" => "Spicy (Synergy / Bracket 3)",
            "O" => "Oddball (Chaos/Theme / Bracket 2)",
            "C" => "Casual (Mid Power / Bracket 2)",
            "PA" => "Precon Appropriate (Low Power / Bracket 1)",
            _ => $"Unknown ({bracketTag})"
        };
    }

    private List<string> ValidateCommanderCard(Card commander)
    {
        var violations = new List<string>();

        // Check if card can be a commander using leadershipSkills field
        var leadershipSkills = commander.LeadershipSkills?.ToLowerInvariant() ?? "";
        var canBeCommander = leadershipSkills.Contains("commander");

        if (!canBeCommander)
        {
            // Fallback: Check if it's a legendary creature or has "can be your commander" text
            var isLegendary = commander.Supertypes?.Contains("Legendary", StringComparison.OrdinalIgnoreCase) ?? false;
            var isCreature = commander.Types?.Contains("Creature", StringComparison.OrdinalIgnoreCase) ?? false;
            var hasCommanderText =
                commander.Text?.Contains("can be your commander", StringComparison.OrdinalIgnoreCase) ?? false;

            if (!((isLegendary && isCreature) || hasCommanderText))
            {
                violations.Add(
                    $"'{commander.Name}' cannot be a commander. Must be a legendary creature or have 'can be your commander' ability.");
            }
        }

        return violations;
    }


    private void ValidateDeckCard(
        DeckEntry entry,
        HashSet<char> commanderColorIdentity,
        Dictionary<string, int> cardCounts,
        List<string> violations)
    {
        var card = _dbContext.Cards.FirstOrDefault(c => c.Name == entry.CardName);
        if (card == null)
        {
            violations.Add($"Card '{entry.CardName}' not found in database.");
            return;
        }

        // Track card count for singleton validation
        if (!cardCounts.TryAdd(entry.CardName, entry.Count))
        {
            cardCounts[entry.CardName] += entry.Count;
        }

        // Check format legality
        var legality = _dbContext.CardLegalities.FirstOrDefault(l => l.Uuid == card.Uuid);
        if (legality == null || !IsLegalInCommander(legality))
        {
            violations.Add($"'{entry.CardName}' is not legal in Commander format.");
        }

        // Check color identity - use the database's ColorIdentity field which already accounts for all mana symbols
        var cardColorIdentity = ParseColorIdentity(card.ColorIdentity);

        // Check if card's color identity is a subset of commander's
        foreach (var color in cardColorIdentity)
        {
            if (!commanderColorIdentity.Contains(color))
            {
                var commanderColors = commanderColorIdentity.Count > 0
                    ? string.Join(", ", commanderColorIdentity)
                    : "colorless";
                violations.Add(
                    $"'{entry.CardName}' has color identity '{color}' which is not in commander's color identity ({commanderColors}).");
                break; // Only report once per card
            }
        }
    }

    private static HashSet<char> ParseColorIdentity(string? colorIdentity)
    {
        var colors = new HashSet<char>();
        if (string.IsNullOrWhiteSpace(colorIdentity))
        {
            return colors;
        }

        // Color identity is stored as comma-separated values like "W,U,B"
        foreach (var part in colorIdentity.Split(',', StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmed = part.Trim().ToUpperInvariant();
            if (trimmed.Length == 1 && "WUBRG".Contains(trimmed[0]))
            {
                colors.Add(trimmed[0]);
            }
        }

        return colors;
    }

    private static bool IsLegalInCommander(CardLegalities legality)
    {
        var status = legality.Commander;
        return status != null &&
               (status.Equals("Legal", StringComparison.OrdinalIgnoreCase) ||
                status.Equals("Restricted", StringComparison.OrdinalIgnoreCase));
    }

    private static List<DeckEntry> ParseDeckList(string deckList)
    {
        var entries = new List<DeckEntry>();
        if (string.IsNullOrWhiteSpace(deckList))
        {
            return entries;
        }

        var lines = deckList.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            if (string.IsNullOrWhiteSpace(trimmed))
            {
                continue;
            }

            // Parse format: "<count> <cardName>" or "<count>x <cardName>"
            var match = Regex.Match(trimmed, @"^(\d+)x?\s+(.+)$", RegexOptions.IgnoreCase);
            if (match.Success && int.TryParse(match.Groups[1].Value, out var count))
            {
                entries.Add(new DeckEntry
                {
                    Count = count,
                    CardName = match.Groups[2].Value.Trim()
                });
            }
        }

        return entries;
    }

    private static string CreateValidationResult(bool isValid, List<string> violations)
    {
        return JsonSerializer.Serialize(new
        {
            isValid,
            violations
        }, new JsonSerializerOptions { WriteIndented = true });
    }

    private class DeckEntry
    {
        public int Count { get; set; }
        public string CardName { get; set; } = string.Empty;
    }

    /// <summary>
    /// Result of Bracket 3 validation.
    /// </summary>
    private class BracketValidationResult
    {
        public bool IsValidForBracket3 { get; set; }
        public string BracketTag { get; set; } = string.Empty;
        public string BracketLabel { get; set; } = string.Empty;
        public List<string> Violations { get; set; } = new();
    }

    /// <summary>
    /// Exception thrown when the bracket validation API fails.
    /// </summary>
    private class BracketValidationApiException : Exception
    {
        public BracketValidationApiException(string message, Exception? innerException = null)
            : base(message, innerException)
        {
        }
    }

    #region Commander Spellbook API Response Models

    /// <summary>
    /// Response from the Commander Spellbook bracket estimation API.
    /// </summary>
    private class BracketResponse
    {
        [JsonPropertyName("bracketTag")] public string BracketTag { get; set; } = string.Empty;

        [JsonPropertyName("gameChangerCards")] public List<BracketCard> GameChangerCards { get; set; } = new();

        [JsonPropertyName("massLandDenialCards")]
        public List<BracketCard> MassLandDenialCards { get; set; } = new();

        [JsonPropertyName("definitelyEarlyGameTwoCardCombos")]
        public List<BracketCombo> DefinitelyEarlyGameTwoCardCombos { get; set; } = new();

        [JsonPropertyName("arguablyEarlyGameTwoCardCombos")]
        public List<BracketCombo> ArguablyEarlyGameTwoCardCombos { get; set; } = new();
    }

    /// <summary>
    /// Card information from the bracket API response.
    /// </summary>
    private class BracketCard
    {
        [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    }

    /// <summary>
    /// Combo information from the bracket API response.
    /// </summary>
    private class BracketCombo
    {
        [JsonPropertyName("uses")] public List<BracketComboUse> Uses { get; set; } = new();
    }

    /// <summary>
    /// Card usage in a combo from the bracket API response.
    /// </summary>
    private class BracketComboUse
    {
        [JsonPropertyName("card")] public BracketCard Card { get; set; } = new();
    }

    #endregion
}