using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents a single price entry for a Magic: The Gathering card.
/// The combination of (CardUuid, GameType, Provider, Currency, BuyType, Finish, Date) forms a unique key.
/// </summary>
[Table("prices")]
public class PriceEntry
{
    /// <summary>
    /// The UUID of the card this price is for.
    /// </summary>
    [Column("cardUuid")]
    [Indexed]
    public string CardUuid { get; set; } = string.Empty;

    /// <summary>
    /// The game type (e.g., "paper", "mtgo").
    /// </summary>
    [Column("gameType")]
    public string GameType { get; set; } = string.Empty;

    /// <summary>
    /// The price provider (e.g., "tcgplayer", "cardhoarder").
    /// </summary>
    [Column("provider")]
    public string Provider { get; set; } = string.Empty;

    /// <summary>
    /// The currency code (e.g., "USD", "EUR").
    /// </summary>
    [Column("currency")]
    public string Currency { get; set; } = string.Empty;

    /// <summary>
    /// The buy type - either "buylist" or "retail".
    /// </summary>
    [Column("buyType")]
    public string BuyType { get; set; } = string.Empty;

    /// <summary>
    /// The card finish (e.g., "normal", "foil").
    /// </summary>
    [Column("finish")]
    public string Finish { get; set; } = string.Empty;

    /// <summary>
    /// The date of the price in YYYY-MM-DD format.
    /// </summary>
    [Column("date")]
    public string Date { get; set; } = string.Empty;

    /// <summary>
    /// The price value.
    /// </summary>
    [Column("price")]
    public double Price { get; set; }
}

/// <summary>
/// Represents metadata about the price data.
/// </summary>
[Table("priceMeta")]
public class PriceMeta
{
    /// <summary>
    /// Primary key.
    /// </summary>
    [PrimaryKey]
    [Column("id")]
    public int Id { get; set; } = 1;

    /// <summary>
    /// The date of the price data in YYYY-MM-DD format.
    /// </summary>
    [Column("date")]
    public string Date { get; set; } = string.Empty;

    /// <summary>
    /// The version of the MTGJSON data.
    /// </summary>
    [Column("version")]
    public string Version { get; set; } = string.Empty;

    /// <summary>
    /// When the data was last imported.
    /// </summary>
    [Column("importedAt")]
    public DateTime ImportedAt { get; set; }
}

