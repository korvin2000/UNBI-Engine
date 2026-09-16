package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.llm.provider.StreamSink;

/**
 * Sends a provider's partial output to the editor, and the editor's cancel back to the provider.
 *
 * <p>The adapter between two narrow interfaces that deliberately do not know about each other: a
 * provider knows nothing about nodes, and a node context knows nothing about HTTP. This is the ten
 * lines that join them.
 *
 * <p>Progress is reported against an <em>estimated</em> length rather than a known one, because
 * nothing knows how long an answer will be until it stops. The estimate is the point: a bar that
 * moves is what tells the user the model is still writing, and it is capped just short of full so
 * that reaching the end still means something.
 */
final class NodeStreamSink implements StreamSink {

    /** Beyond the expected length the bar creeps rather than sticking, and never reaches full. */
    private static final double CEILING = 0.95;

    /** A bar cannot show more than this many distinct positions, so nor should the event stream. */
    private static final double STEP = 0.02;

    private final NodeContext context;
    private final String portKey;
    private final double expectedCharacters;
    private double lastReported = -1;

    /** What to assume when no ceiling was asked for, so the bar still moves rather than sticking. */
    private static final int UNBOUNDED_ESTIMATE = 4096;

    NodeStreamSink(NodeContext context, String portKey, int expectedOutputTokens) {
        this.context = context;
        this.portKey = portKey;
        // Four characters to a token is the usual rough figure, and rough is all this needs to be.
        var expectedTokens = expectedOutputTokens > 0 ? expectedOutputTokens : UNBOUNDED_ESTIMATE;
        this.expectedCharacters = Math.max(200d, expectedTokens * 4d);
    }

    @Override
    public void chunk(String text) {
        context.stream(portKey, text);
    }

    @Override
    public boolean cancelled() {
        return context.isCancelled();
    }

    /**
     * Reported in steps rather than per chunk.
     *
     * A streamed answer arrives in hundreds of pieces, and each one already sends its text. Sending
     * a progress frame beside every one of them doubles the traffic to say something the eye cannot
     * see — the bar cannot move by a two-hundredth of a pixel.
     */
    @Override
    public void progress(long charactersSoFar) {
        var fraction = Math.min(CEILING, charactersSoFar / expectedCharacters);
        if (fraction - lastReported < STEP) {
            return;
        }
        lastReported = fraction;
        context.progress(fraction, charactersSoFar + " characters");
    }
}
