using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents various identifiers for a card across different platforms.
/// </summary>
[Table("cardIdentifiers")]
public class CardIdentifiers
{
    [Column("cardKingdomEtchedId")]
    public string? CardKingdomEtchedId { get; set; }

    [Column("cardKingdomFoilId")]
    public string? CardKingdomFoilId { get; set; }

    [Column("cardKingdomId")]
    public string? CardKingdomId { get; set; }

    [Column("cardsphereFoilId")]
    public string? CardsphereFoilId { get; set; }

    [Column("cardsphereId")]
    public string? CardsphereId { get; set; }

    [Column("deckboxId")]
    public string? DeckboxId { get; set; }

    [Column("mcmId")]
    public string? McmId { get; set; }

    [Column("mcmMetaId")]
    public string? McmMetaId { get; set; }

    [Column("mtgArenaId")]
    public string? MtgArenaId { get; set; }

    [Column("mtgjsonFoilVersionId")]
    public string? MtgjsonFoilVersionId { get; set; }

    [Column("mtgjsonNonFoilVersionId")]
    public string? MtgjsonNonFoilVersionId { get; set; }

    [Column("mtgjsonV4Id")]
    public string? MtgjsonV4Id { get; set; }

    [Column("mtgoFoilId")]
    public string? MtgoFoilId { get; set; }

    [Column("mtgoId")]
    public string? MtgoId { get; set; }

    [Column("multiverseId")]
    public string? MultiverseId { get; set; }

    [Column("scryfallCardBackId")]
    public string? ScryfallCardBackId { get; set; }

    [Column("scryfallId")]
    public string? ScryfallId { get; set; }

    [Column("scryfallIllustrationId")]
    public string? ScryfallIllustrationId { get; set; }

    [Column("scryfallOracleId")]
    public string? ScryfallOracleId { get; set; }

    [Column("tcgplayerEtchedProductId")]
    public string? TcgplayerEtchedProductId { get; set; }

    [Column("tcgplayerProductId")]
    public string? TcgplayerProductId { get; set; }

    [Column("uuid")]
    public string? Uuid { get; set; }
}

