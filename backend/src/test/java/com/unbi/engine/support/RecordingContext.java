package com.unbi.engine.support;

import com.unbi.engine.core.node.NodeContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link NodeContext} for tests: inputs in, outputs and narration out.
 *
 * <p>The reason the node SPI is worth its narrowness. Testing a node needs no Spring context, no
 * engine, no graph and no socket — just a map of inputs and an assertion about what came back.
 *
 * <p><b>Thread-safe on the recording side</b>, because a node is allowed to be concurrent and one of
 * them is: LLM Batch runs several items at once and every one of them logs and reports progress. A
 * plain {@code ArrayList} here made that a data race in the <em>test double</em>, which surfaced as
 * an {@code ArrayIndexOutOfBoundsException} from inside the node under test — a failure that looks
 * exactly like a bug in the node and is not one.
 */
public final class RecordingContext implements NodeContext {

    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<String, Object> outputs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final Map<String, StringBuilder> streams = new ConcurrentHashMap<>();
    private final List<Double> progressFractions = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public static RecordingContext with(Object... keysAndValues) {
        var context = new RecordingContext();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            context.inputs.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return context;
    }

    public RecordingContext and(String key, Object value) {
        inputs.put(key, value);
        return this;
    }

    public RecordingContext cancelledFromTheStart() {
        cancelled = true;
        return this;
    }

    @Override
    public Object rawInput(String key) {
        return inputs.get(key);
    }

    @Override
    public void output(String key, Object value) {
        outputs.put(key, value);
    }

    @Override
    public void progress(double fraction, String message) {
        progressFractions.add(fraction);
        if (message != null) {
            logs.add(message);
        }
    }

    @Override
    public void log(String message) {
        logs.add(message);
    }

    /** Recorded so a test can assert that a node streamed, and towards which port. */
    @Override
    public void stream(String key, String chunk) {
        var buffer = streams.computeIfAbsent(key, ignored -> new StringBuilder());
        // StringBuilder is not thread-safe either, and a node may stream from several threads.
        synchronized (buffer) {
            buffer.append(chunk);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @SuppressWarnings("unchecked")
    public <T> T output(String key) {
        return (T) outputs.get(key);
    }

    /**
     * An output as a plain Object.
     *
     * <p>The typed {@link #output(String)} infers its type from the assignment, which leaves an
     * assertion with no target type ambiguous. This is the form to use inside one.
     */
    public Object rawOutput(String key) {
        return outputs.get(key);
    }

    public Map<String, Object> outputs() {
        return Map.copyOf(outputs);
    }

    public List<String> logs() {
        return List.copyOf(logs);
    }

    public List<Double> progressFractions() {
        return List.copyOf(progressFractions);
    }

    /** Everything streamed towards one output port, in order. */
    public String streamed(String key) {
        var text = streams.get(key);
        return text == null ? "" : text.toString();
    }
}
