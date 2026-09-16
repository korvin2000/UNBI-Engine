package com.unbi.engine.nodes.llm;

import com.unbi.engine.llm.prompt.PromptTemplate;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.nodes.files.model.FileRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which requests one LLM Request node makes, worked out from what is wired into it.
 *
 * <p>The rule is the one a spreadsheet user already knows. Every input that can carry several
 * values — system prompts, user prompts, data items, attachments — is a <em>lane</em>. A lane
 * holding one value applies to every request; a lane holding several makes several requests. Two
 * or more long lanes are either paired up by position (the third system prompt with the third
 * item) or crossed (every system prompt against every item), and which of the two is a setting on
 * the node rather than something inferred.
 *
 * <p>Pure: a plan is a function of its inputs and touches no network, which is what makes every
 * shape of batch — one file per request, all files in one request, three prompts against one
 * document, X prompts by Y prompts — a table in a unit test rather than a run against a gateway.
 */
final class RequestPlan {

    /** How several long lanes combine. */
    enum Combine {
        /** Same length, matched by position. A lane of one still applies to every request. */
        PAIR,
        /** Every combination: the product of the lane lengths. */
        CROSS;

        static Combine of(String raw) {
            return "cross".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? CROSS : PAIR;
        }
    }

    /**
     * One request, fully rendered.
     *
     * @param index    0-based position in the plan; the outputs keep this order
     * @param system   the rendered system prompt, blank for none
     * @param user     the rendered user prompt, blank when attachments alone carry the request
     * @param item     the data item this request is about, or null when the plan has no data lane
     * @param bindings what the templates were rendered against, for the log
     */
    record Request(
            int index,
            String system,
            String user,
            Object item,
            List<Attachment> attachments,
            Map<String, Object> bindings) {

        /** Short enough for a log line, specific enough to find the request again. */
        String label(int total) {
            var about = switch (item) {
                case null -> attachments.size() == 1 ? attachments.getFirst().name() : "";
                case FileRef file -> file.name();
                case Map<?, ?> record -> record.isEmpty() ? "record" : shorten(String.valueOf(record.values().iterator().next()));
                default -> shorten(String.valueOf(item));
            };
            var position = total > 1 ? "%d of %d".formatted(index + 1, total) : "request";
            return about.isBlank() ? position : position + " (" + about + ")";
        }

        private static String shorten(String text) {
            var line = text.strip().replace('\n', ' ');
            return line.length() <= 48 ? line : line.substring(0, 48) + "…";
        }
    }

    /**
     * What a plan is built from. Lists with one entry apply to every request.
     *
     * @param items null when nothing is wired into Data; an empty list is an error the plan reports
     */
    record Inputs(
            List<String> systems,
            List<String> users,
            List<Object> items,
            List<Attachment> attachments,
            boolean attachEach,
            Combine combine,
            Map<String, Object> shared,
            boolean strict) {}

    private RequestPlan() {}

    static List<Request> plan(Inputs inputs) {
        var systems = inputs.systems().isEmpty() ? List.of("") : inputs.systems();
        var users = inputs.users().isEmpty() ? List.of("") : inputs.users();
        if (inputs.items() != null && inputs.items().isEmpty()) {
            throw new IllegalStateException("Data is wired in but empty, so there is nothing to ask about.");
        }
        var items = inputs.items() == null ? java.util.Collections.<Object>singletonList(null) : inputs.items();
        var groups = attachmentGroups(inputs.attachments(), inputs.attachEach());

        var lanes = List.of(
                new Lane("system prompts", systems.size()),
                new Lane("user prompts", users.size()),
                new Lane("data items", items.size()),
                new Lane("attachments", groups.size()));
        var positions = inputs.combine() == Combine.CROSS ? cross(lanes) : pair(lanes);

        var requests = new ArrayList<Request>(positions.size());
        for (var at : positions) {
            var index = requests.size();
            var item = items.get(at[2]);
            var attachments = groups.get(at[3]);
            var bindings = bindings(inputs.shared(), item, attachments, index, positions.size());
            var system = render(systems.get(at[0]), bindings, inputs.strict(), "system prompt", index);
            var user = render(users.get(at[1]), bindings, inputs.strict(), "user prompt", index);
            if (user.isBlank() && attachments.isEmpty()) {
                throw new IllegalStateException(positions.size() == 1
                        ? "This node needs a user prompt, or at least one attachment to talk about."
                        : "Request %d has neither a user prompt nor an attachment.".formatted(index + 1));
            }
            requests.add(new Request(index, system, user, item, attachments, bindings));
        }
        return List.copyOf(requests);
    }

