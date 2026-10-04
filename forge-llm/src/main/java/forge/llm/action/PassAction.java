package forge.llm.action;

/** Pass priority (priority windows) - always available, always id 0. */
public final class PassAction extends GameAction {
    public static final int ID = 0;

    private final String description;

    public PassAction(String description) {
        this.description = description;
    }

    @Override
    public Type type() {
        return Type.PASS;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String key() {
        return "pass";
    }
}
