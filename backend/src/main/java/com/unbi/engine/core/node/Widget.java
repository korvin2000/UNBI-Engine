package com.unbi.engine.core.node;

import java.util.ArrayList;
import java.util.List;

/**
 * How an input renders when it is not driven by an edge.
 *
 * <p>A closed set on purpose. The frontend has one case per kind, and because this is sealed, adding
 * a case here is a compile error everywhere it must be handled — including the wire encoder and,
 * transitively, a reminder to add the Angular control.
 *
 * <p>The bar for a new kind is that no existing one can express the input honestly. {@code rows} and
 * {@code monospace} on a text field are not a new kind; a list of tags is, because a comma-separated
 * string is a parser the user has to get right in their head.
 */
public sealed interface Widget {

    /**
     * @param rows      preferred height for a multiline field; 0 leaves it to the frontend
     * @param monospace for content whose alignment carries meaning — a schema, a template
     * @param editor    offer a full-window editor beside the field. A prompt is written, re-read and
     *                  revised; six visible rows inside a node on a zoomable canvas is a keyhole to
     *                  do that through, and widening the node instead makes the graph unreadable.
     * @param library   preset node type whose saved values stock this field's template list, blank
     *                  for none. Naming a <em>preset type</em> rather than inventing a second store
     *                  is what keeps "a saved prompt" one mechanism: the Presets tab lists, searches
     *                  and deletes them already.
     * @param libraryKey which value inside those presets holds the text
     */
    record TextField(
            String placeholder,
            boolean multiline,
            int rows,
            boolean monospace,
            boolean editor,
            String library,
            String libraryKey) implements Widget {

        public TextField {
            rows = Math.max(0, rows);
            library = library == null ? "" : library;
            libraryKey = libraryKey == null ? "" : libraryKey;
            if (!library.isBlank() && libraryKey.isBlank()) {
                throw new IllegalArgumentException(
                        "A template library needs the value key holding the text, or nothing can be loaded from it");
            }
        }

        public static TextField of(String placeholder) {
            return new TextField(placeholder, false, 0, false, false, "", "");
        }

        public static TextField multiline(String placeholder) {
            return new TextField(placeholder, true, 0, false, false, "", "");
        }

        public static TextField multiline(String placeholder, int rows) {
            return new TextField(placeholder, true, rows, false, false, "", "");
        }

        /** For code, schemas and prompt templates. */
        public static TextField code(String placeholder, int rows) {
            return new TextField(placeholder, true, rows, true, false, "", "");
        }

        /** Long prose with a full-window editor and a named template library behind it. */
        public static TextField prose(String placeholder, int rows, String library, String libraryKey) {
            return new TextField(placeholder, true, rows, false, true, library, libraryKey);
        }

        /** Code or a template, with the same editor and library. */
        public static TextField code(String placeholder, int rows, String library, String libraryKey) {
            return new TextField(placeholder, true, rows, true, true, library, libraryKey);
        }

        public TextField withEditor() {
            return new TextField(placeholder, true, rows, monospace, true, library, libraryKey);
        }
    }

    /**
     * @param optional when true, an empty field is a value of its own — "do not send this" — rather
     *     than a number the user has not finished typing. That distinction is load-bearing for
     *     sampling parameters, where a temperature of zero and no temperature at all are different
     *     requests and one gateway honours only the second.
     * @param blankLabel what an empty optional field means, in the user's words. "unset" is right
     *     for a sampler and wrong for an output ceiling, where the honest reading of blank is
     *     "no limit" — and a field whose empty state has no name is a field nobody dares clear.
     */
    record NumberField(
            double min, double max, double step, String unit, boolean optional, String blankLabel)
            implements Widget {

        public NumberField {
            blankLabel = blankLabel == null || blankLabel.isBlank() ? "unset" : blankLabel.trim();
        }

        public NumberField(double min, double max, double step, String unit, boolean optional) {
            this(min, max, step, unit, optional, "unset");
        }

        public static NumberField of(double min, double max) {
            return new NumberField(min, max, 1, "", false, "unset");
        }

        public static NumberField of(double min, double max, double step) {
            return new NumberField(min, max, step, "", false, "unset");
        }

        /** A number that may be left blank, meaning "unset". */
        public static NumberField optional(double min, double max, double step) {
            return new NumberField(min, max, step, "", true, "unset");
        }

        /** A number that may be left blank, with a name for what blank means. */
        public static NumberField optional(double min, double max, double step, String unit, String blankLabel) {
            return new NumberField(min, max, step, unit, true, blankLabel);
        }
    }

