using System.Text.Json;
using CardDatabaseMCPServer.Tools;
using Microsoft.AspNetCore.Mvc;

namespace CardDatabaseMCPServer.Controllers;

/// <summary>
/// REST API controller for retrieving deck building session data.
/// Exposes deck data built through MCP tool calls to external clients.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class DeckController : ControllerBase
{
    private readonly DeckBuilderTools _deckBuilderTools;
    private readonly ILogger<DeckController> _logger;

    /// <summary>
    /// Initializes a new instance of the DeckController.
    /// </summary>
    /// <param name="deckBuilderTools">The deck builder tools service for accessing deck sessions.</param>
    /// <param name="logger">Logger instance for diagnostic logging.</param>
    public DeckController(DeckBuilderTools deckBuilderTools, ILogger<DeckController> logger)
    {
        _deckBuilderTools = deckBuilderTools;
        _logger = logger;
    }

    /// <summary>
    /// Gets the complete deck list for a deck session by its ID.
    /// </summary>
    /// <param name="deckId">The deck session ID from the in-memory deck sessions.</param>
    /// <returns>The complete deck list including commander, cards, and total count.</returns>
    /// <response code="200">Returns the deck data with commander, cards array, and total card count.</response>
    /// <response code="404">If the deck session with the specified ID does not exist.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpGet("{deckId:int}")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(DeckResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status404NotFound)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult GetDeckByDeckId(int deckId)
    {
        try
        {
            _logger.LogInformation("REST API request to get deck {DeckId}", deckId);

            // Call the existing GetFullDecklist method from DeckBuilderTools
            var jsonResult = _deckBuilderTools.GetFullDecklist(deckId);

            // Parse the JSON response to check for errors
            using var document = JsonDocument.Parse(jsonResult);
            var root = document.RootElement;

            // Check if the response contains an error
            if (root.TryGetProperty("error", out var errorElement))
            {
                var errorMessage = errorElement.GetString();
                _logger.LogWarning("Deck {DeckId} not found: {Error}", deckId, errorMessage);

                return NotFound(new ErrorResponse
                {
                    Error = errorMessage ?? $"Deck session {deckId} not found."
                });
            }

            // Parse the successful response into a typed object
            var response = new DeckResponse
            {
                Commander = root.TryGetProperty("commander", out var cmdElement) && cmdElement.ValueKind != JsonValueKind.Null
                    ? cmdElement.GetString() ?? string.Empty
                    : string.Empty,
                TotalCards = root.GetProperty("totalCount").GetInt32(),
                Cards = new List<CardEntry>()
            };

            if (root.TryGetProperty("cards", out var cardsElement))
            {
                foreach (var cardElement in cardsElement.EnumerateArray())
                {
                    response.Cards.Add(new CardEntry
                    {
                        Quantity = cardElement.GetProperty("quantity").GetInt32(),
                        Name = cardElement.GetProperty("name").GetString() ?? string.Empty
                    });
                }
            }

            _logger.LogInformation("Successfully retrieved deck {DeckId} with {TotalCards} cards", 
                deckId, response.TotalCards);

            return Ok(response);
        }
        catch (JsonException ex)
        {
            _logger.LogError(ex, "Failed to parse deck data for deck {DeckId}", deckId);
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = "Failed to parse deck data."
            });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error retrieving deck {DeckId}", deckId);
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = $"An error occurred while retrieving the deck: {ex.Message}"
            });
        }
    }

    /// <summary>
    /// Creates a new deck session without a commander (for non-Commander formats).
    /// </summary>
    /// <returns>The new deck session ID.</returns>
    /// <response code="200">Returns the new deck session ID.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpPost("begin-without-commander")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(BeginDeckResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult BeginNewDeckWithoutCommander()
    {
        try
        {
            _logger.LogInformation("REST API request to create new non-Commander deck session");

            var jsonResult = _deckBuilderTools.BeginNewDeck("Modern", null);

            using var document = JsonDocument.Parse(jsonResult);
            var root = document.RootElement;

            if (root.TryGetProperty("error", out var errorElement))
            {
                var errorMessage = errorElement.GetString();
                _logger.LogWarning("Failed to create deck session: {Error}", errorMessage);
                return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
                {
                    Error = errorMessage ?? "Failed to create deck session."
                });
            }

            var deckId = root.GetProperty("deckId").GetInt32();
            _logger.LogInformation("Successfully created non-Commander deck session {DeckId}", deckId);

            return Ok(new BeginDeckResponse { DeckId = deckId });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating non-Commander deck session");
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = $"An error occurred while creating the deck session: {ex.Message}"
            });
        }
    }

    /// <summary>
    /// Adds cards to a non-Commander deck session.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <param name="request">The request containing card list and sideboard flag.</param>
    /// <returns>Result of the add operation.</returns>
    /// <response code="200">Returns the result of adding cards.</response>
    /// <response code="404">If the deck session does not exist.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpPost("{deckId:int}/add-cards")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(AddCardsResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status404NotFound)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult AddCardsToNonCommanderDeck(int deckId, [FromBody] AddCardsRequest request)
    {
        try
        {
            _logger.LogInformation("REST API request to add cards to deck {DeckId}, toSideboard: {ToSideboard}",
                deckId, request.ToSideboard);

            var jsonResult = _deckBuilderTools.EditDeck(deckId, cardsToRemove: null, cardsToAdd: request.CardList, toSideboard: request.ToSideboard);

            using var document = JsonDocument.Parse(jsonResult);
            var root = document.RootElement;

            if (root.TryGetProperty("error", out var errorElement))
            {
                var errorMessage = errorElement.GetString();
                _logger.LogWarning("Failed to add cards to deck {DeckId}: {Error}", deckId, errorMessage);

                if (errorMessage?.Contains("not found") == true)
                {
                    return NotFound(new ErrorResponse { Error = errorMessage });
                }

                return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
                {
                    Error = errorMessage ?? "Failed to add cards to deck."
                });
            }

            // EditDeck returns addedCards array and addFailures array
            var cardsAddedCount = 0;
            if (root.TryGetProperty("addedCards", out var addedCardsElement))
            {
                cardsAddedCount = addedCardsElement.GetArrayLength();
            }

            var response = new AddCardsResponse
            {
                Message = $"Added {cardsAddedCount} cards to deck",
                CardsAdded = cardsAddedCount,
                InvalidCards = new List<string>()
            };

            if (root.TryGetProperty("addFailures", out var failuresElement))
            {
                foreach (var failure in failuresElement.EnumerateArray())
                {
                    if (failure.TryGetProperty("name", out var nameElement))
                    {
                        response.InvalidCards.Add(nameElement.GetString() ?? "");
                    }
                }
            }

            _logger.LogInformation("Successfully added {CardsAdded} cards to deck {DeckId}",
                response.CardsAdded, deckId);

            return Ok(response);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error adding cards to deck {DeckId}", deckId);
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = $"An error occurred while adding cards: {ex.Message}"
            });
        }
    }

    /// <summary>
    /// Gets the complete deck list including sideboard for a non-Commander deck session.
    /// </summary>
    /// <param name="deckId">The deck session ID.</param>
    /// <returns>The complete deck list with mainboard and sideboard.</returns>
    /// <response code="200">Returns the deck data with mainboard, sideboard, and counts.</response>
    /// <response code="404">If the deck session does not exist.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpGet("{deckId:int}/with-sideboard")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(DeckWithSideboardResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status404NotFound)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult GetDeckWithSideboard(int deckId)
    {
        try
        {
            _logger.LogInformation("REST API request to get deck with sideboard {DeckId}", deckId);

            var jsonResult = _deckBuilderTools.GetFullDecklistWithSideboard(deckId);

            using var document = JsonDocument.Parse(jsonResult);
            var root = document.RootElement;

            if (root.TryGetProperty("error", out var errorElement))
            {
                var errorMessage = errorElement.GetString();
                _logger.LogWarning("Deck {DeckId} not found: {Error}", deckId, errorMessage);
                return NotFound(new ErrorResponse
                {
                    Error = errorMessage ?? $"Deck session {deckId} not found."
                });
            }

            var response = new DeckWithSideboardResponse
            {
                Mainboard = new List<CardEntry>(),
                Sideboard = new List<CardEntry>(),
                MainboardTotal = root.GetProperty("mainboardCount").GetInt32(),
                SideboardTotal = root.GetProperty("sideboardCount").GetInt32(),
                IsCommanderFormat = root.TryGetProperty("format", out var formatElement)
                    && formatElement.GetString()?.Equals("Commander", StringComparison.OrdinalIgnoreCase) == true
            };

            if (root.TryGetProperty("mainboard", out var mainboardElement))
            {
                foreach (var cardElement in mainboardElement.EnumerateArray())
                {
                    response.Mainboard.Add(new CardEntry
                    {
                        Quantity = cardElement.GetProperty("quantity").GetInt32(),
                        Name = cardElement.GetProperty("name").GetString() ?? string.Empty
                    });
                }
            }

            if (root.TryGetProperty("sideboard", out var sideboardElement))
            {
                foreach (var cardElement in sideboardElement.EnumerateArray())
                {
                    response.Sideboard.Add(new CardEntry
                    {
                        Quantity = cardElement.GetProperty("quantity").GetInt32(),
                        Name = cardElement.GetProperty("name").GetString() ?? string.Empty
                    });
                }
            }

            _logger.LogInformation("Successfully retrieved deck {DeckId} with {MainboardTotal} mainboard and {SideboardTotal} sideboard cards",
                deckId, response.MainboardTotal, response.SideboardTotal);

            return Ok(response);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error retrieving deck with sideboard {DeckId}", deckId);
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = $"An error occurred while retrieving the deck: {ex.Message}"
            });
        }
    }

    /// <summary>
    /// Gets the most recently created deck session ID.
    /// Used as a reliable fallback when deck ID cannot be extracted from LLM text.
    /// </summary>
    /// <returns>The most recent deck session ID.</returns>
    /// <response code="200">Returns the latest deck session ID.</response>
    /// <response code="404">If no deck sessions exist.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpGet("latest-session")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(object), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status404NotFound)]
    public IActionResult GetLatestSession()
    {
        try
        {
            _logger.LogInformation("REST API request to get latest deck session");

            var sessionIds = DeckBuilderTools._deckSessions.Keys.OrderByDescending(id => id).ToList();
            if (sessionIds.Count == 0)
            {
                _logger.LogWarning("No deck sessions found");
                return NotFound(new ErrorResponse { Error = "No deck sessions exist" });
            }

            var latestId = sessionIds[0];
            _logger.LogInformation("Latest deck session ID: {DeckId}", latestId);
            return Ok(new { deckId = latestId });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting latest deck session");
            return StatusCode(500, new ErrorResponse { Error = $"Internal error: {ex.Message}" });
        }
    }

    /// <summary>
    /// Deletes a deck session by its ID.
    /// </summary>
    /// <param name="deckId">The deck session ID to delete.</param>
    /// <returns>Success or error response.</returns>
    /// <response code="200">The deck session was successfully deleted.</response>
    /// <response code="404">If the deck session does not exist.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpDelete("{deckId:int}")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(DeleteDeckResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status404NotFound)]
    [ProducesResponseType(typeof(ErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult DeleteDeck(int deckId)
    {
        try
        {
            _logger.LogInformation("REST API request to delete deck {DeckId}", deckId);

            // Access the static deck sessions dictionary directly
            bool removed = DeckBuilderTools._deckSessions.TryRemove(deckId, out _);

            if (!removed)
            {
                _logger.LogWarning("Deck {DeckId} not found for deletion", deckId);
                return NotFound(new ErrorResponse
                {
                    Error = $"Deck session {deckId} not found."
                });
            }

            _logger.LogInformation("Successfully deleted deck {DeckId}", deckId);
            return Ok(new DeleteDeckResponse { Message = $"Deck session {deckId} deleted successfully." });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting deck {DeckId}", deckId);
            return StatusCode(StatusCodes.Status500InternalServerError, new ErrorResponse
            {
                Error = $"An error occurred while deleting the deck: {ex.Message}"
            });
        }
    }
}

/// <summary>
/// Response model for beginning a new deck session.
/// </summary>
public class BeginDeckResponse
{
    /// <summary>
    /// The new deck session ID.
    /// </summary>
    public int DeckId { get; set; }
}

/// <summary>
/// Request model for adding cards to a deck.
/// </summary>
public class AddCardsRequest
{
    /// <summary>
    /// The card list in format "quantity cardname" per line.
    /// </summary>
    public string CardList { get; set; } = string.Empty;

    /// <summary>
    /// Whether to add cards to the sideboard instead of mainboard.
    /// </summary>
    public bool ToSideboard { get; set; } = false;
}

/// <summary>
/// Response model for adding cards to a deck.
/// </summary>
public class AddCardsResponse
{
    /// <summary>
    /// Success message.
    /// </summary>
    public string Message { get; set; } = string.Empty;

    /// <summary>
    /// Number of cards added.
    /// </summary>
    public int CardsAdded { get; set; }

    /// <summary>
    /// List of card names that were not found in the database.
    /// </summary>
    public List<string> InvalidCards { get; set; } = new();
}

/// <summary>
/// Response model for deck data with sideboard.
/// </summary>
public class DeckWithSideboardResponse
{
    /// <summary>
    /// Array of mainboard card entries.
    /// </summary>
    public List<CardEntry> Mainboard { get; set; } = new();

    /// <summary>
    /// Array of sideboard card entries.
    /// </summary>
    public List<CardEntry> Sideboard { get; set; } = new();

    /// <summary>
    /// Total number of cards in the mainboard.
    /// </summary>
    public int MainboardTotal { get; set; }

    /// <summary>
    /// Total number of cards in the sideboard.
    /// </summary>
    public int SideboardTotal { get; set; }

    /// <summary>
    /// Whether this is a Commander format deck.
    /// </summary>
    public bool IsCommanderFormat { get; set; }
}

/// <summary>
/// Response model for deleting a deck.
/// </summary>
public class DeleteDeckResponse
{
    /// <summary>
    /// Success message.
    /// </summary>
    public string Message { get; set; } = string.Empty;
}

/// <summary>
/// Response model for deck data.
/// </summary>
public class DeckResponse
{
    /// <summary>
    /// The commander card name.
    /// </summary>
    public string Commander { get; set; } = string.Empty;

    /// <summary>
    /// Array of card objects with quantity and name properties.
    /// </summary>
    public List<CardEntry> Cards { get; set; } = new();

    /// <summary>
    /// Total number of cards in the deck (including commander).
    /// </summary>
    public int TotalCards { get; set; }
}

/// <summary>
/// Represents a card entry in the deck.
/// </summary>
public class CardEntry
{
    /// <summary>
    /// The quantity of this card in the deck.
    /// </summary>
    public int Quantity { get; set; }

    /// <summary>
    /// The name of the card.
    /// </summary>
    public string Name { get; set; } = string.Empty;
}

/// <summary>
/// Error response model.
/// </summary>
public class ErrorResponse
{
    /// <summary>
    /// The error message.
    /// </summary>
    public string Error { get; set; } = string.Empty;
}

