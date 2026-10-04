using System.Text.Json;
using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Data;

/// <summary>
/// Database context for persisting deck building sessions to SQLite.
/// Provides CRUD operations and automatic table migration on startup.
/// </summary>
public class DeckSessionContext : IDisposable
{
    private readonly SQLiteConnection _connection;
    private bool _disposed;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = false
    };

    /// <summary>
    /// Initializes a new instance of the DeckSessionContext.
    /// Creates the database and table if they don't exist.
    /// </summary>
    /// <param name="databasePath">Path to the deck-sessions.db SQLite database file.</param>
    public DeckSessionContext(string databasePath)
    {
        if (string.IsNullOrWhiteSpace(databasePath))
        {
            throw new ArgumentException("Database path cannot be null or empty.", nameof(databasePath));
        }

        _connection = new SQLiteConnection(databasePath, SQLiteOpenFlags.ReadWrite | SQLiteOpenFlags.Create);
        _connection.CreateTable<DeckSessionEntity>();
    }

    /// <summary>
    /// Loads all persisted deck sessions from the database.
    /// </summary>
    /// <returns>A list of all deck sessions.</returns>
    public List<(int Id, Tools.DeckBuilderTools.DeckSession Session)> LoadAllSessions()
    {
        var entities = _connection.Table<DeckSessionEntity>().ToList();
        var sessions = new List<(int Id, Tools.DeckBuilderTools.DeckSession Session)>();

        foreach (var entity in entities)
        {
            var session = MapToDeckSession(entity);
            sessions.Add((entity.Id, session));
        }

        return sessions;
    }

    /// <summary>
    /// Saves (inserts or replaces) a deck session to the database.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <param name="session">The deck session to persist.</param>
    public void SaveSession(int deckId, Tools.DeckBuilderTools.DeckSession session)
    {
        var entity = MapToEntity(deckId, session);
        _connection.InsertOrReplace(entity);
    }

    /// <summary>
    /// Deletes a deck session from the database by ID.
    /// </summary>
    /// <param name="deckId">The deck session ID to delete.</param>
    public void DeleteSession(int deckId)
    {
        _connection.Delete<DeckSessionEntity>(deckId);
    }

    /// <summary>
    /// Maps a DeckSessionEntity to a DeckSession.
    /// </summary>
    private static Tools.DeckBuilderTools.DeckSession MapToDeckSession(DeckSessionEntity entity)
    {
        var format = Enum.Parse<Tools.DeckBuilderTools.DeckFormat>(entity.Format);

        var colorIdentity = new HashSet<char>();
        if (!string.IsNullOrEmpty(entity.ColorIdentity))
        {
            foreach (var part in entity.ColorIdentity.Split(',', StringSplitOptions.RemoveEmptyEntries))
            {
                var trimmed = part.Trim().ToUpperInvariant();
                if (trimmed.Length == 1 && "WUBRG".Contains(trimmed[0]))
                {
                    colorIdentity.Add(trimmed[0]);
                }
            }
        }

        var cards = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrEmpty(entity.CardsJson) && entity.CardsJson != "{}")
        {
            try
            {
                var deserialized = JsonSerializer.Deserialize<Dictionary<string, int>>(entity.CardsJson);
                if (deserialized != null)
                {
                    cards = new Dictionary<string, int>(deserialized, StringComparer.OrdinalIgnoreCase);
                }
            }
            catch
            {
                // If JSON is malformed, start with empty dictionary
            }
        }

        var sideboard = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrEmpty(entity.SideboardJson) && entity.SideboardJson != "{}")
        {
            try
            {
                var deserialized = JsonSerializer.Deserialize<Dictionary<string, int>>(entity.SideboardJson);
                if (deserialized != null)
                {
                    sideboard = new Dictionary<string, int>(deserialized, StringComparer.OrdinalIgnoreCase);
                }
            }
            catch
            {
                // If JSON is malformed, start with empty dictionary
            }
        }

        DateTime createdAt = DateTime.UtcNow;
        if (!string.IsNullOrEmpty(entity.CreatedAt))
        {
            DateTime.TryParse(entity.CreatedAt, null, System.Globalization.DateTimeStyles.RoundtripKind, out createdAt);
        }

        return new Tools.DeckBuilderTools.DeckSession
        {
            CommanderName = entity.CommanderName,
            CommanderColorIdentity = colorIdentity,
            Cards = cards,
            Sideboard = sideboard,
            CreatedAt = createdAt,
            Format = format
        };
    }

    /// <summary>
    /// Maps a DeckSession to a DeckSessionEntity for persistence.
    /// </summary>
    private static DeckSessionEntity MapToEntity(int deckId, Tools.DeckBuilderTools.DeckSession session)
    {
        return new DeckSessionEntity
        {
            Id = deckId,
            Format = session.Format.ToString(),
            CommanderName = session.CommanderName,
            ColorIdentity = string.Join(",", session.CommanderColorIdentity),
            CardsJson = JsonSerializer.Serialize(session.Cards, JsonOptions),
            SideboardJson = JsonSerializer.Serialize(session.Sideboard, JsonOptions),
            CreatedAt = session.CreatedAt.ToString("O")
        };
    }

    /// <summary>
    /// Disposes the database connection.
    /// </summary>
    public void Dispose()
    {
        Dispose(true);
        GC.SuppressFinalize(this);
    }

    /// <summary>
    /// Disposes the database connection.
    /// </summary>
    protected virtual void Dispose(bool disposing)
    {
        if (!_disposed)
        {
            if (disposing)
            {
                _connection?.Close();
                _connection?.Dispose();
            }
            _disposed = true;
        }
    }
}
