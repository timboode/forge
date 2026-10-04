using SQLite;

namespace CardDatabaseMCPServer.Models;

/// <summary>
/// SQLite-persisted entity for a deck building session.
/// Complex types (color identity, cards, sideboard) are serialized as JSON/CSV.
/// </summary>
[Table("deck_sessions")]
public class DeckSessionEntity
{
    /// <summary>
    /// The deck session ID (wrapping range -99 to 999). Manually assigned, not auto-increment.
    /// </summary>
    [PrimaryKey]
    [Column("id")]
    public int Id { get; set; }

    /// <summary>
    /// The deck format: "Commander" or "Modern".
    /// </summary>
    [Column("format")]
    [NotNull]
    public string Format { get; set; } = string.Empty;

    /// <summary>
    /// The commander card name (empty for non-Commander formats).
    /// </summary>
    [Column("commander_name")]
    [NotNull]
    public string CommanderName { get; set; } = string.Empty;

    /// <summary>
    /// Comma-separated color identity characters (e.g. "W,U,B").
    /// </summary>
    [Column("color_identity")]
    [NotNull]
    public string ColorIdentity { get; set; } = string.Empty;

    /// <summary>
    /// JSON-serialized mainboard cards as a dictionary of card name to quantity.
    /// </summary>
    [Column("cards_json")]
    [NotNull]
    public string CardsJson { get; set; } = "{}";

    /// <summary>
    /// JSON-serialized sideboard cards as a dictionary of card name to quantity.
    /// </summary>
    [Column("sideboard_json")]
    [NotNull]
    public string SideboardJson { get; set; } = "{}";

    /// <summary>
    /// ISO 8601 UTC timestamp of when the session was created.
    /// </summary>
    [Column("created_at")]
    [NotNull]
    public string CreatedAt { get; set; } = string.Empty;
}
