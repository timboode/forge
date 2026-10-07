using Serilog;

namespace CardDatabaseMCPServer.Configuration;

/// <summary>
/// Handles Serilog logging configuration for CardDatabase MCP Server
/// </summary>
public static class LoggingConfiguration
{
    /// <summary>
    /// Configures Serilog for the application
    /// </summary>
    /// <param name="loggingBuilder">The logging builder to configure</param>
    public static void ConfigureSerilog(ILoggingBuilder loggingBuilder)
    {
        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Information()
            .WriteTo.File(
                Path.Combine(AppContext.BaseDirectory, "logs", "CardDatabaseServer_.log"),
                rollingInterval: RollingInterval.Day,
                outputTemplate: "{Timestamp:yyyy-MM-dd HH:mm:ss.fff zzz} [{Level:u3}] {Message:lj}{NewLine}{Exception}")
            .CreateLogger();

        loggingBuilder.AddSerilog();
    }
}

