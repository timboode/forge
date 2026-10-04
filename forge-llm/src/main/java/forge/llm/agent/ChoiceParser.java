package forge.llm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a plain-text model reply into an {@link AgentChoice}. Accepted shapes (case-insensitive):
 * <pre>
 *   ACTION 7            ACTIONS 3, 5, 9        PASS        NONE
 *   REASON: free text, optional, may follow on its own line
 * </pre>
 * Used by agents that talk to a model through plain chat replies rather than a tool call.
 */
public final class ChoiceParser {
    private static final Pattern ACTION = Pattern.compile("(?im)^\\s*ACTIONS?\\s*[:=]?\\s*([0-9][0-9, \\t]*)");
    private static final Pattern PASS = Pattern.compile("(?im)^\\s*PASS\\b");
    private static final Pattern NONE = Pattern.compile("(?im)^\\s*NONE\\b");
    private static final Pattern REASON = Pattern.compile("(?is)\\bREASON(?:ING)?\\s*[:=]\\s*(.*)$");

    private ChoiceParser() {
    }

    /** @throws IllegalArgumentException if no recognizable answer is present */
    public static AgentChoice parse(String reply) {
        if (reply == null) {
            throw new IllegalArgumentException("empty reply");
        }
        // small models decorate: "ACTION [3]", "**ACTION 3**", "`ACTION 3`"
        reply = reply.replace("*", "").replace("`", "").replace("[", " ").replace("]", " ");
        String reasoning = "";
        Matcher r = REASON.matcher(reply);
        if (r.find()) {
            reasoning = r.group(1).trim();
        }
        Matcher a = ACTION.matcher(reply);
        if (a.find()) {
            List<Integer> ids = new ArrayList<>();
            for (String part : a.group(1).split("[, \\t]+")) {
                if (!part.isBlank()) {
                    ids.add(Integer.parseInt(part.trim()));
                }
            }
            if (!ids.isEmpty()) {
                return AgentChoice.of(ids, reasoning);
            }
        }
        if (PASS.matcher(reply).find()) {
            return AgentChoice.pass(reasoning);
        }
        if (NONE.matcher(reply).find()) {
            return AgentChoice.none(reasoning);
        }
        throw new IllegalArgumentException("no ACTION/PASS/NONE found in reply: " + reply);
    }
}
