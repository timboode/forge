namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents the game formats in Magic: The Gathering.
/// Used to filter cards by format legality.
/// </summary>
public enum MtgGameFormat
{
    /// <summary>
    /// Standard format - rotating format with recent sets.
    /// </summary>
    Standard,

    /// <summary>
    /// Modern format - non-rotating format with cards from 8th Edition onwards.
    /// </summary>
    Modern,

    /// <summary>
    /// Legacy format - non-rotating format with most cards ever printed.
    /// </summary>
    Legacy,

    /// <summary>
    /// Vintage format - non-rotating format allowing almost all cards.
    /// </summary>
    Vintage,

    /// <summary>
    /// Commander format - multiplayer format with 100-card singleton decks.
    /// </summary>
    Commander,

    /// <summary>
    /// Pioneer format - non-rotating format with cards from Return to Ravnica onwards.
    /// </summary>
    Pioneer,

    /// <summary>
    /// Pauper format - format using only common cards.
    /// </summary>
    Pauper,

    /// <summary>
    /// Historic format - digital format on MTG Arena with expanded card pool.
    /// </summary>
    Historic,

    /// <summary>
    /// Alchemy format - digital format on MTG Arena with rebalanced cards.
    /// </summary>
    Alchemy,

    /// <summary>
    /// Brawl format - Commander-like format on MTG Arena with 60-card decks.
    /// </summary>
    Brawl,

    /// <summary>
    /// Timeless format - digital format on MTG Arena with no bans.
    /// </summary>
    Timeless,

    /// <summary>
    /// Oathbreaker format - multiplayer format with planeswalker commanders.
    /// </summary>
    Oathbreaker
}

