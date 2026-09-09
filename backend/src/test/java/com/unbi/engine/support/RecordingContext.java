package com.unbi.engine.support;

import com.unbi.engine.core.node.NodeContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link NodeContext} for tests: inputs in, outputs and narration out.
 *
 * <p>The reason the node SPI is worth its narrowness. Testing a node needs no Spring context, no
 * engine, no graph and no socket — just a map of inputs and an assertion about what came back.
 */
public final class RecordingContext implements NodeContext {

    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<String, Object> outputs = new LinkedHashMap<>();
    private final List<String> logs = new ArrayList<>();
    private final List<Double> progressFractions = new ArrayList<>();
    private boolean cancelled;

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

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @SuppressWarnings("unchecked")
    public <T> T output(String key) {
        return (T) outputs.get(key);
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
}
