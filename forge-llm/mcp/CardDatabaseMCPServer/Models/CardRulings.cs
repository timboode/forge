using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents official rulings for a card.
/// </summary>
[Table("cardRulings")]
public class CardRulings
{
    [Column("date")]
    public DateTime? Date { get; set; }

    [Column("text")]
    public string? Text { get; set; }

    [Column("uuid")]
    public string Uuid { get; set; } = string.Empty;
}

