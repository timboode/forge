using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents translations for a set name.
/// </summary>
[Table("setTranslations")]
public class SetTranslations
{
    [Column("language")]
    public string? Language { get; set; }

    [Column("setCode")]
    public string? SetCode { get; set; }

    [Column("translation")]
    public string? Translation { get; set; }
}

