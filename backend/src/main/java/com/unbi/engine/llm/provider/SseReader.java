package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.spec.LlmFailure;

/** Stateful SSE framing. An unterminated final event is deliberately never dispatched. */
final class SseReader {
    static final int MAX_FRAME = 16 * 1024 * 1024;
    record Event(String type, String data) {}

    private final StringBuilder data = new StringBuilder();
    private String type = "";
    private boolean first = true;
    private boolean hasData;
    private long size;

    Event accept(String line) {
        if (first) {
            first = false;
            if (line.startsWith("\ufeff")) line = line.substring(1);
        }
        if (line.isEmpty()) {
            var event = hasData ? new Event(type, data.substring(0, data.length() - 1)) : null;
            data.setLength(0);
            type = "";
            hasData = false;
            size = 0;
            return event;
        }
        size += utf8Size(line) + 1L;
        if (size > MAX_FRAME) throw malformed();
        if (line.startsWith(":")) return null;
        int colon = line.indexOf(':');
        var field = colon < 0 ? line : line.substring(0, colon);
        var value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "data" -> { data.append(value).append('\n'); hasData = true; }
            case "event" -> type = value;
            default -> { }
        }
        return null;
    }

    private static long utf8Size(String value) {
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x80) bytes++;
            else if (ch < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(ch) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += 3;
        }
        return bytes;
    }

    static LlmFailure malformed() {
        return new LlmFailure(LlmFailure.Kind.RESPONSE_FORMAT, "The endpoint sent an invalid or oversized event");
    }
}
