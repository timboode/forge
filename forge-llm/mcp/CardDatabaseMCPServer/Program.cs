using CardDatabaseMCPServer.Configuration;
using CardDatabaseMCPServer.Data;
using CardDatabaseMCPServer.Services;
using CardDatabaseMCPServer.Tools;
using ModelContextProtocol.AspNetCore;

namespace CardDatabaseMCPServer;

/// <summary>
/// Main entry point for the CardDatabase MCP server application
/// </summary>
public class Program
{
    public static Task Main(string[] args)
    {
        // Check for import-prices command
        if (args.Length > 0 && args[0].Equals("import-prices", StringComparison.OrdinalIgnoreCase))
        {
            return RunPriceImportAsync();
        }

        return MainAsync(args);
    }

    /// <summary>
    /// Runs the price import process.
    /// </summary>
    private static async Task RunPriceImportAsync()
    {
        var importer = new PriceImporter();
        await importer.RunAsync();
    }

    /// <summary>
    /// Main async entry point that configures and runs the CardDatabase MCP server
    /// </summary>
    public static async Task MainAsync(
        string[] args,
        ILoggerProvider? loggerProvider = null,
        Microsoft.AspNetCore.Connections.IConnectionListenerFactory? kestrelTransport = null,
        CancellationToken cancellationToken = default)
    {
        var builder = WebApplication.CreateEmptyBuilder(new() { Args = args });

        // Configure application settings
        builder.Configuration.AddJsonFile("appsettings.json", optional: true, reloadOnChange: true);
        builder.Configuration.AddJsonFile($"appsettings.{builder.Environment.EnvironmentName}.json", optional: true, reloadOnChange: true);

        // Configure Kestrel server
        if (kestrelTransport is null)
        {
            int port = args.Length > 0 && uint.TryParse(args[0], out var parsedPort) ? (int)parsedPort : 3020;
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.ListenLocalhost(port);
            });
        }
        else
        {
            builder.Services.AddSingleton(kestrelTransport);
        }

        builder.WebHost.UseKestrelCore();
        builder.Services.AddLogging();
        builder.Services.AddRoutingCore();

        // Configure logging
        builder.Logging.AddConsole();
        LoggingConfiguration.ConfigureSerilog(builder.Logging);
        if (loggerProvider is not null)
        {
            builder.Logging.AddProvider(loggerProvider);
        }

        // Ensure card database is fresh before registering context
        var databasePath = Path.Combine(AppContext.BaseDirectory, "AllPrintings.sqlite");
        var databaseUpdater = new CardDatabaseUpdater(AppContext.BaseDirectory);
        var databaseReady = await databaseUpdater.EnsureDatabaseAsync();

        if (!databaseReady)
        {
            Console.WriteLine("FATAL: Card database is not available and could not be downloaded. Server cannot start.");
            throw new InvalidOperationException("Card database is not available. Please ensure network connectivity or manually place AllPrintings.sqlite in the application directory.");
        }

        // Register CardDatabaseContext as a singleton
        builder.Services.AddSingleton(_ => new CardDatabaseContext(databasePath));

        // Register PriceDatabaseContext as a singleton
        var priceDatabasePath = Path.Combine(AppContext.BaseDirectory, "card-prices.sqlite");
        builder.Services.AddSingleton(_ => new PriceDatabaseContext(priceDatabasePath));

        // Register CardFrequencyContext as a singleton
        var frequencyDatabasePath = Path.Combine(AppContext.BaseDirectory, "card-frequencies.db");
        builder.Services.AddSingleton(_ => new CardFrequencyContext(frequencyDatabasePath));

        // Register DeckSessionContext as a singleton for persisted deck sessions
        var deckSessionsDatabasePath = Path.Combine(AppContext.BaseDirectory, "deck-sessions.db");
        builder.Services.AddSingleton(_ => new DeckSessionContext(deckSessionsDatabasePath));

        // Register CardDatabaseTools
        builder.Services.AddSingleton<CardDatabaseTools>();

        // forge-llm addition: compact in-game card lookup (see Tools/GameLookupTools.cs)
        builder.Services.AddSingleton<GameLookupTools>();

        // Register DeckBuilderTools as a singleton for REST API controller access
        builder.Services.AddSingleton<DeckBuilderTools>();

        // Add HttpClient factory for DeckValidationTools to make HTTP requests to Commander Spellbook API
        builder.Services.AddHttpClient();

        // Add controllers for REST API endpoints
        builder.Services.AddControllers()
            .AddApplicationPart(typeof(Program).Assembly);

        // Configure MCP server with CardDatabase tools
        builder.Services.AddMcpServer(McpServerConfiguration.ConfigureOptions)
            .WithTools<CardDatabaseTools>()
            .WithTools<GameLookupTools>()
            .WithTools<DeckValidationTools>()
            .WithTools<DeckBuilderTools>()
            .WithHttpTransport(options =>
            {
                // Set idle timeout to 24 hours to prevent session closure during long operations
                options.IdleTimeout = TimeSpan.FromDays(1);
            });

        // Build and configure the application
        var app = builder.Build();

        // Get logger after app is built
        var logger = app.Services.GetRequiredService<ILogger<Program>>();

        // forge-llm addition: set CARDDB_SKIP_IMPORTS=1 to skip the (network-bound, slow) price and frequency imports,
        // which a game-playing agent does not need.
        var skipImports = Environment.GetEnvironmentVariable("CARDDB_SKIP_IMPORTS") == "1";

        // Run price import before starting the server
        logger.LogInformation("Running price import...");
        try
        {
            if (skipImports) throw new OperationCanceledException("skipped via CARDDB_SKIP_IMPORTS");
            var importer = new PriceImporter();
            await importer.RunAsync();
            logger.LogInformation("Price import completed successfully.");
        }
        catch (OperationCanceledException) when (skipImports)
        {
            logger.LogInformation("Price import skipped (CARDDB_SKIP_IMPORTS=1).");
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Price import failed, continuing with server startup. Prices may be unavailable or stale.");
        }

        // Run card frequency import before starting the server
        logger.LogInformation("Running card frequency import...");
        try
        {
            if (skipImports) throw new OperationCanceledException("skipped via CARDDB_SKIP_IMPORTS");
            var frequencyImporter = new CardFrequencyImporter(cardDatabasePath: databasePath);
            await frequencyImporter.RunAsync();
            logger.LogInformation("Card frequency import completed successfully.");
        }
        catch (OperationCanceledException) when (skipImports)
        {
            logger.LogInformation("Card frequency import skipped (CARDDB_SKIP_IMPORTS=1).");
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Card frequency import failed, continuing with server startup. Frequency data may be unavailable.");
        }

        logger.LogInformation("Starting CardDatabase MCP server on port {Port}...",
            args.Length > 0 && uint.TryParse(args[0], out var p) ? p : 3020);

        app.UseRouting();

        // Map controllers for REST API endpoints
        app.MapControllers();

        // Map default MCP endpoint
        app.MapMcp();

        await app.RunAsync(cancellationToken);
    }
}