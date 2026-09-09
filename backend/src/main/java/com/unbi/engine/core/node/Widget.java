package com.unbi.engine.core.node;

import java.util.List;

/**
 * How an input renders when it is not driven by an edge.
 *
 * <p>A closed set on purpose. The frontend has one component per case, and because this is sealed,
 * adding a case here is a compile error everywhere it must be handled — including the wire encoder
 * and, transitively, a reminder to add the Angular widget.
 */
public sealed interface Widget {

    record TextField(String placeholder, boolean multiline) implements Widget {
        public static TextField of(String placeholder) {
            return new TextField(placeholder, false);
        }

        public static TextField multiline(String placeholder) {
            return new TextField(placeholder, true);
        }
    }

    record NumberField(double min, double max, double step, String unit) implements Widget {
        public static NumberField of(double min, double max) {
            return new NumberField(min, max, 1, "");
        }
    }

    record Slider(double min, double max, double step) implements Widget {}

    record Toggle() implements Widget {}

    record Dropdown(List<Option> options) implements Widget {
        public Dropdown {
            if (options.isEmpty()) {
                throw new IllegalArgumentException("A dropdown with no options cannot be used");
            }
            options = List.copyOf(options);
        }

        public static Dropdown of(String... valueLabelPairs) {
            if (valueLabelPairs.length % 2 != 0) {
                throw new IllegalArgumentException("Expected value/label pairs, got " + valueLabelPairs.length);
            }
            var options = new java.util.ArrayList<Option>();
            for (int i = 0; i < valueLabelPairs.length; i += 2) {
                options.add(new Option(valueLabelPairs[i], valueLabelPairs[i + 1]));
            }
            return new Dropdown(options);
        }
    }

    record DirectoryPicker() implements Widget {}

    record FilePicker(List<String> extensions) implements Widget {}

    record Option(String value, String label) {}
}
