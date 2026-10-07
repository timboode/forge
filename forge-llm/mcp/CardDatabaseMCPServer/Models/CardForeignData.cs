using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents foreign language data for a card.
/// </summary>
[Table("cardForeignData")]
public class CardForeignData
{
    [Column("faceName")]
    public string? FaceName { get; set; }

    [Column("flavorText")]
    public string? FlavorText { get; set; }

    [Column("identifiers")]
    public string? Identifiers { get; set; }

    [Column("language")]
    public string? Language { get; set; }

    [Column("multiverseId")]
    public int? MultiverseId { get; set; }

    [Column("name")]
    public string? Name { get; set; }

    [Column("text")]
    public string? Text { get; set; }

    [Column("type")]
    public string? Type { get; set; }

    [Column("uuid")]
    public string? Uuid { get; set; }
}

