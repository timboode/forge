using System.Diagnostics;
using Microsoft.Extensions.Logging;

namespace CardDatabaseMCPServer.Services;

/// <summary>
/// Service for checking and updating the AllPrintings.sqlite database from MTGJSON.
/// Automatically downloads a fresh copy if the database is missing or older than 7 days.
/// </summary>
public class CardDatabaseUpdater
{
    private const string DatabaseUrl = "https://mtgjson.com/api/v5/AllPrintings.sqlite";
    private const string DatabaseFileName = "AllPrintings.sqlite";
    private const int MaxDatabaseAgeDays = 7;

    private readonly HttpClient _httpClient;
    private readonly string _basePath;
    private readonly ILogger<CardDatabaseUpdater>? _logger;

    /// <summary>
    /// Initializes a new instance of the CardDatabaseUpdater class.
    /// </summary>
    /// <param name="basePath">The base path for file operations. Defaults to AppContext.BaseDirectory.</param>
    /// <param name="logger">Optional logger for status messages.</param>
    public CardDatabaseUpdater(string? basePath = null, ILogger<CardDatabaseUpdater>? logger = null)
    {
        _basePath = basePath ?? AppContext.BaseDirectory;
        _logger = logger;
        _httpClient = new HttpClient();
        _httpClient.Timeout = TimeSpan.FromMinutes(30); // Large file download timeout
    }

    /// <summary>
    /// Gets the full path to the database file.
    /// </summary>
    public string DatabasePath => Path.Combine(_basePath, DatabaseFileName);

    /// <summary>
    /// Ensures the database exists and is fresh (not older than 7 days).
    /// Downloads a new copy if needed.
    /// </summary>
    /// <returns>True if the database is ready for use, false if it could not be prepared.</returns>
    public async Task<bool> EnsureDatabaseAsync()
    {
        var stopwatch = Stopwatch.StartNew();
        LogInformation("Checking card database freshness...");

        try
        {
            var (needsUpdate, reason) = CheckDatabaseFreshness();

            if (!needsUpdate)
            {
                LogInformation("Card database is fresh, no update needed.");
                return true;
            }

            LogInformation($"Card database update required: {reason}");
            await DownloadDatabaseAsync();

            stopwatch.Stop();
            LogInformation($"Card database update completed in {stopwatch.Elapsed.TotalSeconds:F2}s");
            return true;
        }
        catch (Exception ex)
        {
            LogError(ex, "Failed to ensure card database is available");

            // If the database exists (even if stale), we can still use it
            if (File.Exists(DatabasePath))
            {
                LogWarning("Using existing stale database due to update failure.");
                return true;
            }

            return false;
        }
    }

    /// <summary>
    /// Checks if the database needs to be updated.
    /// </summary>
    /// <returns>A tuple indicating if update is needed and the reason.</returns>
    private (bool needsUpdate, string reason) CheckDatabaseFreshness()
    {
        if (!File.Exists(DatabasePath))
        {
            return (true, "Database file does not exist");
        }

        var fileInfo = new FileInfo(DatabasePath);
        var fileAge = DateTime.Now - fileInfo.LastWriteTime;

        if (fileAge.TotalDays > MaxDatabaseAgeDays)
        {
            return (true, $"Database is {fileAge.TotalDays:F1} days old (max: {MaxDatabaseAgeDays} days)");
        }

        LogInformation($"Database age: {fileAge.TotalDays:F1} days (max: {MaxDatabaseAgeDays} days)");
        return (false, string.Empty);
    }

