using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents a card frequency entry tracking how often a card is used across decks.
/// </summary>
[Table("cardFrequencies")]
public class CardFrequency
{
    /// <summary>
    /// Primary key, auto-increment.
    /// </summary>
    [PrimaryKey]
    [AutoIncrement]
    [Column("id")]
    public int Id { get; set; }

    /// <summary>
    /// The name of the card.
    /// </summary>
    [Column("cardName")]
    [Indexed(Unique = true)]
    [NotNull]
    public string CardName { get; set; } = string.Empty;

    /// <summary>
    /// The total count of how many times this card appears across all decks.
    /// </summary>
    [Column("useCount")]
    public long UseCount { get; set; }

    /// <summary>
    /// The count of how many times this card appears across decks in the last 30 days.
    /// 0 when time-windowed data is not available; callers should fall back to <see cref="UseCount"/>.
    /// </summary>
    [Column("lastThirtyDaysUseCount")]
    public long Last30DaysUseCount { get; set; }

    /// <summary>
    /// The count of how many times this card appears across decks in the last 90 days.
    /// 0 when time-windowed data is not available; callers should fall back to <see cref="UseCount"/>.
    /// </summary>
    [Column("lastNinetyDaysUseCount")]
    public long Last90DaysUseCount { get; set; }

    /// <summary>
    /// The MTGJSON UUID of the card. Resolved best-effort from the cards table.
    /// May be null if the cards table was not available at import time or if
    /// the card name could not be resolved to a UUID.
    /// </summary>
    [Column("cardUuid")]
    public string? CardUuid { get; set; }
}

