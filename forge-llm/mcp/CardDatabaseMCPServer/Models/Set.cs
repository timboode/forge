using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents a Magic: The Gathering set.
/// </summary>
[Table("sets")]
public class Set
{
    [Column("baseSetSize")]
    public int? BaseSetSize { get; set; }

    [Column("block")]
    public string? Block { get; set; }

    [Column("cardsphereSetId")]
    public int? CardsphereSetId { get; set; }

    [Column("code")]
    [PrimaryKey]
    public string Code { get; set; } = string.Empty;

    [Column("isFoilOnly")]
    public bool? IsFoilOnly { get; set; }

    [Column("isForeignOnly")]
    public bool? IsForeignOnly { get; set; }

    [Column("isNonFoilOnly")]
    public bool? IsNonFoilOnly { get; set; }

    [Column("isOnlineOnly")]
    public bool? IsOnlineOnly { get; set; }

    [Column("isPartialPreview")]
    public bool? IsPartialPreview { get; set; }

    [Column("keyruneCode")]
    public string? KeyruneCode { get; set; }

    [Column("languages")]
    public string? Languages { get; set; }

    [Column("mcmId")]
    public int? McmId { get; set; }

    [Column("mcmIdExtras")]
    public int? McmIdExtras { get; set; }

    [Column("mcmName")]
    public string? McmName { get; set; }

    [Column("mtgoCode")]
    public string? MtgoCode { get; set; }

    [Column("name")]
    public string? Name { get; set; }

    [Column("parentCode")]
    public string? ParentCode { get; set; }

    [Column("releaseDate")]
    public string? ReleaseDate { get; set; }

    [Column("tcgplayerGroupId")]
    public int? TcgplayerGroupId { get; set; }

    [Column("tokenSetCode")]
    public string? TokenSetCode { get; set; }

    [Column("totalSetSize")]
    public int? TotalSetSize { get; set; }

    [Column("type")]
    public string? Type { get; set; }
}