    /** "All in one request" is one group; "one request per attachment" is one group each. */
    private static List<List<Attachment>> attachmentGroups(List<Attachment> attachments, boolean each) {
        if (!each || attachments.isEmpty()) {
            return List.of(List.copyOf(attachments));
        }
        return attachments.stream().map(List::of).toList();
    }

    private record Lane(String name, int size) {}

    /** Positions for pairing: every long lane must agree on a length. */
    private static List<int[]> pair(List<Lane> lanes) {
        var long_ = lanes.stream().filter(lane -> lane.size() > 1).toList();
        var lengths = long_.stream().map(Lane::size).distinct().toList();
        if (lengths.size() > 1) {
            var got = long_.stream()
                    .map(lane -> lane.size() + " " + lane.name())
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException(
                    ("Pairing by position needs lists of the same length, but got %s. Switch Combine Lists to "
                            + "\"Every combination\", or make them the same length.").formatted(got));
        }
        var count = lengths.isEmpty() ? 1 : lengths.getFirst();
        var positions = new ArrayList<int[]>(count);
        for (int k = 0; k < count; k++) {
            var at = new int[lanes.size()];
            for (int lane = 0; lane < lanes.size(); lane++) {
                at[lane] = lanes.get(lane).size() > 1 ? k : 0;
            }
            positions.add(at);
        }
        return positions;
    }

    /** Positions for the product, first lane outermost so a system prompt's requests stay together. */
    private static List<int[]> cross(List<Lane> lanes) {
        var positions = new ArrayList<int[]>();
        var at = new int[lanes.size()];
        while (true) {
            positions.add(at.clone());
            int lane = lanes.size() - 1;
            while (lane >= 0 && ++at[lane] >= lanes.get(lane).size()) {
                at[lane] = 0;
                lane--;
            }
            if (lane < 0) {
                return positions;
            }
        }
    }

    /**
     * What a template can read for one request.
     *
     * <p>Named variables come first and the request's own facts are laid over them, because
     * {@code item} and {@code index} mean one thing here and a Variables node binding the same
     * name would be shadowing the batch by accident. A record's fields and a file's name are added
     * only where nothing else claimed the name, so {@code {{title}}} reaches a dataset column
     * without stopping anyone from binding a {@code title} of their own.
     */
    private static Map<String, Object> bindings(
            Map<String, Object> shared, Object item, List<Attachment> attachments, int index, int total) {
        var bindings = new LinkedHashMap<String, Object>(shared);
        bindings.put("index", index + 1);
        bindings.put("count", total);
        if (item != null) {
            bindings.put("item", item);
            if (item instanceof Map<?, ?> record) {
                record.forEach((key, value) -> {
                    if (key != null && value != null) {
                        bindings.putIfAbsent(String.valueOf(key), value);
                    }
                });
            }
            if (item instanceof FileRef file) {
                bindings.putIfAbsent("name", file.name());
                bindings.putIfAbsent("path", file.path());
            }
        }
        if (attachments.size() == 1) {
            bindings.putIfAbsent("file", attachments.getFirst().name());
            bindings.putIfAbsent("name", attachments.getFirst().name());
        }
        return bindings;
    }

    private static String render(String template, Map<String, Object> bindings, boolean strict, String what, int index) {
        try {
            return PromptTemplate.render(template, bindings, strict).trim();
        } catch (PromptTemplate.MissingVariableException missing) {
            throw new IllegalStateException(
                    "Request %d, %s: %s".formatted(index + 1, what, missing.getMessage()), missing);
        }
    }
}
