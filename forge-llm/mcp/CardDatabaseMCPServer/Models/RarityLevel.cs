namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents the rarity level of a Magic: The Gathering card.
/// Used for sorting cards by rarity.
/// </summary>
public enum RarityLevel
{
    /// <summary>
    /// Common rarity - most frequently found cards.
    /// </summary>
    Common = 0,

    /// <summary>
    /// Uncommon rarity - less common than common cards.
    /// </summary>
    Uncommon = 1,

    /// <summary>
    /// Rare rarity - harder to find cards.
    /// </summary>
    Rare = 2,

    /// <summary>
    /// Mythic rarity - the rarest standard cards.
    /// </summary>
    Mythic = 3
}

