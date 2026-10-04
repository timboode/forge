using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents booster sheets for a set.
/// </summary>
[Table("setBoosterSheets")]
public class SetBoosterSheets
{
    [Column("boosterName")]
    public string? BoosterName { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }

    [Column("sheetHasBalanceColors")]
    public bool? SheetHasBalanceColors { get; set; }

    [Column("sheetIsFoil")]
    public bool? SheetIsFoil { get; set; }

    [Column("sheetName")]
    public string? SheetName { get; set; }
}

