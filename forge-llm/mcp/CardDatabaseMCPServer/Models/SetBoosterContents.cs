using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents booster contents for a set.
/// </summary>
[Table("setBoosterContents")]
public class SetBoosterContents
{
    [Column("boosterIndex")]
    public int? BoosterIndex { get; set; }

    [Column("boosterName")]
    public string? BoosterName { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }

    [Column("sheetName")]
    public string? SheetName { get; set; }

    [Column("sheetPicks")]
    public int? SheetPicks { get; set; }
}

