namespace CardDatabaseMCPServer.Models;

/// <summary>
/// Represents detailed card information returned by MCP tools.
/// Includes card properties, legalities, rulings, and identifiers.
/// </summary>
public class CardDetails
{
    // Core card properties
    public string? Name { get; set; }
    public string? ManaCost { get; set; }
    public float? ManaValue { get; set; }
    public string? Type { get; set; }
    public string? Types { get; set; }
    public string? Subtypes { get; set; }
    public string? Supertypes { get; set; }
    public string? Text { get; set; }
    public string? OriginalText { get; set; }
    public string? Power { get; set; }
    public string? Toughness { get; set; }
    public string? Loyalty { get; set; }
    public string? Colors { get; set; }
    public string? ColorIdentity { get; set; }
    public string? Keywords { get; set; }
    public string? Rarity { get; set; }
    public string? Availability { get; set; }
    public string? OriginalReleaseDate { get; set; }

    // Price data (from card-prices.sqlite)
    /// <summary>
    /// Current paper retail price in USD. -1.0 if not available.
    /// </summary>
    public double PaperPrice { get; set; } = -1.0;

    /// <summary>
    /// Current MTGO retail price in USD. -1.0 if not available.
    /// </summary>
    public double MtgoPrice { get; set; } = -1.0;

    /// <summary>
    /// The number of times this card appears across all tracked decks.
    /// 0 if the card is not found in the frequency database.
    /// </summary>
    public long CardUseFrequency { get; set; } = 0;

    /// <summary>
    /// The number of times this card appears across tracked decks in the last 30 days.
    /// 0 if the card is not found in the frequency database or windowed data is unavailable.
    /// </summary>
    public long CardUseFrequencyLast30Days { get; set; } = 0;

    /// <summary>
    /// The number of times this card appears across tracked decks in the last 90 days.
    /// 0 if the card is not found in the frequency database or windowed data is unavailable.
    /// </summary>
    public long CardUseFrequencyLast90Days { get; set; } = 0;

    // Related data
    public CardLegalitiesInfo? Legalities { get; set; }
    public List<CardRulingInfo>? Rulings { get; set; }
    public CardIdentifiersInfo? Identifiers { get; set; }

    /// <summary>
    /// Creates a CardDetails instance from a Card model.
    /// </summary>
    public static CardDetails FromCard(Card card)
    {
        return new CardDetails
        {
            Name = card.Name,
            ManaCost = card.ManaCost,
            ManaValue = card.ManaValue,
            Type = card.Type,
            Types = card.Types,
            Subtypes = card.Subtypes,
            Supertypes = card.Supertypes,
            Text = card.Text,
            OriginalText = card.OriginalText,
            Power = card.Power,
            Toughness = card.Toughness,
            Loyalty = card.Loyalty,
            Colors = card.Colors,
            ColorIdentity = card.ColorIdentity,
            Keywords = card.Keywords,
            Rarity = card.Rarity,
            Availability = card.Availability,
            OriginalReleaseDate = card.OriginalReleaseDate,
            PaperPrice = -1.0,
            MtgoPrice = -1.0
        };
    }
}

/// <summary>
/// Represents card legality information across formats.
/// </summary>
public class CardLegalitiesInfo
{
    public string? Standard { get; set; }
    public string? Modern { get; set; }
    public string? Legacy { get; set; }
    public string? Vintage { get; set; }
    public string? Commander { get; set; }
    public string? Pioneer { get; set; }
    public string? Pauper { get; set; }
    public string? Historic { get; set; }
    public string? Alchemy { get; set; }
    public string? Brawl { get; set; }
    public string? Timeless { get; set; }
    public string? Oathbreaker { get; set; }

    public static CardLegalitiesInfo FromCardLegalities(CardLegalities legalities)
    {
        return new CardLegalitiesInfo
        {
            Standard = legalities.Standard,
            Modern = legalities.Modern,
            Legacy = legalities.Legacy,
            Vintage = legalities.Vintage,
            Commander = legalities.Commander,
            Pioneer = legalities.Pioneer,
            Pauper = legalities.Pauper,
            Historic = legalities.Historic,
            Alchemy = legalities.Alchemy,
            Brawl = legalities.Brawl,
            Timeless = legalities.Timeless,
            Oathbreaker = legalities.Oathbreaker
        };
    }
}

/// <summary>
/// Represents a single card ruling.
/// </summary>
public class CardRulingInfo
{
    public DateTime? Date { get; set; }
    public string? Text { get; set; }

    public static CardRulingInfo FromCardRulings(CardRulings ruling)
    {
        return new CardRulingInfo
        {
            Date = ruling.Date,
            Text = ruling.Text
        };
    }
}

/// <summary>
/// Represents card identifiers across platforms.
/// </summary>
public class CardIdentifiersInfo
{
    public string? ScryfallId { get; set; }
    public string? ScryfallOracleId { get; set; }
    public string? MultiverseId { get; set; }
    public string? MtgArenaId { get; set; }
    public string? TcgplayerProductId { get; set; }
    public string? CardKingdomId { get; set; }

    public static CardIdentifiersInfo FromCardIdentifiers(CardIdentifiers identifiers)
    {
        return new CardIdentifiersInfo
        {
            ScryfallId = identifiers.ScryfallId,
            ScryfallOracleId = identifiers.ScryfallOracleId,
            MultiverseId = identifiers.MultiverseId,
            MtgArenaId = identifiers.MtgArenaId,
            TcgplayerProductId = identifiers.TcgplayerProductId,
            CardKingdomId = identifiers.CardKingdomId
        };
    }
}

