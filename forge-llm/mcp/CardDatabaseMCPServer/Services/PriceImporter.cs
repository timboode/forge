using System.Diagnostics;
using System.Text.Json;
using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Services;

/// <summary>
/// Service for importing Magic: The Gathering card price data from MTGJSON.
/// </summary>
public class PriceImporter
{
    private const string PriceFileUrl = "https://mtgjson.com/api/v5/AllPrices.json";
    private const string PriceFileName = "AllPricesToday.json";
    private const string PriceDatabaseName = "card-prices.sqlite";
    private const int MaxFileAgeHours = 168;

    private readonly HttpClient _httpClient;
    private readonly string _basePath;

    /// <summary>
    /// Initializes a new instance of the PriceImporter class.
    /// </summary>
    /// <param name="basePath">The base path for file operations. Defaults to current directory.</param>
    public PriceImporter(string? basePath = null)
    {
        _basePath = basePath ?? AppContext.BaseDirectory;
        _httpClient = new HttpClient();
        _httpClient.Timeout = TimeSpan.FromMinutes(10); // Large file download timeout
    }

    /// <summary>
    /// Runs the full price import process.
    /// </summary>
    public async Task RunAsync()
    {
        var stopwatch = Stopwatch.StartNew();
        Console.WriteLine("=== MTG Price Importer ===");
        Console.WriteLine($"Base path: {_basePath}");
        Console.WriteLine();

        try
        {
            // Step 1: Check/download the JSON file
            var jsonFilePath = Path.Combine(_basePath, PriceFileName);
            var needsImport = await EnsureJsonFileAsync(jsonFilePath);

            if (!needsImport) return;

            // Step 2: Parse the JSON file
            Console.WriteLine("Parsing JSON file...");
            var parseStopwatch = Stopwatch.StartNew();
            var (meta, priceEntries) = await ParsePriceFileAsync(jsonFilePath);
            parseStopwatch.Stop();
            Console.WriteLine($"Parsed {priceEntries.Count:N0} price entries in {parseStopwatch.Elapsed.TotalSeconds:F2}s");
            Console.WriteLine($"Meta: Date={meta.Date}, Version={meta.Version}");
            Console.WriteLine();

            // Step 3: Import into database
            var dbPath = Path.Combine(_basePath, PriceDatabaseName);
            Console.WriteLine($"Importing to database: {dbPath}");
            await ImportToDatabaseAsync(dbPath, meta, priceEntries);

            stopwatch.Stop();
            Console.WriteLine();
            Console.WriteLine($"=== Import completed in {stopwatch.Elapsed.TotalSeconds:F2}s ===");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ERROR: {ex.Message}");
            Console.WriteLine(ex.StackTrace);
            throw;
        }
    }

    /// <summary>
    /// Ensures the JSON file exists and is not older than MaxFileAgeHours.
    /// Downloads a fresh copy if needed.
    /// </summary>
    private async Task<bool> EnsureJsonFileAsync(string filePath)
    {
        var needsDownload = false;
        var reason = "";

        if (!File.Exists(filePath))
        {
            needsDownload = true;
            reason = "File does not exist";
        }
        else
        {
            var fileInfo = new FileInfo(filePath);
            var fileAge = DateTime.Now - fileInfo.LastWriteTime;
            if (fileAge.TotalHours > MaxFileAgeHours)
            {
                needsDownload = true;
                reason = $"File is {fileAge.TotalHours:F1} hours old (max: {MaxFileAgeHours} hours)";
            }
            else
            {
                Console.WriteLine($"Using existing file (age: {fileAge.TotalHours:F1} hours)");
            }
        }

        if (needsDownload)
        {
            Console.WriteLine($"Downloading price file... ({reason})");
            var downloadStopwatch = Stopwatch.StartNew();

            using var response = await _httpClient.GetAsync(PriceFileUrl, HttpCompletionOption.ResponseHeadersRead);
            response.EnsureSuccessStatusCode();

            var contentLength = response.Content.Headers.ContentLength;
            Console.WriteLine($"File size: {(contentLength.HasValue ? $"{contentLength.Value / 1024.0 / 1024.0:F1} MB" : "unknown")}");

            await using var stream = await response.Content.ReadAsStreamAsync();
            await using var fileStream = new FileStream(filePath, FileMode.Create, FileAccess.Write, FileShare.None);
            await stream.CopyToAsync(fileStream);

            downloadStopwatch.Stop();
            Console.WriteLine($"Download completed in {downloadStopwatch.Elapsed.TotalSeconds:F2}s");
        }

        Console.WriteLine();

        return needsDownload;
    }

    /// <summary>
    /// Parses the price JSON file and returns meta info and price entries.
    /// </summary>
    private async Task<(PriceMeta meta, List<PriceEntry> entries)> ParsePriceFileAsync(string filePath)
    {
        var entries = new List<PriceEntry>();
        var meta = new PriceMeta { ImportedAt = DateTime.UtcNow };

        await using var stream = File.OpenRead(filePath);
        using var document = await JsonDocument.ParseAsync(stream);

        var root = document.RootElement;

        // Parse meta
        if (root.TryGetProperty("meta", out var metaElement))
        {
            if (metaElement.TryGetProperty("date", out var dateElement))
                meta.Date = dateElement.GetString() ?? "";
            if (metaElement.TryGetProperty("version", out var versionElement))
                meta.Version = versionElement.GetString() ?? "";
        }

        // Parse data
        if (!root.TryGetProperty("data", out var dataElement))
        {
            throw new InvalidOperationException("JSON file does not contain 'data' property");
        }

        // Traverse the nested structure
        foreach (var cardProperty in dataElement.EnumerateObject())
        {
            var cardUuid = cardProperty.Name;
            ParseCardPrices(cardUuid, cardProperty.Value, entries);
        }

        return (meta, entries);
    }

