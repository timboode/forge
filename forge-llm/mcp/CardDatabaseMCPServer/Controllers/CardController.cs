using CardDatabaseMCPServer.Data;
using Microsoft.AspNetCore.Mvc;

namespace CardDatabaseMCPServer.Controllers;

/// <summary>
/// REST API controller for querying card data from the database.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class CardController : ControllerBase
{
    private readonly CardDatabaseContext _dbContext;
    private readonly PriceDatabaseContext _priceDbContext;
    private readonly ILogger<CardController> _logger;

    /// <summary>
    /// Initializes a new instance of the CardController.
    /// </summary>
    public CardController(
        CardDatabaseContext dbContext,
        PriceDatabaseContext priceDbContext,
        ILogger<CardController> logger)
    {
        _dbContext = dbContext;
        _priceDbContext = priceDbContext;
        _logger = logger;
    }

    /// <summary>
    /// Gets mythic rare cards from a deck list that are under the specified maximum price.
    /// </summary>
    /// <param name="request">The request containing maxPrice and deckList.</param>
    /// <returns>List of mythic rare cards under the max price with their quantities and prices.</returns>
    /// <response code="200">Returns the list of qualifying mythic rare cards.</response>
    /// <response code="400">If the request is invalid.</response>
    /// <response code="500">If an internal error occurs during processing.</response>
    [HttpPost("mythic-rares-with-max-price")]
    [Produces("application/json")]
    [ProducesResponseType(typeof(MythicRaresResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(CardErrorResponse), StatusCodes.Status400BadRequest)]
    [ProducesResponseType(typeof(CardErrorResponse), StatusCodes.Status500InternalServerError)]
    public IActionResult GetMythicalRaresWithMaxPrice([FromBody] MythicRaresRequest request)
    {
        try
        {
            if (request.DeckList == null || request.DeckList.Count == 0)
            {
                return BadRequest(new CardErrorResponse { Error = "DeckList is required and cannot be empty." });
            }

            if (request.MaxPrice <= 0)
            {
                return BadRequest(new CardErrorResponse { Error = "MaxPrice must be greater than 0." });
            }

            _logger.LogInformation(
                "REST API request to get mythic rares with max price ${MaxPrice:F2} from deck with {CardCount} unique cards",
                request.MaxPrice, request.DeckList.Count);

            var mythicRares = new List<MythicRareCard>();

            foreach (var kvp in request.DeckList)
            {
                var cardName = kvp.Key;
                var quantity = kvp.Value;

                // Find the card in the database by name (case-insensitive)
                var card = _dbContext.Cards
                    .FirstOrDefault(c => c.Name != null && 
                        c.Name.ToLower() == cardName.ToLower());

                if (card == null)
                {
                    _logger.LogDebug("Card not found: {CardName}", cardName);
                    continue;
                }

                // Check if the card is mythic rare
                if (string.IsNullOrEmpty(card.Rarity) ||
                    !card.Rarity.Equals("mythic", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                // Get the paper price
                var paperPrice = _priceDbContext.GetPaperPrice(card.Uuid);

                // Check if under max price (prices of 1000000000 indicate no price data)
                if (paperPrice >= 1000000000 || paperPrice > request.MaxPrice)
                {
                    continue;
                }

                mythicRares.Add(new MythicRareCard
                {
                    Name = card.Name ?? cardName,
                    Quantity = quantity,
                    Price = paperPrice
                });
            }

            _logger.LogInformation("Found {Count} mythic rare cards under ${MaxPrice:F2}",
                mythicRares.Count, request.MaxPrice);

            return Ok(new MythicRaresResponse
            {
                Cards = mythicRares,
                TotalCount = mythicRares.Count
            });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting mythic rares with max price");
            return StatusCode(StatusCodes.Status500InternalServerError, new CardErrorResponse
            {
                Error = $"An error occurred: {ex.Message}"
            });
        }
    }
}

/// <summary>
/// Request model for getting mythic rares with max price.
/// </summary>
public class MythicRaresRequest
{
    /// <summary>
    /// Maximum price threshold for mythic rare cards.
    /// </summary>
    public double MaxPrice { get; set; }

    /// <summary>
    /// Dictionary of card names to quantities.
    /// </summary>
    public Dictionary<string, int> DeckList { get; set; } = new();
}

/// <summary>
/// Response model for mythic rares query.
/// </summary>
public class MythicRaresResponse
{
    /// <summary>
    /// List of qualifying mythic rare cards.
    /// </summary>
    public List<MythicRareCard> Cards { get; set; } = new();

    /// <summary>
    /// Total count of qualifying mythic rare cards.
    /// </summary>
    public int TotalCount { get; set; }
}

/// <summary>
/// Represents a mythic rare card in the response.
/// </summary>
public class MythicRareCard
{
    /// <summary>
    /// The card name.
    /// </summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>
    /// The quantity of this card in the deck.
    /// </summary>
    public int Quantity { get; set; }

    /// <summary>
    /// The paper price of the card.
    /// </summary>
    public double Price { get; set; }
}

/// <summary>
/// Error response model for card queries.
/// </summary>
public class CardErrorResponse
{
    /// <summary>
    /// The error message.
    /// </summary>
    public string Error { get; set; } = string.Empty;
}

