using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Data;

/// <summary>
/// Database context for interacting with the card-frequencies.db database.
/// Provides access to card usage frequency data.
/// </summary>
public class CardFrequencyContext : IDisposable
{
    private readonly SQLiteConnection? _connection;
    private bool _disposed;
    private readonly bool _databaseExists;

    /// <summary>
    /// Initializes a new instance of the CardFrequencyContext.
    /// </summary>
    /// <param name="databasePath">Path to the card-frequencies.db database file.</param>
    public CardFrequencyContext(string databasePath)
    {
        if (string.IsNullOrWhiteSpace(databasePath))
        {
            throw new ArgumentException("Database path cannot be null or empty.", nameof(databasePath));
        }

        _databaseExists = File.Exists(databasePath);
        if (_databaseExists)
        {
            _connection = new SQLiteConnection(databasePath, SQLiteOpenFlags.ReadOnly);
        }
    }

    /// <summary>
    /// Gets whether the frequency database exists and is available.
    /// </summary>
    public bool IsAvailable => _databaseExists && _connection != null;

    /// <summary>
    /// Gets the use count for a card by name.
    /// Returns 0 if the card is not found.
    /// </summary>
    /// <param name="cardName">The card name to look up.</param>
    /// <returns>The use count, or 0 if not found.</returns>
    public long GetUseCount(string cardName)
    {
        if (!IsAvailable || string.IsNullOrEmpty(cardName))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardName == cardName);

            return entry?.UseCount ?? 0;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the use count for a card by UUID.
    /// Returns 0 if the card is not found or the UUID is null/empty.
    /// Falls back to name-based lookup if UUID-based lookup returns 0.
    /// </summary>
    /// <param name="uuid">The MTGJSON UUID of the card.</param>
    /// <returns>The use count, or 0 if not found.</returns>
    public long GetUseCountByUuid(string uuid)
    {
        if (!IsAvailable || string.IsNullOrEmpty(uuid))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardUuid == uuid);

            return entry?.UseCount ?? 0;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the last-30-days use count for a card by name.
    /// Returns 0 if the card is not found or the windowed count is 0 (falls back to total count).
    /// </summary>
    /// <param name="cardName">The card name to look up.</param>
    /// <returns>The last-30-days use count, or 0 if not found.</returns>
    public long GetUseCountLast30Days(string cardName)
    {
        if (!IsAvailable || string.IsNullOrEmpty(cardName))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardName == cardName);

            if (entry == null)
                return 0;

            // Fall back to total use count if windowed data is not available
            return entry.Last30DaysUseCount > 0 ? entry.Last30DaysUseCount : entry.UseCount;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the last-90-days use count for a card by name.
    /// Returns 0 if the card is not found or the windowed count is 0 (falls back to total count).
    /// </summary>
    /// <param name="cardName">The card name to look up.</param>
    /// <returns>The last-90-days use count, or 0 if not found.</returns>
    public long GetUseCountLast90Days(string cardName)
    {
        if (!IsAvailable || string.IsNullOrEmpty(cardName))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardName == cardName);

            if (entry == null)
                return 0;

            // Fall back to total use count if windowed data is not available
            return entry.Last90DaysUseCount > 0 ? entry.Last90DaysUseCount : entry.UseCount;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the last-30-days use count for a card by UUID.
    /// Returns 0 if the card is not found or the windowed count is 0 (falls back to total count).
    /// </summary>
    /// <param name="uuid">The MTGJSON UUID of the card.</param>
    /// <returns>The last-30-days use count, or 0 if not found.</returns>
    public long GetUseCountLast30DaysByUuid(string uuid)
    {
        if (!IsAvailable || string.IsNullOrEmpty(uuid))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardUuid == uuid);

            if (entry == null)
                return 0;

            // Fall back to total use count if windowed data is not available
            return entry.Last30DaysUseCount > 0 ? entry.Last30DaysUseCount : entry.UseCount;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the last-90-days use count for a card by UUID.
    /// Returns 0 if the card is not found or the windowed count is 0 (falls back to total count).
    /// </summary>
    /// <param name="uuid">The MTGJSON UUID of the card.</param>
    /// <returns>The last-90-days use count, or 0 if not found.</returns>
    public long GetUseCountLast90DaysByUuid(string uuid)
    {
        if (!IsAvailable || string.IsNullOrEmpty(uuid))
        {
            return 0;
        }

        try
        {
            var entry = _connection!.Table<CardFrequency>()
                .FirstOrDefault(f => f.CardUuid == uuid);

            if (entry == null)
                return 0;

            // Fall back to total use count if windowed data is not available
            return entry.Last90DaysUseCount > 0 ? entry.Last90DaysUseCount : entry.UseCount;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// Gets the top N most frequently used cards.
    /// </summary>
    /// <param name="count">The number of cards to return.</param>
    /// <returns>A list of card frequency entries ordered by use count descending.</returns>
    public List<CardFrequency> GetTopCards(int count = 100)
    {
        if (!IsAvailable)
        {
            return new List<CardFrequency>();
        }

        try
        {
            return _connection!.Table<CardFrequency>()
                .OrderByDescending(f => f.UseCount)
                .Take(count)
                .ToList();
        }
        catch
        {
            return new List<CardFrequency>();
        }
    }

    /// <summary>
    /// Gets all card frequency entries.
    /// </summary>
    /// <returns>A list of all card frequency entries.</returns>
    public List<CardFrequency> GetAllCards()
    {
        if (!IsAvailable)
        {
            return new List<CardFrequency>();
        }

        try
        {
            return _connection!.Table<CardFrequency>().ToList();
        }
        catch
        {
            return new List<CardFrequency>();
        }
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
    /// <param name="disposing">Whether to dispose managed resources.</param>
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

