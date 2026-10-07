using System.Collections.Concurrent;
using System.ComponentModel;
using System.Text.Json;
using System.Text.RegularExpressions;
using CardDatabaseMCPServer.Data;
using CardDatabaseMCPServer.Models;
using ModelContextProtocol.Server;

namespace CardDatabaseMCPServer.Tools;

/// <summary>
/// MCP tools for stateful deck building functionality.
/// Maintains multiple in-memory deck sessions for building Commander decks.
/// </summary>
[McpServerToolType]
public class DeckBuilderTools
{
    private readonly CardDatabaseContext _dbContext;
    private readonly DeckSessionContext _deckSessionContext;
    private readonly ILogger<DeckBuilderTools> _logger;

    // In-memory deck sessions storage (cache layer over SQLite persistence)
    public static readonly ConcurrentDictionary<int, DeckSession> _deckSessions = new();
    private static readonly object _sessionLock = new();
    private static int _nextDeckId = -99;
    private static bool _sessionsLoaded;

    // Deck ID range constants
    private const int MinDeckId = -99;
    private const int MaxDeckId = 999;

    // Basic land names that are exempt from singleton rule
    private static readonly HashSet<string> BasicLandNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "Plains", "Island", "Swamp", "Mountain", "Forest",
        "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp",
        "Snow-Covered Mountain", "Snow-Covered Forest", "Wastes"
    };

    /// <summary>
    /// Initializes a new instance of the DeckBuilderTools class.
    /// </summary>
    public DeckBuilderTools(
        CardDatabaseContext dbContext,
        DeckSessionContext deckSessionContext,
        ILogger<DeckBuilderTools> logger)
    {
        _dbContext = dbContext;
        _deckSessionContext = deckSessionContext;
        _logger = logger;

        LoadPersistedSessions();
    }

    /// <summary>
    /// Loads all persisted deck sessions from SQLite into the in-memory cache on first initialization.
    /// Thread-safe: only executes once across all instances.
    /// </summary>
    private void LoadPersistedSessions()
    {
        if (_sessionsLoaded) return;

        lock (_sessionLock)
        {
            if (_sessionsLoaded) return;

            try
            {
                var sessions = _deckSessionContext.LoadAllSessions();
                foreach (var (id, session) in sessions)
                {
                    _deckSessions[id] = session;
                }

                _logger.LogInformation("Loaded {Count} persisted deck sessions from SQLite", sessions.Count);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to load persisted deck sessions from SQLite. Starting with empty sessions.");
            }

            _sessionsLoaded = true;
        }
    }

    /// <summary>
    /// Persists a deck session from the in-memory cache to SQLite.
    /// Failures are logged but do not interrupt the operation.
    /// </summary>
    private void PersistSession(int deckId)
    {
        try
        {
            lock (_sessionLock)
            {
                if (_deckSessions.TryGetValue(deckId, out var session))
                {
                    _deckSessionContext.SaveSession(deckId, session);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist deck session {DeckId} to SQLite", deckId);
        }
    }

    /// <summary>
    /// Begins a new deck building session with the specified format and optional commander.
    /// </summary>
    /// <param name="format">The deck format: "Commander" or "Modern".</param>
    /// <param name="commanderCardName">Optional: The exact name of the commander card (required for Commander format, ignored for other formats).</param>
    /// <returns>JSON string containing the deck session ID, format, and commander name (if applicable).</returns>
    [McpServerTool(Name = "beginNewDeck")]
    [Description("Begins a new deck building session. For Commander format, provide a commander card name. For Modern format, commander is not needed. Returns a unique deck session ID for subsequent operations.")]
    public string BeginNewDeck(
        [Description("The deck format: 'Commander' or 'Modern'. Commander format requires a commander and enforces singleton/color identity rules. Modern format allows duplicates and sideboards.")]
        string format,
        [Description("Optional: The exact name of the commander card. Required for Commander format, ignored for Modern format.")]
        string? commanderCardName = null)
    {
        try
        {
            // Parse the format
            if (!Enum.TryParse<DeckFormat>(format, ignoreCase: true, out var deckFormat))
            {
                return JsonSerializer.Serialize(new
                {
                    error = $"Invalid format '{format}'. Must be 'Commander' or 'Modern'."
                }, new JsonSerializerOptions { WriteIndented = true });
            }

            _logger.LogInformation("Beginning new {Format} deck session", deckFormat);

            string resolvedCommanderName = string.Empty;
            HashSet<char> commanderColorIdentity = new();

            // For Commander format, validate the commander
            if (deckFormat == DeckFormat.Commander)
            {
                if (string.IsNullOrWhiteSpace(commanderCardName))
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = "Commander format requires a commander card name."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }

                // Validate that the commander exists in the database
                var commander = _dbContext.Cards.FirstOrDefault(c => c.Name == commanderCardName);
                if (commander == null)
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = $"Commander '{commanderCardName}' not found in database."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }

                // Validate that the commander is a legal commander
                var commanderViolations = ValidateCommanderCard(commander);
                if (commanderViolations.Count > 0)
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = string.Join("; ", commanderViolations)
                    }, new JsonSerializerOptions { WriteIndented = true });
                }

                resolvedCommanderName = commanderCardName;
                commanderColorIdentity = ParseColorIdentity(commander.ColorIdentity);
            }

            // Create new deck session with next available ID
            int deckId;
            lock (_sessionLock)
            {
                // Find next available ID, skipping any that are already in use
                deckId = _nextDeckId;
                while (_deckSessions.ContainsKey(deckId))
                {
                    deckId++;
                    if (deckId > MaxDeckId)
                        deckId = MinDeckId;
                }

                _deckSessions[deckId] = new DeckSession
                {
                    CommanderName = resolvedCommanderName,
                    CommanderColorIdentity = commanderColorIdentity,
                    Cards = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase),
                    Sideboard = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase),
                    CreatedAt = DateTime.UtcNow,
                    Format = deckFormat
                };

                // Advance to next ID, wrapping around
                _nextDeckId = deckId + 1;
                if (_nextDeckId > MaxDeckId)
                {
                    _nextDeckId = MinDeckId;
                }
            }

            // Persist to SQLite (outside lock to avoid nested lock)
            PersistSession(deckId);

            _logger.LogInformation("Created new {Format} deck session {DeckId}{Commander}",
                deckFormat, deckId,
                deckFormat == DeckFormat.Commander ? $" with commander: {resolvedCommanderName}" : "");

            // Build response based on format
            if (deckFormat == DeckFormat.Commander)
            {
                return JsonSerializer.Serialize(new
                {
                    deckId,
                    format = deckFormat.ToString(),
                    commander = resolvedCommanderName
                }, new JsonSerializerOptions { WriteIndented = true });
            }
            else
            {
                return JsonSerializer.Serialize(new
                {
                    deckId,
                    format = deckFormat.ToString()
                }, new JsonSerializerOptions { WriteIndented = true });
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error beginning new deck session");
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Gets the complete deck list for a deck session, including mainboard, sideboard, and total counts.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <returns>JSON string containing the complete deck list with mainboard, sideboard, and total counts.</returns>
    [McpServerTool(Name = "getFullDecklistWithSideboard")]
    [Description("Gets the complete deck list for a deck session, including mainboard, sideboard, commander (if applicable), format, and total card counts.")]
    public string GetFullDecklistWithSideboard(
        [Description("The deck session ID")]
        int deckId)
    {
        try
        {
            _logger.LogInformation("Getting full decklist with sideboard for deck {DeckId}", deckId);

            DeckSession? session;
            lock (_sessionLock)
            {
                if (!_deckSessions.TryGetValue(deckId, out session))
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = $"Deck session {deckId} not found."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }
            }

            List<object> mainboard;
            List<object> sideboard;
            int mainboardTotal;
            int sideboardTotal;
            int totalCount;
            lock (_sessionLock)
            {
                mainboard = session.Cards
                    .Select(kvp => (object)new { quantity = kvp.Value, name = kvp.Key })
                    .ToList();
                sideboard = session.Sideboard
                    .Select(kvp => (object)new { quantity = kvp.Value, name = kvp.Key })
                    .ToList();
                mainboardTotal = session.Cards.Values.Sum();
                sideboardTotal = session.Sideboard.Values.Sum();
                // For Commander format, include the commander in total count
                totalCount = mainboardTotal + sideboardTotal + (session.IsCommanderFormat ? 1 : 0);
            }

            return JsonSerializer.Serialize(new
            {
                commander = session.IsCommanderFormat ? session.CommanderName : null,
                format = session.Format.ToString(),
                mainboard,
                sideboard,
                mainboardCount = mainboardTotal,
                sideboardCount = sideboardTotal,
                totalCount
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting full decklist with sideboard for deck {DeckId}", deckId);
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Sets a new commander for an existing deck session.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <param name="newCommanderCardName">The exact name of the new commander card.</param>
    /// <returns>JSON string containing the new commander name, removed cards, and updated card count.</returns>
    [McpServerTool(Name = "setCommander")]
    [Description("Sets a new commander for an existing deck session. Removes any cards that violate the new commander's color identity.")]
    public string SetCommander(
        [Description("The deck session ID")]
        int deckId,
        [Description("The exact name of the new commander card")]
        string newCommanderCardName)
    {
        try
        {
            _logger.LogInformation("Setting new commander for deck {DeckId}: {CommanderName}", deckId, newCommanderCardName);

            DeckSession? session;
            lock (_sessionLock)
            {
                if (!_deckSessions.TryGetValue(deckId, out session))
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = $"Deck session {deckId} not found."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }
            }

            // Validate that the new commander exists
            var commander = _dbContext.Cards.FirstOrDefault(c => c.Name == newCommanderCardName);
            if (commander == null)
            {
                return JsonSerializer.Serialize(new
                {
                    error = $"Commander '{newCommanderCardName}' not found in database."
                }, new JsonSerializerOptions { WriteIndented = true });
            }

            // Validate that the new commander is a legal commander
            var commanderViolations = ValidateCommanderCard(commander);
            if (commanderViolations.Count > 0)
            {
                return JsonSerializer.Serialize(new
                {
                    error = string.Join("; ", commanderViolations)
                }, new JsonSerializerOptions { WriteIndented = true });
            }

            var newColorIdentity = ParseColorIdentity(commander.ColorIdentity);
            var removedCards = new List<object>();

            // Check each card against the new color identity and remove violations
            lock (_sessionLock)
            {
                var cardsToRemove = new List<string>();

                foreach (var kvp in session.Cards)
                {
                    var card = _dbContext.Cards.FirstOrDefault(c => c.Name == kvp.Key);
                    if (card != null)
                    {
                        var cardColorIdentity = ParseColorIdentity(card.ColorIdentity);
                        foreach (var color in cardColorIdentity)
                        {
                            if (!newColorIdentity.Contains(color))
                            {
                                cardsToRemove.Add(kvp.Key);
                                removedCards.Add(new { quantity = kvp.Value, name = kvp.Key, reason = $"Color identity '{color}' not in new commander's identity" });
                                break;
                            }
                        }
                    }
                }

                // Remove the violating cards
                foreach (var cardName in cardsToRemove)
                {
                    session.Cards.Remove(cardName);
                }

                // Update the commander
                session.CommanderName = newCommanderCardName;
                session.CommanderColorIdentity = newColorIdentity;
            }

            int mainboardCount, sideboardCount, totalCount;
            lock (_sessionLock)
            {
                mainboardCount = session.Cards.Values.Sum();
                sideboardCount = session.Sideboard.Values.Sum();
                totalCount = mainboardCount + sideboardCount + 1; // +1 for commander
            }

            _logger.LogInformation("Set new commander for deck {DeckId}. Removed {RemovedCount} cards. Total cards: {TotalCount}",
                deckId, removedCards.Count, totalCount);

            // Persist changes to SQLite
            PersistSession(deckId);

            return JsonSerializer.Serialize(new
            {
                commander = newCommanderCardName,
                removedCards,
                mainboardCount,
                sideboardCount,
                totalCount
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error setting commander for deck {DeckId}", deckId);
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Gets the complete deck list for a deck session.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <returns>JSON string containing the complete deck list with commander, cards, and total count.</returns>
    [McpServerTool(Name = "getFullDecklist")]
    [Description("Gets the complete deck list for a deck session, including commander, all cards with quantities, and total card count.")]
    public string GetFullDecklist(
        [Description("The deck session ID")]
        int deckId)
    {
        try
        {
            _logger.LogInformation("Getting full decklist for deck {DeckId}", deckId);

            DeckSession? session;
            lock (_sessionLock)
            {
                if (!_deckSessions.TryGetValue(deckId, out session))
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = $"Deck session {deckId} not found."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }
            }

            List<object> cards;
            int mainboardCount, sideboardCount, totalCount;
            lock (_sessionLock)
            {
                cards = session.Cards
                    .Select(kvp => (object)new { quantity = kvp.Value, name = kvp.Key })
                    .ToList();
                mainboardCount = session.Cards.Values.Sum();
                sideboardCount = session.Sideboard.Values.Sum();
                totalCount = mainboardCount + sideboardCount + (session.IsCommanderFormat ? 1 : 0);
            }

            return JsonSerializer.Serialize(new
            {
                commander = session.IsCommanderFormat ? session.CommanderName : null,
                format = session.Format.ToString(),
                cards,
                mainboardCount,
                sideboardCount,
                totalCount
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting full decklist for deck {DeckId}", deckId);
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Edits a deck by removing and/or adding cards in a single operation.
    /// Removals are processed first, then additions.
    /// </summary>
    /// <param name="deckId">The deck session ID returned from BeginNewDeck.</param>
    /// <param name="cardsToRemove">Optional: A newline-separated list of cards to remove in "count cardName" format.</param>
    /// <param name="cardsToAdd">Optional: A newline-separated list of cards to add in "count cardName" format.</param>
    /// <param name="toSideboard">If true, additions go to sideboard (only for non-Commander formats).</param>
    /// <returns>JSON string containing removed cards, added cards, failures, and updated card counts.</returns>
    [McpServerTool(Name = "editDeck")]
    [Description("Edits a deck by removing and/or adding cards in a single operation. Removals are processed first, then additions. For Commander format: validates color identity, singleton rule, and Commander legality. For Modern format: only validates card existence, allows duplicates, supports sideboard.")]
    public string EditDeck(
        [Description("The deck session ID returned from beginNewDeck")]
        int deckId,
        [Description("Optional: A newline-separated list of cards to remove in 'count cardName' format (e.g., '1 Blood Moon\\n3 Island'). Processed before additions.")]
        string? cardsToRemove = null,
        [Description("Optional: A newline-separated list of cards to add in 'count cardName' format (e.g., '1 Blood Moon\\n3 Island'). Processed after removals.")]
        string? cardsToAdd = null,
        [Description("If true, additions go to the sideboard instead of mainboard. Only applicable for non-Commander formats. Default is false.")]
        bool toSideboard = false)
    {
        try
        {
            _logger.LogInformation("Editing deck {DeckId}", deckId);

            // Validate deck session exists
            DeckSession? session;
            lock (_sessionLock)
            {
                if (!_deckSessions.TryGetValue(deckId, out session))
                {
                    return JsonSerializer.Serialize(new
                    {
                        error = $"Deck session {deckId} not found."
                    }, new JsonSerializerOptions { WriteIndented = true });
                }
            }

            // Validate that at least one operation is specified
            if (string.IsNullOrWhiteSpace(cardsToRemove) && string.IsNullOrWhiteSpace(cardsToAdd))
            {
                return JsonSerializer.Serialize(new
                {
                    error = "At least one of cardsToRemove or cardsToAdd must be provided."
                }, new JsonSerializerOptions { WriteIndented = true });
            }

            var removedCards = new List<object>();
            var addedCards = new List<object>();
            var removeFailures = new List<object>();
            var addFailures = new List<object>();

            // Process removals first
            if (!string.IsNullOrWhiteSpace(cardsToRemove))
            {
                var removeEntries = ParseDeckList(cardsToRemove);
                lock (_sessionLock)
                {
                    foreach (var entry in removeEntries)
                    {
                        if (!session.Cards.TryGetValue(entry.CardName, out var currentCount))
                        {
                            // Try sideboard for non-Commander formats
                            if (!session.IsCommanderFormat && session.Sideboard.TryGetValue(entry.CardName, out currentCount))
                            {
                                var removeCount = Math.Min(entry.Count, currentCount);
                                var newCount = currentCount - removeCount;
                                if (newCount <= 0)
                                    session.Sideboard.Remove(entry.CardName);
                                else
                                    session.Sideboard[entry.CardName] = newCount;
                                removedCards.Add(new { quantity = removeCount, name = entry.CardName, from = "sideboard" });
                                if (removeCount < entry.Count)
                                    removeFailures.Add(new { quantity = entry.Count - removeCount, name = entry.CardName, reason = $"Only {removeCount} copies were in sideboard (requested {entry.Count})." });
                                continue;
                            }
                            removeFailures.Add(new { quantity = entry.Count, name = entry.CardName, reason = "Card not found in deck." });
                            continue;
                        }

                        var removeCountMain = Math.Min(entry.Count, currentCount);
                        var newCountMain = currentCount - removeCountMain;
                        if (newCountMain <= 0)
                            session.Cards.Remove(entry.CardName);
                        else
                            session.Cards[entry.CardName] = newCountMain;
                        removedCards.Add(new { quantity = removeCountMain, name = entry.CardName, from = "mainboard" });
                        if (removeCountMain < entry.Count)
                            removeFailures.Add(new { quantity = entry.Count - removeCountMain, name = entry.CardName, reason = $"Only {removeCountMain} copies were in deck (requested {entry.Count})." });
                    }
                }
            }

            // Process additions
            if (!string.IsNullOrWhiteSpace(cardsToAdd))
            {
                var addEntries = ParseDeckList(cardsToAdd);
                var targetDictionary = (session.IsCommanderFormat || !toSideboard) ? session.Cards : session.Sideboard;
                var targetName = (session.IsCommanderFormat || !toSideboard) ? "mainboard" : "sideboard";

                foreach (var entry in addEntries)
                {
                    if (session.IsCommanderFormat)
                    {
                        var validationResult = ValidateCardForDeck(entry, session);
                        if (validationResult.IsValid)
                        {
                            lock (_sessionLock)
                            {
                                if (!session.Cards.TryAdd(entry.CardName, entry.Count))
                                    session.Cards[entry.CardName] += entry.Count;
                            }
                            addedCards.Add(new { quantity = entry.Count, name = entry.CardName, to = "mainboard" });
                        }
                        else
                        {
                            addFailures.Add(new { quantity = entry.Count, name = entry.CardName, reasons = validationResult.Reasons });
                        }
                    }
                    else
                    {
                        var card = _dbContext.Cards.FirstOrDefault(c => c.Name == entry.CardName);
                        if (card == null)
                        {
                            addFailures.Add(new { quantity = entry.Count, name = entry.CardName, reasons = new[] { "Card not found in database." } });
                            continue;
                        }
                        lock (_sessionLock)
                        {
                            if (!targetDictionary.TryAdd(entry.CardName, entry.Count))
                                targetDictionary[entry.CardName] += entry.Count;
                        }
                        addedCards.Add(new { quantity = entry.Count, name = entry.CardName, to = targetName });
                    }
                }
            }

            // Calculate card counts
            int mainboardCount, sideboardCount, totalCount;
            lock (_sessionLock)
            {
                mainboardCount = session.Cards.Values.Sum();
                sideboardCount = session.Sideboard.Values.Sum();
                totalCount = mainboardCount + sideboardCount + (session.IsCommanderFormat ? 1 : 0);
            }

            _logger.LogInformation("Edited deck {DeckId}. Removed: {RemovedCount}, Added: {AddedCount}. Total: {TotalCount}",
                deckId, removedCards.Count, addedCards.Count, totalCount);

            // Persist changes to SQLite
            PersistSession(deckId);

            return JsonSerializer.Serialize(new
            {
                removedCards,
                addedCards,
                removeFailures,
                addFailures,
                mainboardCount,
                sideboardCount,
                totalCount
            }, new JsonSerializerOptions { WriteIndented = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error editing deck {DeckId}", deckId);
            return JsonSerializer.Serialize(new { error = $"Error: {ex.Message}" });
        }
    }

    #region Helper Methods

    /// <summary>
    /// Validates that a card can be a commander.
    /// </summary>
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

    /// <summary>
    /// Validates a card for inclusion in a deck.
    /// </summary>
    private CardValidationResult ValidateCardForDeck(DeckEntry entry, DeckSession session)
    {
        var reasons = new List<string>();

        // Check if card exists in database
        var card = _dbContext.Cards.FirstOrDefault(c => c.Name == entry.CardName);
        if (card == null)
        {
            reasons.Add($"Card not found in database.");
            return new CardValidationResult { IsValid = false, Reasons = reasons };
        }

        // Check format legality
        var legality = _dbContext.CardLegalities.FirstOrDefault(l => l.Uuid == card.Uuid);
        if (legality == null || !IsLegalInCommander(legality))
        {
            reasons.Add("Card is not legal in Commander format.");
        }

        // Check color identity
        var cardColorIdentity = ParseColorIdentity(card.ColorIdentity);
        foreach (var color in cardColorIdentity)
        {
            if (!session.CommanderColorIdentity.Contains(color))
            {
                var commanderColors = session.CommanderColorIdentity.Count > 0
                    ? string.Join(", ", session.CommanderColorIdentity)
                    : "colorless";
                reasons.Add($"Color identity '{color}' not in commander's color identity ({commanderColors}).");
                break;
            }
        }

        // Check singleton rule (except basic lands)
        if (!BasicLandNames.Contains(entry.CardName))
        {
            lock (_sessionLock)
            {
                if (session.Cards.TryGetValue(entry.CardName, out var existingCount))
                {
                    if (existingCount + entry.Count > 1)
                    {
                        reasons.Add($"Singleton violation: would have {existingCount + entry.Count} copies (max 1 for non-basic lands).");
                    }
                }
                else if (entry.Count > 1)
                {
                    reasons.Add($"Singleton violation: cannot add {entry.Count} copies (max 1 for non-basic lands).");
                }
            }
        }

        return new CardValidationResult { IsValid = reasons.Count == 0, Reasons = reasons };
    }

    /// <summary>
    /// Parses a color identity string into a set of color characters.
    /// </summary>
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

    /// <summary>
    /// Checks if a card is legal in Commander format.
    /// </summary>
    private static bool IsLegalInCommander(CardLegalities legality)
    {
        var status = legality.Commander;
        return status != null &&
               (status.Equals("Legal", StringComparison.OrdinalIgnoreCase) ||
                status.Equals("Restricted", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>
    /// Parses a deck list string into entries.
    /// </summary>
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

    #endregion

    #region Internal Classes

    /// <summary>
    /// Represents the deck format for a deck building session.
    /// </summary>
    public enum DeckFormat
    {
        /// <summary>
        /// Commander format: 100-card singleton with commander, color identity restrictions, no sideboard.
        /// </summary>
        Commander,

        /// <summary>
        /// Modern format: 60-card minimum mainboard, 15-card sideboard, allows duplicates (up to 4 copies).
        /// </summary>
        Modern
    }

    /// <summary>
    /// Represents a deck building session.
    /// </summary>
    public class DeckSession
    {
        public string CommanderName { get; set; } = string.Empty;
        public HashSet<char> CommanderColorIdentity { get; set; } = new();
        public Dictionary<string, int> Cards { get; set; } = new(StringComparer.OrdinalIgnoreCase);
        public Dictionary<string, int> Sideboard { get; set; } = new(StringComparer.OrdinalIgnoreCase);
        public DateTime CreatedAt { get; set; }

        /// <summary>
        /// The format of this deck (Commander, Modern, etc.).
        /// Determines validation rules applied during deck building.
        /// </summary>
        public DeckFormat Format { get; set; } = DeckFormat.Commander;

        /// <summary>
        /// Indicates whether this is a Commander format deck.
        /// Commander decks have commander validation, color identity restrictions, and no sideboard.
        /// </summary>
        public bool IsCommanderFormat => Format == DeckFormat.Commander;
    }

    /// <summary>
    /// Represents a parsed deck entry.
    /// </summary>
    private class DeckEntry
    {
        public int Count { get; set; }
        public string CardName { get; set; } = string.Empty;
    }

    /// <summary>
    /// Result of card validation.
    /// </summary>
    private class CardValidationResult
    {
        public bool IsValid { get; set; }
        public List<string> Reasons { get; set; } = new();
    }

    #endregion
}