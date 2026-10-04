using System.ComponentModel;
using System.Text.Json;
using System.Text.RegularExpressions;
using CardDatabaseMCPServer.Data;
using CardDatabaseMCPServer.Models;
using ModelContextProtocol.Server;

namespace CardDatabaseMCPServer.Tools;

/// <summary>
/// MCP tools for querying Magic: The Gathering card data from the AllPrintings.sqlite database.
/// </summary>
[McpServerToolType]
public class CardDatabaseTools
{
    private readonly CardDatabaseContext _dbContext;
    private readonly PriceDatabaseContext _priceDbContext;
    private readonly CardFrequencyContext _frequencyDbContext;
    private readonly ILogger<CardDatabaseTools> _logger;

    /// <summary>
    /// Initializes a new instance of the CardDatabaseTools class.
    /// </summary>
    public CardDatabaseTools(
        CardDatabaseContext dbContext,
        PriceDatabaseContext priceDbContext,
        CardFrequencyContext frequencyDbContext,
        ILogger<CardDatabaseTools> logger)
    {
        _dbContext = dbContext;
        _priceDbContext = priceDbContext;
        _frequencyDbContext = frequencyDbContext;
        _logger = logger;
    }

    /// <summary>
    /// Searches for cards using multiple optional filter criteria.
    /// </summary>
    /// <param name="legalInFormat">The game format to filter cards by legality. Only cards that are legal or restricted in this format will be returned.</param>
    /// <param name="cardName">Partial or full card name match.</param>
    /// <param name="manaValue">Exact mana value match.</param>
    /// <param name="manaCost">Exact mana cost string match.</param>
    /// <param name="originalText">Search in original card text.</param>
    /// <param name="keywords">Search for specific keywords.</param>
    /// <param name="availability">Filter by availability.</param>
    /// <param name="colors">Filter by color(s).</param>
    /// <param name="rarity">Filter by rarity.</param>
    /// <param name="types">Filter by card types.</param>
    /// <param name="supertypes">Filter by supertypes.</param>
    /// <param name="subtypes">Filter by subtypes.</param>
    /// <param name="text">Search in card text.</param>
    /// <param name="power">Filter by power value.</param>
    /// <param name="toughness">Filter by toughness value.</param>
    /// <param name="rulings">Search in card rulings text.</param>
    /// <param name="releasedAfter">Filter to only include cards released on or after this date.</param>
    /// <param name="maxResults">Maximum number of results to return.</param>
    /// <param name="sortByField">Field to sort results by.</param>
    /// <param name="sortDirection">Sort direction.</param>
    /// <param name="maxSets">Maximum number of sets a card can be printed in.</param>
    /// <param name="maxCardUseFrequency">Maximum card use frequency.</param>
    /// <returns>JSON string containing matching cards with their details.</returns>
    [McpServerTool(Name = "searchCards")]
    [Description(
        "Searches for cards using multiple optional filter criteria. Requires specifying a game format to filter by legality. Returns a list of matching cards with their details. Supports sorting by various fields including manaValue, mtgoPrice, paperPrice, rarity, power, toughness, loyalty, name, originalReleaseDate, and useFrequency.")]
    public string SearchCards(
        [Description(
            "The game format to filter cards by legality. Only cards that are legal or restricted in this format will be returned. Required parameter.")]
        MtgGameFormat legalInFormat,
        [Description("Partial or full card name match")]
        string? cardName = null,
        [Description("Exact mana value match")]
        float? manaValue = null,
        [Description("Exact mana cost string match (e.g., '{2}{U}{U}')")]
        string? manaCost = null,
        [Description("Search in original card text (partial match)")]
        string? originalText = null,
        [Description("Search for specific keywords (partial match)")]
        string? keywords = null,
        [Description("Filter by color(s)")] string? colors = null,
        [Description("Filter by rarity (e.g., 'common', 'uncommon', 'rare', 'mythic')")]
        string? rarity = null,
        [Description("Filter by card types (partial match)")]
        string? types = null,
        [Description("Filter by supertypes (partial match)")]
        string? supertypes = null,
        [Description("Filter by subtypes (partial match)")]
        string? subtypes = null,
        [Description("Search in card text (partial match)")]
        string? text = null,
        [Description(
            "Regex pattern to match against card text fields (Text and OriginalText). Uses case-insensitive matching. Example: 'destroy.*creature' to find cards with 'destroy' followed by 'creature'.")]
        string? searchCardTextMatchingRegex = null,
        [Description("Filter by power value")] string? power = null,
        [Description("Filter by toughness value")]
        string? toughness = null,
        [Description("Search in card rulings text (partial match)")]
        string? rulings = null,
        [Description(
            "Filter to only include cards released on or after this date (ISO 8601 format, e.g., '2020-01-15')")]
        string? releasedAfter = null,
        
        [Description(
            "Field to sort results by. Supported values: 'manaValue', 'mtgoPrice', 'paperPrice', 'rarity', 'power', 'toughness', 'loyalty', 'name', 'originalReleaseDate', 'useFrequency'. If not specified, no sorting is applied.")]
        string? sortByField = null,
        [Description(
            "Sort direction. Supported values: 'asc'/'ascending' for ascending order (default), 'desc'/'descending' for descending order.")]
        string? sortDirection = null,
        [Description(
            "Maximum number of sets a card can be printed in. Cards printed in more sets than this value will be filtered out. The number of sets is determined by counting commas in the 'printings' field and adding 1.")]
        int? maxSets = null,
        [Description(
            "Maximum card use frequency. Cards with a use frequency higher than this value will be filtered out. Use this to find less commonly played cards.")]
        long? maxCardUseFrequency = null,
            [Description("Filter by availability (defaults to 'mtgo' if not specified)")]
    string? availability = "mtgo",
        [Description("Maximum number of results to return (default: 50)")]
    int maxResults = 50)
    {
        try
        {
            _logger.LogInformation("Searching cards with filters");

            // log all parameters on a single log line.
            _logger.LogInformation(
                "legalInFormat: {legalInFormat}, cardName: {cardName}, manaValue: {manaValue}, manaCost: {manaCost}, originalText: {originalText}, keywords: {keywords}, availability: {availability}, colors: {colors}, rarity: {rarity}, types: {types}, supertypes: {supertypes}, subtypes: {subtypes}, text: {text}, searchCardTextMatchingRegex: {searchCardTextMatchingRegex}, power: {power}, toughness: {toughness}, rulings: {rulings}, releasedAfter: {releasedAfter}, maxResults: {maxResults}, sortByField: {sortByField}, sortDirection: {sortDirection}, maxSets: {maxSets}, maxCardUseFrequency: {maxCardUseFrequency}",
                legalInFormat, cardName, manaValue, manaCost, originalText, keywords, availability, colors, rarity,
                types, supertypes, subtypes, text, searchCardTextMatchingRegex, power, toughness, rulings,
                releasedAfter, maxResults, sortByField, sortDirection, maxSets, maxCardUseFrequency);
            
            
            // Start with base query
            var query = _dbContext.Cards.AsQueryable();

            // Apply filters
            if (!string.IsNullOrWhiteSpace(cardName))
            {
                var searchName = cardName.ToLowerInvariant();
                query = query.Where(c => c.Name != null && c.Name.ToLower().Contains(searchName));
            }

            if (manaValue.HasValue)
            {
                query = query.Where(c => c.ManaValue == manaValue.Value);
            }

            if (!string.IsNullOrWhiteSpace(manaCost))
            {
                query = query.Where(c => c.ManaCost == manaCost);
            }

            if (!string.IsNullOrWhiteSpace(originalText))
            {
                var searchText = originalText.ToLowerInvariant();
                query = query.Where(c => c.OriginalText != null && c.OriginalText.ToLower().Contains(searchText));
            }

            if (!string.IsNullOrWhiteSpace(keywords))
            {
                var searchKeywords = keywords.ToLowerInvariant();
                query = query.Where(c => c.Keywords != null && c.Keywords.ToLower().Contains(searchKeywords));
            }

            if (!string.IsNullOrWhiteSpace(availability))
            {
                var searchAvailability = availability.ToLowerInvariant();
                query =
                    query.Where(c => c.Availability != null && c.Availability.ToLower().Contains(searchAvailability));
            }

            if (!string.IsNullOrWhiteSpace(colors))
            {
                var searchColors = colors.ToLowerInvariant();
                query = query.Where(c => c.Colors != null && c.Colors.ToLower().Contains(searchColors));
            }

            if (!string.IsNullOrWhiteSpace(rarity))
            {
                var searchRarity = rarity.ToLowerInvariant();
                query = query.Where(c => c.Rarity != null && c.Rarity.ToLower() == searchRarity);
            }

            if (!string.IsNullOrWhiteSpace(types))
            {
                var searchTypes = types.ToLowerInvariant();
                query = query.Where(c => c.Types != null && c.Types.ToLower().Contains(searchTypes));
            }

            if (!string.IsNullOrWhiteSpace(supertypes))
            {
                var searchSupertypes = supertypes.ToLowerInvariant();
                query = query.Where(c => c.Supertypes != null && c.Supertypes.ToLower().Contains(searchSupertypes));
            }

            if (!string.IsNullOrWhiteSpace(subtypes))
            {
                var searchSubtypes = subtypes.ToLowerInvariant();
                query = query.Where(c => c.Subtypes != null && c.Subtypes.ToLower().Contains(searchSubtypes));
            }

            if (!string.IsNullOrWhiteSpace(text))
            {
                var searchText = text.ToLowerInvariant();
                query = query.Where(c => c.Text != null && c.Text.ToLower().Contains(searchText));
            }

            if (!string.IsNullOrWhiteSpace(power))
            {
                query = query.Where(c => c.Power == power);
            }

            if (!string.IsNullOrWhiteSpace(toughness))
            {
                query = query.Where(c => c.Toughness == toughness);
            }

            // Filter by release date if specified (originalReleaseDate is stored as YYYY-MM-DD string)
            if (!string.IsNullOrWhiteSpace(releasedAfter))
            {
                // String comparison works for ISO 8601 dates (YYYY-MM-DD format)
                query = query.Where(c => c.OriginalReleaseDate != null &&
                                         string.Compare(c.OriginalReleaseDate, releasedAfter,
                                             StringComparison.Ordinal) >= 0);
            }

            // Get initial results
            var cards = query.Take(maxResults * 2).ToList();

            // Filter by format legality (mandatory - requires join with cardLegalities)
            var legalUuids = GetLegalCardUuids(legalInFormat);
            cards = cards.Where(c => legalUuids.Contains(c.Uuid)).ToList();

            // Filter by rulings if specified (requires join with cardRulings)
            if (!string.IsNullOrWhiteSpace(rulings))
            {
                var searchRulings = rulings.ToLowerInvariant();
                var rulingsUuids = GetCardUuidsWithRulings(searchRulings);
                cards = cards.Where(c => rulingsUuids.Contains(c.Uuid)).ToList();
            }

            // Filter by maximum number of sets if specified
            if (maxSets.HasValue)
            {
                cards = cards.Where(c =>
                {
                    // Count commas in printings field and add 1 to get number of sets
                    var printings = c.Printings ?? string.Empty;
                    var commaCount = printings.Count(ch => ch == ',');
                    var setCount = commaCount + 1;
                    return setCount <= maxSets.Value;
                }).ToList();
            }

            // Filter by regex pattern matching on card text fields
            if (!string.IsNullOrWhiteSpace(searchCardTextMatchingRegex))
            {
                try
                {
                    var regex = new Regex(searchCardTextMatchingRegex, RegexOptions.IgnoreCase | RegexOptions.Compiled,
                        TimeSpan.FromSeconds(1));
                    cards = cards.Where(c =>
                    {
                        // Match against Text field
                        if (!string.IsNullOrEmpty(c.Text) && regex.IsMatch(c.Text))
                            return true;
                        // Match against OriginalText field
                        if (!string.IsNullOrEmpty(c.OriginalText) && regex.IsMatch(c.OriginalText))
                            return true;
                        return false;
                    }).ToList();
                }
                catch (ArgumentException ex)
                {
                    _logger.LogWarning(ex, "Invalid regex pattern provided: {Pattern}", searchCardTextMatchingRegex);
                    // Invalid regex pattern - return no matches for this filter
                    cards = [];
                }
                catch (RegexMatchTimeoutException ex)
                {
                    _logger.LogWarning(ex, "Regex pattern timed out: {Pattern}", searchCardTextMatchingRegex);
                    // Regex timed out - return no matches for this filter
                    cards = [];
                }
            }

            // Convert to CardDetails (needed for sorting by price and frequency)
            var results = cards.Select(card =>
            {
                var details = CardDetails.FromCard(card);

                // Get legalities
                var legalities = _dbContext.CardLegalities
                    .FirstOrDefault(l => l.Uuid == card.Uuid);
                if (legalities != null)
                {
                    details.Legalities = CardLegalitiesInfo.FromCardLegalities(legalities);
                }

                // Get prices
                var (paperPrice, mtgoPrice) = _priceDbContext.GetPrices(card.Uuid);
                details.PaperPrice = paperPrice;
                details.MtgoPrice = mtgoPrice;

                // Get card use frequency (UUID-first lookup with name-based fallback)
                details.CardUseFrequency = _frequencyDbContext.GetUseCountByUuid(card.Uuid);
                if (details.CardUseFrequency == 0)
                {
                    details.CardUseFrequency = _frequencyDbContext.GetUseCount(card.Name ?? string.Empty);
                }

                // Get time-windowed card use frequency (UUID-first lookup with name-based fallback)
                details.CardUseFrequencyLast30Days = _frequencyDbContext.GetUseCountLast30DaysByUuid(card.Uuid);
                if (details.CardUseFrequencyLast30Days == 0)
                {
                    details.CardUseFrequencyLast30Days = _frequencyDbContext.GetUseCountLast30Days(card.Name ?? string.Empty);
                }

                details.CardUseFrequencyLast90Days = _frequencyDbContext.GetUseCountLast90DaysByUuid(card.Uuid);
                if (details.CardUseFrequencyLast90Days == 0)
                {
                    details.CardUseFrequencyLast90Days = _frequencyDbContext.GetUseCountLast90Days(card.Name ?? string.Empty);
                }

                return details;
            }).ToList();

            // Filter by maximum card use frequency if specified
            if (maxCardUseFrequency.HasValue)
            {
                results = results.Where(c => c.CardUseFrequency <= maxCardUseFrequency.Value).ToList();
            }

            // Apply sorting if specified, otherwise randomize order
            if (!string.IsNullOrWhiteSpace(sortByField))
            {
                var isDescending = !string.IsNullOrWhiteSpace(sortDirection) &&
                                   (sortDirection.Equals("desc", StringComparison.OrdinalIgnoreCase) ||
                                    sortDirection.Equals("descending", StringComparison.OrdinalIgnoreCase));

                results = ApplySorting(results, sortByField.ToLowerInvariant(), isDescending);
            }
            else
            {
                // Randomize order when no sort order is specified
                results = results.OrderBy(_ => Random.Shared.Next()).ToList();
            }

            // Take final results after sorting
            results = results.Take(maxResults).ToList();

            _logger.LogInformation("Search returned {Count} cards", results.Count);
            return JsonSerializer.Serialize(new
            {
                count = results.Count,
                cards = results
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error searching cards");
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Gets UUIDs of cards that are legal in the specified format.
    /// </summary>
    /// <param name="format">The game format to check legality for.</param>
    /// <returns>A set of card UUIDs that are legal or restricted in the specified format.</returns>
    private HashSet<string> GetLegalCardUuids(MtgGameFormat format)
    {
        var legalStatuses = new[] { "Legal", "Restricted" };

        // Query based on format
        var legalities = _dbContext.CardLegalities.ToList();

        var legalUuids = format switch
        {
            MtgGameFormat.Standard => legalities.Where(l => legalStatuses.Contains(l.Standard)).Select(l => l.Uuid),
            MtgGameFormat.Modern => legalities.Where(l => legalStatuses.Contains(l.Modern)).Select(l => l.Uuid),
            MtgGameFormat.Legacy => legalities.Where(l => legalStatuses.Contains(l.Legacy)).Select(l => l.Uuid),
            MtgGameFormat.Vintage => legalities.Where(l => legalStatuses.Contains(l.Vintage)).Select(l => l.Uuid),
            MtgGameFormat.Commander => legalities.Where(l => legalStatuses.Contains(l.Commander)).Select(l => l.Uuid),
            MtgGameFormat.Pioneer => legalities.Where(l => legalStatuses.Contains(l.Pioneer)).Select(l => l.Uuid),
            MtgGameFormat.Pauper => legalities.Where(l => legalStatuses.Contains(l.Pauper)).Select(l => l.Uuid),
            MtgGameFormat.Historic => legalities.Where(l => legalStatuses.Contains(l.Historic)).Select(l => l.Uuid),
            MtgGameFormat.Alchemy => legalities.Where(l => legalStatuses.Contains(l.Alchemy)).Select(l => l.Uuid),
            MtgGameFormat.Brawl => legalities.Where(l => legalStatuses.Contains(l.Brawl)).Select(l => l.Uuid),
            MtgGameFormat.Timeless => legalities.Where(l => legalStatuses.Contains(l.Timeless)).Select(l => l.Uuid),
            MtgGameFormat.Oathbreaker => legalities.Where(l => legalStatuses.Contains(l.Oathbreaker))
                .Select(l => l.Uuid),
            _ => throw new ArgumentOutOfRangeException(nameof(format), format, "Unsupported game format")
        };

        return legalUuids.Where(u => u != null).Select(u => u!).ToHashSet();
    }

    /// <summary>
    /// Gets UUIDs of cards that have rulings containing the specified text.
    /// </summary>
    private HashSet<string> GetCardUuidsWithRulings(string searchText)
    {
        return _dbContext.CardRulings
            .Where(r => r.Text != null && r.Text.ToLower().Contains(searchText))
            .Select(r => r.Uuid)
            .ToHashSet();
    }

    /// <summary>
    /// Applies sorting to the card results based on the specified field and direction.
    /// </summary>
    /// <param name="cards">The list of cards to sort.</param>
    /// <param name="sortField">The field to sort by (lowercase).</param>
    /// <param name="descending">Whether to sort in descending order.</param>
    /// <returns>The sorted list of cards.</returns>
    private List<CardDetails> ApplySorting(List<CardDetails> cards, string sortField, bool descending)
    {
        IEnumerable<CardDetails> sorted = sortField switch
        {
            "manavalue" => descending
                ? cards.OrderByDescending(c => c.ManaValue ?? float.MaxValue)
                : cards.OrderBy(c => c.ManaValue ?? float.MaxValue),

            "mtgoprice" => descending
                ? cards.OrderByDescending(c => c.MtgoPrice >= 0 ? c.MtgoPrice : double.MinValue)
                : cards.OrderBy(c => c.MtgoPrice >= 0 ? c.MtgoPrice : double.MaxValue),

            "paperprice" => descending
                ? cards.OrderByDescending(c => c.PaperPrice >= 0 ? c.PaperPrice : double.MinValue)
                : cards.OrderBy(c => c.PaperPrice >= 0 ? c.PaperPrice : double.MaxValue),

            "rarity" => descending
                ? cards.OrderByDescending(c => ConvertRarityToLevel(c.Rarity))
                : cards.OrderBy(c => ConvertRarityToLevel(c.Rarity)),

            "power" => descending
                ? cards.OrderByDescending(c => ParseNumericValue(c.Power))
                : cards.OrderBy(c => ParseNumericValue(c.Power)),

            "toughness" => descending
                ? cards.OrderByDescending(c => ParseNumericValue(c.Toughness))
                : cards.OrderBy(c => ParseNumericValue(c.Toughness)),

            "loyalty" => descending
                ? cards.OrderByDescending(c => ParseNumericValue(c.Loyalty))
                : cards.OrderBy(c => ParseNumericValue(c.Loyalty)),

            "name" => descending
                ? cards.OrderByDescending(c => c.Name ?? string.Empty)
                : cards.OrderBy(c => c.Name ?? string.Empty),

            "originalreleasedate" => descending
                ? cards.OrderByDescending(c => c.OriginalReleaseDate ?? string.Empty)
                : cards.OrderBy(c => c.OriginalReleaseDate ?? string.Empty),

            "usefrequency" or "frequency" or "cardusefrequency" => descending
                ? cards.OrderByDescending(c => c.CardUseFrequency)
                : cards.OrderBy(c => c.CardUseFrequency),

            _ => cards // No sorting for unknown fields
        };

        return sorted.ToList();
    }

    /// <summary>
    /// Converts a rarity string to a RarityLevel enum value for sorting.
    /// </summary>
    /// <param name="rarity">The rarity string (e.g., "common", "uncommon", "rare", "mythic").</param>
    /// <returns>The corresponding RarityLevel value, or -1 for unknown rarities.</returns>
    private static int ConvertRarityToLevel(string? rarity)
    {
        if (string.IsNullOrWhiteSpace(rarity))
            return -1;

        return rarity.ToLowerInvariant() switch
        {
            "common" => (int)RarityLevel.Common,
            "uncommon" => (int)RarityLevel.Uncommon,
            "rare" => (int)RarityLevel.Rare,
            "mythic" => (int)RarityLevel.Mythic,
            _ => -1 // Unknown rarity sorts before common
        };
    }

    /// <summary>
    /// Parses a numeric value from a string, handling special cases like "*" or "X".
    /// </summary>
    /// <param name="value">The string value to parse (e.g., "3", "*", "X", "1+*").</param>
    /// <returns>The parsed numeric value, or null if the value cannot be parsed.</returns>
    private static float? ParseNumericValue(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        // Try to parse as a simple number
        if (float.TryParse(value, out var numericValue))
            return numericValue;

        // Handle special cases - these sort after numeric values
        // "*" typically means variable, "X" means depends on mana spent
        return value.ToUpperInvariant() switch
        {
            "*" => float.MaxValue - 2,
            "X" => float.MaxValue - 1,
            _ => float.MaxValue // Unknown values sort last
        };
    }
}