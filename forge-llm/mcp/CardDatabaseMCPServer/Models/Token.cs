using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents a Magic: The Gathering token.
/// </summary>
[Table("tokens")]
public class Token
{
    [Column("artist")]
    public string? Artist { get; set; }

    [Column("artistIds")]
    public string? ArtistIds { get; set; }

    [Column("asciiName")]
    public string? AsciiName { get; set; }

    [Column("availability")]
    public string? Availability { get; set; }

    [Column("boosterTypes")]
    public string? BoosterTypes { get; set; }

    [Column("borderColor")]
    public string? BorderColor { get; set; }

    [Column("colorIdentity")]
    public string? ColorIdentity { get; set; }

    [Column("colorIndicator")]
    public string? ColorIndicator { get; set; }

    [Column("colors")]
    public string? Colors { get; set; }

    [Column("edhrecSaltiness")]
    public float? EdhrecSaltiness { get; set; }

    [Column("faceName")]
    public string? FaceName { get; set; }

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

    [Column("hasFoil")]
    public bool? HasFoil { get; set; }

    [Column("hasNonFoil")]
    public bool? HasNonFoil { get; set; }

    [Column("isFullArt")]
    public bool? IsFullArt { get; set; }

    [Column("isFunny")]
    public bool? IsFunny { get; set; }

    [Column("isOversized")]
    public bool? IsOversized { get; set; }

    [Column("isPromo")]
    public bool? IsPromo { get; set; }

    [Column("isReprint")]
    public bool? IsReprint { get; set; }

    [Column("isTextless")]
    public bool? IsTextless { get; set; }

    [Column("keywords")]
    public string? Keywords { get; set; }

    [Column("language")]
    public string? Language { get; set; }

    [Column("layout")]
    public string? Layout { get; set; }

    [Column("manaCost")]
    public string? ManaCost { get; set; }

    [Column("name")]
    public string? Name { get; set; }

    [Column("number")]
    public string? Number { get; set; }

    [Column("orientation")]
    public string? Orientation { get; set; }

    [Column("originalText")]
    public string? OriginalText { get; set; }

    [Column("otherFaceIds")]
    public string? OtherFaceIds { get; set; }

    [Column("power")]
    public string? Power { get; set; }

    [Column("printedType")]
    public string? PrintedType { get; set; }

    [Column("promoTypes")]
    public string? PromoTypes { get; set; }

    [Column("relatedCards")]
    public string? RelatedCards { get; set; }

    [Column("reverseRelated")]
    public string? ReverseRelated { get; set; }

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

    [Column("watermark")]
    public string? Watermark { get; set; }
}

