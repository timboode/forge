using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents cards in a booster sheet for a set.
/// </summary>
[Table("setBoosterSheetCards")]
public class SetBoosterSheetCards
{
    [Column("boosterName")]
    public string? BoosterName { get; set; }

    [Column("cardUuid")]
    public string CardUuid { get; set; } = string.Empty;

    [Column("cardWeight")]
    public long? CardWeight { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }

    [Column("sheetName")]
    public string? SheetName { get; set; }
}

