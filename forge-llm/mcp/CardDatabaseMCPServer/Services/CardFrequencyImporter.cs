using System.Diagnostics;
using CardDatabaseMCPServer.Models;
using SQLite;

namespace CardDatabaseMCPServer.Services;

/// <summary>
/// Service for importing card frequency data from a CSV file into SQLite database.
/// Optionally resolves card names to UUIDs from the main card database.
/// </summary>
public class CardFrequencyImporter
{
    private const string CsvFileName = "card-use-frequency.csv";
    private const string DatabaseName = "card-frequencies.db";

    private readonly string _basePath;
    private readonly string? _cardDatabasePath;

    /// <summary>
    /// Initializes a new instance of the CardFrequencyImporter class.
    /// </summary>
    /// <param name="basePath">The base path for file operations. Defaults to application base directory.</param>
    /// <param name="cardDatabasePath">
    /// Optional path to the AllPrintings.sqlite database for resolving card names to UUIDs.
    /// If not provided or unavailable, UUID resolution is skipped (best-effort).
    /// </param>
    public CardFrequencyImporter(string? basePath = null, string? cardDatabasePath = null)
    {
        _basePath = basePath ?? AppContext.BaseDirectory;
        _cardDatabasePath = cardDatabasePath;
    }

    /// <summary>
    /// Runs the card frequency import process.
    /// </summary>
    /// <returns>True if import was successful or skipped (no CSV file), false if an error occurred.</returns>
    public async Task<bool> RunAsync()
    {
        var stopwatch = Stopwatch.StartNew();
        Console.WriteLine("=== Card Frequency Importer ===");
        Console.WriteLine($"Base path: {_basePath}");

        var csvFilePath = Path.Combine(_basePath, CsvFileName);
        var dbPath = Path.Combine(_basePath, DatabaseName);

        // Check if CSV file exists
        if (!File.Exists(csvFilePath))
        {
            Console.WriteLine($"CSV file not found: {csvFilePath}");
            Console.WriteLine("Skipping card frequency import.");
            return true; // Not an error, just no data to import
        }

        try
        {
            // Parse CSV file
            Console.WriteLine($"Parsing CSV file: {csvFilePath}");
            var entries = await ParseCsvFileAsync(csvFilePath);
            Console.WriteLine($"Parsed {entries.Count:N0} card frequency entries.");

            if (entries.Count == 0)
            {
                Console.WriteLine("No entries to import.");
                return true;
            }

            // Import to database
            Console.WriteLine($"Importing to database: {dbPath}");
            await ImportToDatabaseAsync(dbPath, entries);

            // Resolve card names to UUIDs from the main card database (best-effort)
            if (!string.IsNullOrEmpty(_cardDatabasePath) && File.Exists(_cardDatabasePath))
            {
                Console.WriteLine("Resolving card names to UUIDs...");
                await ResolveUuidsAsync(dbPath);
            }
            else
            {
                Console.WriteLine("Card database not available, skipping UUID resolution.");
            }

            stopwatch.Stop();
            Console.WriteLine($"=== Card frequency import completed in {stopwatch.Elapsed.TotalSeconds:F2}s ===");
            return true;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ERROR during card frequency import: {ex.Message}");
            Console.WriteLine(ex.StackTrace);
            return false;
        }
    }

    /// <summary>
    /// Parses the CSV file and returns a list of card frequency entries.
    /// Format: cardName|count (pipe-separated)
    /// </summary>
    private async Task<List<CardFrequency>> ParseCsvFileAsync(string filePath)
    {
        var entries = new List<CardFrequency>();
        var lines = await File.ReadAllLinesAsync(filePath);

        foreach (var line in lines)
        {
            if (string.IsNullOrWhiteSpace(line))
                continue;

            var parts = line.Split('|');
            if (parts.Length != 2)
            {
                Console.WriteLine($"Warning: Malformed line skipped: {line}");
                continue;
            }

            var cardName = parts[0].Trim();
            if (string.IsNullOrEmpty(cardName))
            {
                Console.WriteLine($"Warning: Empty card name skipped: {line}");
                continue;
            }

            if (!long.TryParse(parts[1].Trim(), out var useCount))
            {
                Console.WriteLine($"Warning: Invalid count skipped: {line}");
                continue;
            }

            entries.Add(new CardFrequency
            {
                CardName = cardName,
                UseCount = useCount,
                Last30DaysUseCount = 0,
                Last90DaysUseCount = 0
            });
        }

        return entries;
    }

    /// <summary>
    /// Imports card frequency data into the SQLite database.
    /// Clears existing data and inserts all new entries.
    /// </summary>
    private Task ImportToDatabaseAsync(string dbPath, List<CardFrequency> entries)
    {
        var importStopwatch = Stopwatch.StartNew();

        // Open database connection (create if doesn't exist)
        using var connection = new SQLiteConnection(dbPath);

        // Create table if it doesn't exist
        connection.CreateTable<CardFrequency>();

        // Clear existing data
        Console.WriteLine("Clearing existing card frequency data...");
        connection.DeleteAll<CardFrequency>();

        // Insert all entries in a transaction
        Console.WriteLine($"Inserting {entries.Count:N0} entries...");
        connection.BeginTransaction();
        try
        {
            connection.InsertAll(entries);
            connection.Commit();
            Console.WriteLine("Transaction committed successfully.");
        }
        catch
        {
            connection.Rollback();
            Console.WriteLine("Transaction rolled back due to error.");
            throw;
        }

        importStopwatch.Stop();
        Console.WriteLine($"Database import completed in {importStopwatch.Elapsed.TotalSeconds:F2}s");

        return Task.CompletedTask;
    }

    /// <summary>
    /// Resolves card names to MTGJSON UUIDs by looking up names in the cards table.
    /// Best-effort: cards not found in the card database keep null UUIDs.
    /// </summary>
    private async Task ResolveUuidsAsync(string dbPath)
    {
        var resolveStopwatch = Stopwatch.StartNew();

        // Open frequency database for read/write
        using var frequencyConnection = new SQLiteConnection(dbPath);
        // Ensure the cardUuid column exists (migration from pre-UUID schema)
        frequencyConnection.CreateTable<CardFrequency>();

        // Open card database for read-only
        using var cardConnection = new SQLiteConnection(_cardDatabasePath!, SQLiteOpenFlags.ReadOnly);

        var frequencyEntries = frequencyConnection.Table<CardFrequency>().ToList();
        var updatedCount = 0;

        foreach (var entry in frequencyEntries)
        {
            // Skip entries that already have a UUID
            if (!string.IsNullOrEmpty(entry.CardUuid))
                continue;

            // Look up UUID from cards table by name
            var card = cardConnection.Table<Card>()
                .FirstOrDefault(c => c.Name == entry.CardName);

            if (card != null && !string.IsNullOrEmpty(card.Uuid))
            {
                entry.CardUuid = card.Uuid;
                frequencyConnection.Update(entry);
                updatedCount++;
            }
        }

        resolveStopwatch.Stop();
        Console.WriteLine($"UUID resolution completed in {resolveStopwatch.Elapsed.TotalSeconds:F2}s. " +
                          $"Resolved {updatedCount:N0} of {frequencyEntries.Count:N0} entries.");
    }
}

