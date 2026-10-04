using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents a Magic: The Gathering card.
/// </summary>
[Table("cards")]
public class Card
{
    [Column("artist")]
    public string? Artist { get; set; }

    [Column("artistIds")]
    public string? ArtistIds { get; set; }

    [Column("asciiName")]
    public string? AsciiName { get; set; }

    [Column("attractionLights")]
    public string? AttractionLights { get; set; }

    [Column("availability")]
    public string? Availability { get; set; }

    [Column("boosterTypes")]
    public string? BoosterTypes { get; set; }

    [Column("borderColor")]
    public string? BorderColor { get; set; }

    [Column("cardParts")]
    public string? CardParts { get; set; }

    [Column("colorIdentity")]
    public string? ColorIdentity { get; set; }

    [Column("colorIndicator")]
    public string? ColorIndicator { get; set; }

    [Column("colors")]
    public string? Colors { get; set; }

    [Column("defense")]
    public string? Defense { get; set; }

    [Column("duelDeck")]
    public string? DuelDeck { get; set; }

    [Column("edhrecRank")]
    public int? EdhrecRank { get; set; }

    [Column("edhrecSaltiness")]
    public float? EdhrecSaltiness { get; set; }

    [Column("faceConvertedManaCost")]
    public float? FaceConvertedManaCost { get; set; }

    [Column("faceFlavorName")]
    public string? FaceFlavorName { get; set; }

    [Column("faceManaValue")]
    public float? FaceManaValue { get; set; }

    [Column("faceName")]
    public string? FaceName { get; set; }

    [Column("facePrintedName")]
    public string? FacePrintedName { get; set; }

    [Column("finishes")]
    public string? Finishes { get; set; }

    [Column("flavorName")]
    public string? FlavorName { get; set; }

    [Column("flavorText")]
    public string? FlavorText { get; set; }

    [Column("frameEffects")]
    public string? FrameEffects { get; set; }

    [Column("frameVersion")]
    public string? FrameVersion { get; set; }

    [Column("hand")]
    public string? Hand { get; set; }

    [Column("hasAlternativeDeckLimit")]
    public bool? HasAlternativeDeckLimit { get; set; }

    [Column("hasContentWarning")]
    public bool? HasContentWarning { get; set; }

    [Column("hasFoil")]
    public bool? HasFoil { get; set; }

    [Column("hasNonFoil")]
    public bool? HasNonFoil { get; set; }

    [Column("isAlternative")]
    public bool? IsAlternative { get; set; }

    [Column("isFullArt")]
    public bool? IsFullArt { get; set; }

    [Column("isFunny")]
    public bool? IsFunny { get; set; }

    [Column("isGameChanger")]
    public bool? IsGameChanger { get; set; }

    [Column("isOnlineOnly")]
    public bool? IsOnlineOnly { get; set; }

    [Column("isOversized")]
    public bool? IsOversized { get; set; }

    [Column("isPromo")]
    public bool? IsPromo { get; set; }

    [Column("isRebalanced")]
    public bool? IsRebalanced { get; set; }

    [Column("isReprint")]
    public bool? IsReprint { get; set; }

    [Column("isReserved")]
    public bool? IsReserved { get; set; }

    [Column("isStarter")]
    public bool? IsStarter { get; set; }

    [Column("isStorySpotlight")]
    public bool? IsStorySpotlight { get; set; }

    [Column("isTextless")]
    public bool? IsTextless { get; set; }

    [Column("isTimeshifted")]
    public bool? IsTimeshifted { get; set; }

    [Column("keywords")]
    public string? Keywords { get; set; }

    [Column("language")]
    public string? Language { get; set; }

    [Column("layout")]
    public string? Layout { get; set; }

    [Column("leadershipSkills")]
    public string? LeadershipSkills { get; set; }

    [Column("life")]
    public string? Life { get; set; }

    [Column("loyalty")]
    public string? Loyalty { get; set; }

    [Column("manaCost")]
    public string? ManaCost { get; set; }

    [Column("manaValue")]
    public float? ManaValue { get; set; }

    [Column("name")]
    public string? Name { get; set; }

    [Column("number")]
    public string? Number { get; set; }

    [Column("originalPrintings")]
    public string? OriginalPrintings { get; set; }

    [Column("originalReleaseDate")]
    public string? OriginalReleaseDate { get; set; }

    [Column("originalText")]
    public string? OriginalText { get; set; }

    [Column("otherFaceIds")]
    public string? OtherFaceIds { get; set; }

    [Column("power")]
    public string? Power { get; set; }

    [Column("printedName")]
    public string? PrintedName { get; set; }

    [Column("printedText")]
    public string? PrintedText { get; set; }

    [Column("printedType")]
    public string? PrintedType { get; set; }

    [Column("printings")]
    public string? Printings { get; set; }

    [Column("promoTypes")]
    public string? PromoTypes { get; set; }

    [Column("rarity")]
    public string? Rarity { get; set; }

    [Column("rebalancedPrintings")]
    public string? RebalancedPrintings { get; set; }

    [Column("relatedCards")]
    public string? RelatedCards { get; set; }

    [Column("securityStamp")]
    public string? SecurityStamp { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }

    [Column("side")]
    public string? Side { get; set; }

    [Column("signature")]
    public string? Signature { get; set; }

    [Column("sourceProducts")]
    public string? SourceProducts { get; set; }

    [Column("subsets")]
    public string? Subsets { get; set; }

    [Column("subtypes")]
    public string? Subtypes { get; set; }

    [Column("supertypes")]
    public string? Supertypes { get; set; }

    [Column("text")]
    public string? Text { get; set; }

    [Column("toughness")]
    public string? Toughness { get; set; }

    [Column("type")]
    public string? Type { get; set; }

    [Column("types")]
    public string? Types { get; set; }

    [Column("uuid")]
    public string Uuid { get; set; } = string.Empty;

    [Column("variations")]
    public string? Variations { get; set; }

    [Column("watermark")]
    public string? Watermark { get; set; }
}

