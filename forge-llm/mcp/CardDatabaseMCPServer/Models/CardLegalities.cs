using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents the legality status of a card in various formats.
/// </summary>
[Table("cardLegalities")]
public class CardLegalities
{
    [Column("alchemy")]
    public string? Alchemy { get; set; }

    [Column("brawl")]
    public string? Brawl { get; set; }

    [Column("commander")]
    public string? Commander { get; set; }

    [Column("duel")]
    public string? Duel { get; set; }

    [Column("future")]
    public string? Future { get; set; }

    [Column("gladiator")]
    public string? Gladiator { get; set; }

    [Column("historic")]
    public string? Historic { get; set; }

    [Column("legacy")]
    public string? Legacy { get; set; }

    [Column("modern")]
    public string? Modern { get; set; }

    [Column("oathbreaker")]
    public string? Oathbreaker { get; set; }

    [Column("oldschool")]
    public string? Oldschool { get; set; }

    [Column("pauper")]
    public string? Pauper { get; set; }

    [Column("paupercommander")]
    public string? Paupercommander { get; set; }

    [Column("penny")]
    public string? Penny { get; set; }

    [Column("pioneer")]
    public string? Pioneer { get; set; }

    [Column("predh")]
    public string? Predh { get; set; }

    [Column("premodern")]
    public string? Premodern { get; set; }

    [Column("standard")]
    public string? Standard { get; set; }

    [Column("standardbrawl")]
    public string? Standardbrawl { get; set; }

    [Column("timeless")]
    public string? Timeless { get; set; }

    [Column("uuid")]
    public string? Uuid { get; set; }

    [Column("vintage")]
    public string? Vintage { get; set; }
}

