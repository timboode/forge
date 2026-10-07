using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Data;

/// <summary>
/// Database context for interacting with the card-prices.sqlite database.
/// Provides access to price data for MTG cards.
/// </summary>
public class PriceDatabaseContext : IDisposable
{
    private readonly SQLiteConnection? _connection;
    private bool _disposed;
    private readonly bool _databaseExists;

    /// <summary>
    /// Initializes a new instance of the PriceDatabaseContext.
    /// </summary>
    /// <param name="databasePath">Path to the card-prices.sqlite database file.</param>
    public PriceDatabaseContext(string databasePath)
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
    /// Gets whether the price database exists and is available.
    /// </summary>
    public bool IsAvailable => _databaseExists && _connection != null;

    /// <summary>
    /// Gets the current paper retail price for a card by UUID.
    /// Returns -1.0 if no price is available.
    /// </summary>
    /// <param name="cardUuid">The card UUID to look up.</param>
    /// <returns>The paper retail price in USD, or -1.0 if not available.</returns>
    public double GetPaperPrice(string cardUuid)
    {
        if (!IsAvailable || string.IsNullOrEmpty(cardUuid))
        {
            return 1000000000;
        }

        try
        {
            // Get the most recent retail price for paper, preferring normal finish
            var price = _connection!.Table<PriceEntry>()
                .Where(p => p.CardUuid == cardUuid
                         && p.GameType == "paper"
                         && p.BuyType == "retail")
                .OrderByDescending(p => p.Date)
                .FirstOrDefault();

            return price?.Price ?? 1000000000;
        }
        catch
        {
            Console.WriteLine("Exception getting paper price for " + cardUuid);
            return 1000000000;
        }
    }

    /// <summary>
    /// Gets the current MTGO retail price for a card by UUID.
    /// Returns -1.0 if no price is available.
    /// </summary>
    /// <param name="cardUuid">The card UUID to look up.</param>
    /// <returns>The MTGO retail price in USD, or -1.0 if not available.</returns>
    public double GetMtgoPrice(string cardUuid)
    {
        if (!IsAvailable || string.IsNullOrEmpty(cardUuid))
        {
            return 1000000000;
        }

        try
        {
            // Get the most recent retail price for MTGO
            var price = _connection!.Table<PriceEntry>()
                .Where(p => p.CardUuid == cardUuid
                         && p.GameType == "mtgo"
                         && p.BuyType == "retail")
                .OrderByDescending(p => p.Date)
                .FirstOrDefault();

            return price?.Price ?? 1000000000;
        }
        catch
        {
            Console.WriteLine("Exception getting MTGO price for " + cardUuid);
            return 1000000000;
        }
    }

    /// <summary>
    /// Gets both paper and MTGO prices for a card by UUID.
    /// Returns (-1.0, -1.0) if no prices are available.
    /// </summary>
    /// <param name="cardUuid">The card UUID to look up.</param>
    /// <returns>A tuple of (paperPrice, mtgoPrice).</returns>
    public (double PaperPrice, double MtgoPrice) GetPrices(string cardUuid)
    {
        return (GetPaperPrice(cardUuid), GetMtgoPrice(cardUuid));
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