    record Slider(double min, double max, double step) implements Widget {}

    record Toggle() implements Widget {}

    /**
     * @param optionsKey names a catalog the engine serves at runtime — credential names, saved
     *     presets — which the editor merges into {@code options}. Static options alone cannot express
     *     a list that changes while the engine is running, and hardcoding one in the frontend would
     *     undo the property that the frontend knows no node types.
     * @param allowCustom lets a value be typed as well as picked, for a list that can never be
     *     complete, such as model names
     * @param narrowing hides the options an upstream node says are unavailable; null offers them all
     */
    record Dropdown(List<Option> options, String optionsKey, boolean allowCustom, Narrowing narrowing)
            implements Widget {

        public Dropdown {
            if (options.isEmpty() && (optionsKey == null || optionsKey.isBlank()) && !allowCustom) {
                throw new IllegalArgumentException(
                        "A dropdown needs options, an options key, or a typable box; one with none of "
                                + "the three can never be used");
            }
            options = List.copyOf(options);
            optionsKey = optionsKey == null ? "" : optionsKey;
        }

        public Dropdown(List<Option> options, String optionsKey, boolean allowCustom) {
            this(options, optionsKey, allowCustom, null);
        }

        public static Dropdown of(String... valueLabelPairs) {
            return new Dropdown(pairs(valueLabelPairs), "", false, null);
        }

        /** Options served by the engine, optionally alongside a few fixed ones. */
        public static Dropdown fromCatalog(String optionsKey, boolean allowCustom, String... valueLabelPairs) {
            return new Dropdown(pairs(valueLabelPairs), optionsKey, allowCustom, null);
        }

        /** Free text with a list of suggestions behind it — a set that can never be complete. */
        public static Dropdown suggesting(String optionsKey, String... valueLabelPairs) {
            return new Dropdown(pairs(valueLabelPairs), optionsKey, true, null);
        }

        /**
         * A typable box whose list a node action fills in.
         *
         * <p>For a set that is neither fixed nor knowable at startup: which models one endpoint
         * serves depends on which endpoint is wired into this node, which is a question only the
         * node in front of the user can answer. Empty until something asks.
         */
        public static Dropdown discovered() {
            return new Dropdown(List.of(), "", true, null);
        }

        public Dropdown narrowedBy(Narrowing narrowing) {
            return new Dropdown(options, optionsKey, allowCustom, narrowing);
        }

        private static List<Option> pairs(String... valueLabelPairs) {
            if (valueLabelPairs.length % 2 != 0) {
                throw new IllegalArgumentException("Expected value/label pairs, got " + valueLabelPairs.length);
            }
            var options = new ArrayList<Option>();
            for (int i = 0; i < valueLabelPairs.length; i += 2) {
                options.add(new Option(valueLabelPairs[i], valueLabelPairs[i + 1]));
            }
            return options;
        }
    }

    /**
     * "Offer only the options the node wired into {@code socket} says it supports."
     *
     * <p>A rule rather than a hardcoded relationship, so the editor applies it without knowing which
     * nodes are involved: read the list-valued input {@code listKey} on whatever is wired into
     * {@code socket}, keep the options whose value appears there, and always keep {@code always}.
     * Nothing wired in means nothing is known, and an unknown is not a reason to hide a choice — so
     * every option is offered.
     *
     * <p>This is how "show only the response formats this model supports" happens on its own rather
     * than on a button press, and it costs the frontend no knowledge of models or response formats.
     *
     * @param always options offered whatever the upstream node says — the ones needing no capability
     */
    record Narrowing(String socket, String listKey, List<String> always) {

