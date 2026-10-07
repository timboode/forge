using ModelContextProtocol.Protocol;
using ModelContextProtocol.Server;

namespace CardDatabaseMCPServer.Configuration;

/// <summary>
/// Configures the MCP server options for CardDatabase
/// </summary>
public static class McpServerConfiguration
{
    /// <summary>
    /// Configures MCP server options with capabilities
    /// </summary>
    public static void ConfigureOptions(McpServerOptions options)
    {
        options.Capabilities = new ServerCapabilities
        {
            Tools = new(),
            Resources = new(),
            Prompts = new(),
        };

        options.ServerInfo = new Implementation
        {
            Name = "CardDatabase",
            Version = "1.0.0"
        };

        options.ServerInstructions = "This is a CardDatabase MCP server that provides tools for querying Magic: The Gathering card data from the AllPrintings.sqlite database";

        // Register handlers (can be extended with custom handlers)
        options.Handlers = new()
        {
            // Add custom handlers here as needed
        };
    }
}

