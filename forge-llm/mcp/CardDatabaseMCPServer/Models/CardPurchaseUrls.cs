using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents purchase URLs for a card from various vendors.
/// </summary>
[Table("cardPurchaseUrls")]
public class CardPurchaseUrls
{
    [Column("cardKingdom")]
    public string? CardKingdom { get; set; }

    [Column("cardKingdomEtched")]
    public string? CardKingdomEtched { get; set; }

    [Column("cardKingdomFoil")]
    public string? CardKingdomFoil { get; set; }

    [Column("cardmarket")]
    public string? Cardmarket { get; set; }

    [Column("tcgplayer")]
    public string? Tcgplayer { get; set; }

    [Column("tcgplayerEtched")]
    public string? TcgplayerEtched { get; set; }

    [Column("uuid")]
    public string? Uuid { get; set; }
}