    /// <summary>
    /// Downloads the database to a temporary file, validates it, then replaces the existing file.
    /// </summary>
    private async Task DownloadDatabaseAsync()
    {
        var tempPath = Path.Combine(_basePath, $"{DatabaseFileName}.tmp");

        try
        {
            LogInformation($"Downloading card database from {DatabaseUrl}...");
            var downloadStopwatch = Stopwatch.StartNew();

            using var response = await _httpClient.GetAsync(DatabaseUrl, HttpCompletionOption.ResponseHeadersRead);
            response.EnsureSuccessStatusCode();

            var contentLength = response.Content.Headers.ContentLength;
            LogInformation($"Database size: {(contentLength.HasValue ? $"{contentLength.Value / 1024.0 / 1024.0:F1} MB" : "unknown")}");

            // Download to temporary file first
            await using (var stream = await response.Content.ReadAsStreamAsync())
            await using (var fileStream = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                await stream.CopyToAsync(fileStream);
            }

            downloadStopwatch.Stop();
            LogInformation($"Download completed in {downloadStopwatch.Elapsed.TotalSeconds:F2}s");

            // Validate the downloaded file
            ValidateDownloadedDatabase(tempPath, contentLength);

            // Replace the existing file with the new one
            ReplaceDatabase(tempPath);

            LogInformation("Card database successfully updated.");
        }
        finally
        {
            // Clean up temp file if it still exists
            if (File.Exists(tempPath))
            {
                try { File.Delete(tempPath); }
                catch { /* Ignore cleanup errors */ }
            }
        }
    }

    /// <summary>
    /// Validates the downloaded database file.
    /// </summary>
    /// <param name="filePath">Path to the downloaded file.</param>
    /// <param name="expectedSize">Expected file size from Content-Length header, if available.</param>
    private void ValidateDownloadedDatabase(string filePath, long? expectedSize)
    {
        var fileInfo = new FileInfo(filePath);

        // Check file exists and has content
        if (!fileInfo.Exists || fileInfo.Length == 0)
        {
            throw new InvalidOperationException("Downloaded database file is empty or missing.");
        }

        // Check file size matches expected size (if known)
        if (expectedSize.HasValue && fileInfo.Length != expectedSize.Value)
        {
            throw new InvalidOperationException(
                $"Downloaded file size ({fileInfo.Length} bytes) does not match expected size ({expectedSize.Value} bytes). Download may be corrupted.");
        }

        // Basic SQLite header validation (first 16 bytes should start with "SQLite format 3")
        try
        {
            using var stream = File.OpenRead(filePath);
            var header = new byte[16];
            var bytesRead = stream.Read(header, 0, 16);

            if (bytesRead < 16)
            {
                throw new InvalidOperationException("Downloaded file is too small to be a valid SQLite database.");
            }

            var headerString = System.Text.Encoding.ASCII.GetString(header);
            if (!headerString.StartsWith("SQLite format 3"))
            {
                throw new InvalidOperationException("Downloaded file does not appear to be a valid SQLite database.");
            }
        }
        catch (IOException ex)
        {
            throw new InvalidOperationException($"Failed to validate downloaded database: {ex.Message}", ex);
        }

        LogInformation($"Database validation passed. File size: {fileInfo.Length / 1024.0 / 1024.0:F1} MB");
    }

    /// <summary>
    /// Replaces the existing database file with the newly downloaded one.
    /// </summary>
    /// <param name="tempPath">Path to the temporary downloaded file.</param>
    private void ReplaceDatabase(string tempPath)
    {
        var backupPath = DatabasePath + ".bak";

        try
        {
            // Create backup of existing file if it exists
            if (File.Exists(DatabasePath))
            {
                LogInformation("Creating backup of existing database...");
                File.Copy(DatabasePath, backupPath, overwrite: true);
            }

            // Replace with new file
            File.Move(tempPath, DatabasePath, overwrite: true);
            LogInformation("Database file replaced successfully.");

            // Remove backup on success
            if (File.Exists(backupPath))
            {
                File.Delete(backupPath);
            }
        }
        catch (Exception ex)
        {
            // Attempt to restore from backup if replacement failed
            if (File.Exists(backupPath) && !File.Exists(DatabasePath))
            {
                LogWarning("Restoring database from backup due to replacement failure...");
                File.Move(backupPath, DatabasePath);
            }

            throw new InvalidOperationException($"Failed to replace database file: {ex.Message}", ex);
        }
    }

    private void LogInformation(string message)
    {
        if (_logger != null)
            _logger.LogInformation(message);
        else
            Console.WriteLine($"[INFO] {message}");
    }

    private void LogWarning(string message)
    {
        if (_logger != null)
            _logger.LogWarning(message);
        else
            Console.WriteLine($"[WARN] {message}");
    }

    private void LogError(Exception ex, string message)
    {
        if (_logger != null)
            _logger.LogError(ex, message);
        else
            Console.WriteLine($"[ERROR] {message}: {ex.Message}");
    }
}
