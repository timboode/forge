using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents metadata about the database.
/// </summary>
[Table("meta")]
public class Meta
{
    [Column("date")]
    public DateTime? Date { get; set; }

    [Column("version")]
    public string? Version { get; set; }
}

