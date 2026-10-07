package forge.llm.action;

/** Something the player owns that looks usable but cannot be used right now, with the engine-derived reason. */
public record UnavailableAction(String subject, String reason) {
    public String toPromptLine() {
        return subject + " - " + reason;
    }
}
