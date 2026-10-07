package forge.llm.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the agent may and may not do at one decision point. Ids are assigned here: 0 is reserved
 * for the pass/none option of priority windows; real actions are numbered from 1.
 */
public final class ActionSet {
    private final List<GameAction> actions;
    private final List<UnavailableAction> unavailable;

    public ActionSet(List<? extends GameAction> actions, List<UnavailableAction> unavailable) {
        this.actions = new ArrayList<>(actions);
        this.unavailable = new ArrayList<>(unavailable);
        int next = 1;
        for (GameAction action : this.actions) {
            action.assignId(action instanceof PassAction ? PassAction.ID : next++);
        }
    }

    public static ActionSet empty() {
        return new ActionSet(Collections.emptyList(), Collections.emptyList());
    }

    public List<GameAction> actions() {
        return Collections.unmodifiableList(actions);
    }

    public List<UnavailableAction> unavailable() {
        return Collections.unmodifiableList(unavailable);
    }

    public boolean isEmpty() {
        return actions.stream().noneMatch(a -> !(a instanceof PassAction));
    }

    /** Actions plus a leading pass option, ids assigned. */
    public static ActionSet withPass(PassAction pass, List<? extends GameAction> actions, List<UnavailableAction> unavailable) {
        List<GameAction> all = new ArrayList<>();
        all.add(pass);
        all.addAll(actions);
        return new ActionSet(all, unavailable);
    }

    public GameAction byId(int id) {
        for (GameAction a : actions) {
            if (a.id() == id) {
                return a;
            }
        }
        return null;
    }

    /** The id the "Query MTG rules" option gets: one past the last real action. */
    public int queryOptionId() {
        int max = -1;
        for (GameAction a : actions) {
            max = Math.max(max, a.id());
        }
        return max + 1;
    }
}
