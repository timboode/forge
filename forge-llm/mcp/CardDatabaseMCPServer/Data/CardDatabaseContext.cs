using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Data;

/// <summary>
/// Database context for interacting with the AllPrintings.sqlite database.
/// Provides access to all MTG card data tables.
/// </summary>
public class CardDatabaseContext : IDisposable
{
    private readonly SQLiteConnection _connection;
    private bool _disposed;

    /// <summary>
    /// Initializes a new instance of the CardDatabaseContext.
    /// </summary>
    /// <param name="databasePath">Path to the AllPrintings.sqlite database file.</param>
    public CardDatabaseContext(string databasePath)
    {
        if (string.IsNullOrWhiteSpace(databasePath))
        {
            throw new ArgumentException("Database path cannot be null or empty.", nameof(databasePath));
        }

        _connection = new SQLiteConnection(databasePath, SQLiteOpenFlags.ReadOnly);
    }

    /// <summary>
    /// Gets the underlying SQLite connection for advanced queries.
    /// </summary>
    public SQLiteConnection Connection => _connection;

    /// <summary>
    /// Gets a table query for cards.
    /// </summary>
    public TableQuery<Card> Cards => _connection.Table<Card>();

    /// <summary>
    /// Gets a table query for card foreign data.
    /// </summary>
    public TableQuery<CardForeignData> CardForeignData => _connection.Table<CardForeignData>();

    /// <summary>
    /// Gets a table query for card identifiers.
    /// </summary>
    public TableQuery<CardIdentifiers> CardIdentifiers => _connection.Table<CardIdentifiers>();

    /// <summary>
    /// Gets a table query for card legalities.
    /// </summary>
    public TableQuery<CardLegalities> CardLegalities => _connection.Table<CardLegalities>();

    /// <summary>
    /// Gets a table query for card purchase URLs.
    /// </summary>
    public TableQuery<CardPurchaseUrls> CardPurchaseUrls => _connection.Table<CardPurchaseUrls>();

    /// <summary>
    /// Gets a table query for card rulings.
    /// </summary>
    public TableQuery<CardRulings> CardRulings => _connection.Table<CardRulings>();

    /// <summary>
    /// Gets a table query for sets.
    /// </summary>
    public TableQuery<Set> Sets => _connection.Table<Set>();

    /// <summary>
    /// Gets a table query for set booster content weights.
    /// </summary>
    public TableQuery<SetBoosterContentWeights> SetBoosterContentWeights => _connection.Table<SetBoosterContentWeights>();

    /// <summary>
    /// Gets a table query for set booster contents.
    /// </summary>
    public TableQuery<SetBoosterContents> SetBoosterContents => _connection.Table<SetBoosterContents>();

    /// <summary>
    /// Gets a table query for set booster sheet cards.
    /// </summary>
    public TableQuery<SetBoosterSheetCards> SetBoosterSheetCards => _connection.Table<SetBoosterSheetCards>();

    /// <summary>
    /// Gets a table query for set booster sheets.
    /// </summary>
    public TableQuery<SetBoosterSheets> SetBoosterSheets => _connection.Table<SetBoosterSheets>();

    /// <summary>
    /// Gets a table query for set translations.
    /// </summary>
    public TableQuery<SetTranslations> SetTranslations => _connection.Table<SetTranslations>();

    /// <summary>
    /// Gets a table query for tokens.
    /// </summary>
    public TableQuery<Token> Tokens => _connection.Table<Token>();

    /// <summary>
    /// Gets a table query for token identifiers.
    /// </summary>
    public TableQuery<TokenIdentifiers> TokenIdentifiers => _connection.Table<TokenIdentifiers>();

    /// <summary>
    /// Gets a table query for database metadata.
    /// </summary>
    public TableQuery<Meta> Meta => _connection.Table<Meta>();

    /// <summary>
    /// Checks if a card is marked as a game changer in the database.
    /// </summary>
    /// <param name="cardName">The exact name of the card to check.</param>
    /// <returns>True if the card is marked as a game changer, false if the card is not a game changer or is not found.</returns>
    public bool IsCardGameChanger(string cardName)
    {
        if (string.IsNullOrWhiteSpace(cardName))
        {
            return false;
        }

        var card = Cards.FirstOrDefault(c => c.Name == cardName);
        return card?.IsGameChanger ?? false;
    }

    /// <summary>
    /// Executes a raw SQL query and returns the results.
    /// </summary>
    /// <typeparam name="T">The type to map results to.</typeparam>
    /// <param name="query">The SQL query to execute.</param>
    /// <param name="args">Query parameters.</param>
    /// <returns>A list of results.</returns>
    public List<T> Query<T>(string query, params object[] args) where T : new()
    {
        return _connection.Query<T>(query, args);
    }

    /// <summary>
    /// Executes a raw SQL query and returns a scalar result.
    /// </summary>
    /// <typeparam name="T">The scalar type to return.</typeparam>
    /// <param name="query">The SQL query to execute.</param>
    /// <param name="args">Query parameters.</param>
    /// <returns>The scalar result.</returns>
    public T ExecuteScalar<T>(string query, params object[] args)
    {
        return _connection.ExecuteScalar<T>(query, args);
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

