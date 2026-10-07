package forge.llm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a plain-text model reply into an {@link AgentChoice}. Accepted shapes (case-insensitive):
 * <pre>
 *   ACTION 7            ACTIONS 3, 5, 9        PASS        NONE
 *   ACTION 7 'first strike', 'damage'          (a rules lookup: id plus quoted search phrases)
 *   REASON: free text, optional, may follow on its own line
 * </pre>
 * Used by agents that talk to a model through plain chat replies rather than a tool call.
 */
public final class ChoiceParser {
    private static final Pattern ACTION = Pattern.compile("(?im)^\\s*ACTIONS?\\s*[:=]?\\s*([0-9][0-9, \\t]*)");
    private static final Pattern PASS = Pattern.compile("(?im)^\\s*PASS\\b");
    private static final Pattern NONE = Pattern.compile("(?im)^\\s*NONE\\b");
    private static final Pattern REASON = Pattern.compile("(?is)\\bREASON(?:ING)?\\s*[:=]\\s*(.*)$");
    private static final Pattern QUOTED = Pattern.compile("'([^']{1,120})'|\"([^\"]{1,120})\"");

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
        int reasonAt = -1;
        Matcher r = REASON.matcher(reply);
        if (r.find()) {
            reasoning = r.group(1).trim();
            reasonAt = r.start();
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
                return AgentChoice.of(ids, reasoning).withSearchTerms(searchTerms(reply, a.end(), reasonAt));
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

    /**
     * The search phrases of a rules lookup: quoted phrases in the reply before the REASON text (a reason is
     * prose and its apostrophes are not phrases), or, when a model forgot the quotes, the comma-separated
     * words after the action ids on that line.
     */
    private static List<String> searchTerms(String reply, int afterIds, int reasonAt) {
        final List<String> terms = new ArrayList<>();
        final String beforeReason = reasonAt > 0 ? reply.substring(0, reasonAt) : reply;
        final Matcher q = QUOTED.matcher(beforeReason);
        while (q.find()) {
            terms.add(q.group(1) != null ? q.group(1) : q.group(2));
        }
        if (!terms.isEmpty()) {
            return terms;
        }
        final int eol = reply.indexOf('\n', afterIds);
        final String rest = reply.substring(afterIds, eol < 0 ? reply.length() : eol).trim();
        if (rest.isEmpty() || rest.toUpperCase(Locale.ROOT).startsWith("REASON")) {
            return List.of();
        }
        for (String part : rest.split(",")) {
            final String term = part.trim();
            if (!term.isEmpty() && term.length() <= 120) {
                terms.add(term);
            }
        }
        return terms.size() <= 6 ? terms : List.of();
    }
}