        public Narrowing {
            if (socket == null || socket.isBlank() || listKey == null || listKey.isBlank()) {
                throw new IllegalArgumentException("Narrowing needs the socket and the list it reads");
            }
            always = List.copyOf(always == null ? List.of() : always);
        }

        public static Narrowing from(String socket, String listKey, String... always) {
            return new Narrowing(socket, listKey, List.of(always));
        }
    }

    /**
     * Several of a fixed set, stored as a list of values.
     *
     * <p>Earns its place over a comma-separated text field because the set is closed and knowable:
     * a capability list typed by hand is a spelling test whose failure mode is a feature silently
     * not being sent.
     */
    record MultiSelect(List<Option> options) implements Widget {

        public MultiSelect {
            if (options.isEmpty()) {
                throw new IllegalArgumentException("A multi-select with no options cannot be used");
            }
            options = List.copyOf(options);
        }

        public static MultiSelect of(String... valueLabelPairs) {
            return new MultiSelect(Dropdown.pairs(valueLabelPairs));
        }
    }

    /**
     * An editable map, stored as an object.
     *
     * <p>For the places where the set of keys belongs to the gateway rather than to this engine —
     * extra headers, mostly. The alternative is a text field holding {@code a: b} pairs, which is a
     * format to document, a parser to write and an error message to invent.
     */
    record KeyValue(String keyPlaceholder, String valuePlaceholder) implements Widget {

        public static KeyValue of(String keyPlaceholder, String valuePlaceholder) {
            return new KeyValue(keyPlaceholder, valuePlaceholder);
        }
    }

    record DirectoryPicker() implements Widget {}

    record FilePicker(List<String> extensions) implements Widget {}

    /**
     * Several files, chosen one at a time and removable one at a time.
     *
     * <p>Not a {@link FilePicker} holding a comma-separated string: a Windows path contains no
     * commas but plenty of spaces, every separator is a character that can appear in a filename, and
     * "which of these twelve is the wrong one" is unanswerable when they are one line of text. The
     * value is a list, and the editor shows it as one.
     */
    record FileList(List<String> extensions) implements Widget {

        public FileList {
            extensions = List.copyOf(extensions == null ? List.of() : extensions);
        }

        public static FileList any() {
            return new FileList(List.of());
        }
    }

    /**
     * A reference to a named configuration the engine keeps, chosen from a list and edited in a
     * dialog of its own.
     *
     * <p>The value stored in the node is the profile's <em>id</em>, never its contents. That is the
     * whole reason this is not a dropdown that copies values in: a workflow saying "the endpoint
     * called openrouter" runs unchanged on a machine where that name points at a different URL,
     * which is what an environment-specific setting has to allow. The fields a profile holds are
     * declared by a {@code ProfileSchema} bean under {@code schema}, and the editor renders them
     * with these same widgets — it learns the shape from the engine, not from code about this node.
     *
     * @param schema the profile schema id, e.g. {@code llm.endpoint}
     */
    record Profile(String schema) implements Widget {

        public Profile {
            if (schema == null || schema.isBlank()) {
                throw new IllegalArgumentException("A profile widget needs the schema it picks from");
            }
        }

        public static Profile of(String schema) {
            return new Profile(schema);
        }
    }

    /**
     * The name of a secret the engine holds, chosen from the names it knows or added on the spot.
     *
     * <p>Its own kind rather than a dropdown over a catalog, because a credential has one behaviour
     * no other list has: the value can be <em>written</em> from the editor — name and key, once,
     * over {@code POST /api/credentials} — and can never be read back. A dropdown knows nothing of
     * that, and teaching it would put the word "credential" into the generic widget code, which is
     * the thing the descriptor-driven design exists to avoid.
     */
    record Credential() implements Widget {}

    record Option(String value, String label) {}
}