    /// <summary>
    /// Parses price data for a single card UUID.
    /// </summary>
    private void ParseCardPrices(string cardUuid, JsonElement cardElement, List<PriceEntry> entries)
    {
        // Iterate game types (paper, mtgo, etc.)
        foreach (var gameTypeProperty in cardElement.EnumerateObject())
        {
            var gameType = gameTypeProperty.Name;

            // Iterate providers (tcgplayer, cardhoarder, etc.)
            foreach (var providerProperty in gameTypeProperty.Value.EnumerateObject())
            {
                var provider = providerProperty.Name;
                var providerData = providerProperty.Value;

                // Get currency
                var currency = "USD";
                if (providerData.TryGetProperty("currency", out var currencyElement))
                {
                    currency = currencyElement.GetString() ?? "USD";
                }

                // Parse buylist prices
                if (providerData.TryGetProperty("buylist", out var buylistElement))
                {
                    ParsePriceCategory(cardUuid, gameType, provider, currency, "buylist", buylistElement, entries);
                }

                // Parse retail prices
                if (providerData.TryGetProperty("retail", out var retailElement))
                {
                    ParsePriceCategory(cardUuid, gameType, provider, currency, "retail", retailElement, entries);
                }
            }
        }
    }

    /// <summary>
    /// Parses a price category (buylist or retail) for all finishes.
    /// </summary>
    private void ParsePriceCategory(
        string cardUuid,
        string gameType,
        string provider,
        string currency,
        string buyType,
        JsonElement categoryElement,
        List<PriceEntry> entries)
    {
        // Iterate finishes (normal, foil, etc.)
        foreach (var finishProperty in categoryElement.EnumerateObject())
        {
            var finish = finishProperty.Name;

            // Iterate date/price pairs
            foreach (var priceProperty in finishProperty.Value.EnumerateObject())
            {
                var date = priceProperty.Name;
                if (priceProperty.Value.TryGetDouble(out var price))
                {
                    entries.Add(new PriceEntry
                    {
                        CardUuid = cardUuid,
                        GameType = gameType,
                        Provider = provider,
                        Currency = currency,
                        BuyType = buyType,
                        Finish = finish,
                        Date = date,
                        Price = price
                    });
                }
            }
        }
    }

    /// <summary>
    /// Imports price data into the SQLite database using upsert (INSERT OR REPLACE) logic.
    /// Existing price entries not in the new data are preserved.
    /// </summary>
    private async Task ImportToDatabaseAsync(string dbPath, PriceMeta meta, List<PriceEntry> entries)
    {
        var importStopwatch = Stopwatch.StartNew();

        // Open database connection
        var connection = new SQLiteConnection(dbPath);

        // Create tables if they don't exist
        connection.CreateTable<PriceMeta>();
        connection.CreateTable<PriceEntry>();

        // Create unique index for upsert support if it doesn't exist
        EnsureUniqueIndex(connection);

        // Update meta (clear and reinsert since it's just one row)
        Console.WriteLine("Updating price metadata...");
        connection.DeleteAll<PriceMeta>();
        connection.Insert(meta);

        Console.WriteLine($"Upserting {entries.Count:N0} price entries (preserving historical data)...");

        // Use a transaction for all upserts
        connection.BeginTransaction();
        try
        {
            // Batch upsert price entries
            var batchSize = 10000;
            var totalBatches = (entries.Count + batchSize - 1) / batchSize;

            for (int i = 0; i < entries.Count; i += batchSize)
            {
                var batch = entries.Skip(i).Take(batchSize).ToList();
                UpsertBatch(connection, batch);

                var currentBatch = (i / batchSize) + 1;
                var progress = (double)(i + batch.Count) / entries.Count * 100;
                Console.Write($"\rProgress: {progress:F1}% ({currentBatch}/{totalBatches} batches)");
            }

            connection.Commit();
            Console.WriteLine();
            Console.WriteLine("Transaction committed successfully.");
        }
        catch (Exception ex)
        {
            connection.Rollback();
            Console.WriteLine($"\nTransaction rolled back due to error: {ex.Message}");
            throw;
        }
        finally
        {
            connection.Close();
            connection.Dispose();
        }

        importStopwatch.Stop();
        Console.WriteLine($"Database import completed in {importStopwatch.Elapsed.TotalSeconds:F2}s");
    }

    /// <summary>
    /// Ensures the unique index exists for upsert operations.
    /// </summary>
    private void EnsureUniqueIndex(SQLiteConnection connection)
    {
        const string createIndexSql = @"
            CREATE UNIQUE INDEX IF NOT EXISTS idx_prices_unique
            ON prices (cardUuid, gameType, provider, currency, buyType, finish, date)";

        connection.Execute(createIndexSql);
    }

    /// <summary>
    /// Upserts a batch of price entries using INSERT OR REPLACE.
    /// </summary>
    private void UpsertBatch(SQLiteConnection connection, List<PriceEntry> batch)
    {
        const string upsertSql = @"
            INSERT OR REPLACE INTO prices (cardUuid, gameType, provider, currency, buyType, finish, date, price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        foreach (var entry in batch)
        {
            connection.Execute(upsertSql,
                entry.CardUuid,
                entry.GameType,
                entry.Provider,
                entry.Currency,
                entry.BuyType,
                entry.Finish,
                entry.Date,
                entry.Price);
        }
    }
}