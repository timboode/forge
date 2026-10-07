using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents booster content weights for a set.
/// </summary>
[Table("setBoosterContentWeights")]
public class SetBoosterContentWeights
{
    [Column("boosterIndex")]
    public int? BoosterIndex { get; set; }

    [Column("boosterName")]
    public string? BoosterName { get; set; }

    [Column("boosterWeight")]
    public int? BoosterWeight { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }
}

