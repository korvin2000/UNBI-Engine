package com.unbi.engine.transport;

import com.unbi.engine.core.graph.GraphValidator;
import com.unbi.engine.core.type.TypeSystem;
import com.unbi.engine.engine.ExecutionEngine;
import com.unbi.engine.registry.NodeRegistry;
import com.unbi.engine.transport.codec.EventCodec;
import com.unbi.engine.transport.codec.GraphCodec;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single duplex channel between the editor and the engine.
 *
 * <p>Commands arrive as {@code run}, {@code cancel}, {@code validate} and {@code ping}; events go
 * back as the frames produced by {@link EventCodec}. One socket may drive several runs, and a run
 * belongs to the socket that started it — so closing the tab cancels the work rather than leaving it
 * grinding through a directory tree for nobody.
 */
@Component
public class EngineWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EngineWebSocketHandler.class);

    private final ExecutionEngine engine;
    private final NodeRegistry registry;
    private final TypeSystem types;
    private final ObjectMapper mapper;

    /** Runs started by each session, so a disconnect can cancel exactly those. */
    private final Map<String, Set<String>> runsBySession = new ConcurrentHashMap<>();

    /**
     * A send lock per session. The engine emits from a virtual run thread, and two runs on one
     * socket would otherwise interleave partial frames — {@code WebSocketSession} is explicitly not
     * safe for concurrent senders.
     */
    private final Map<String, ReentrantLock> sendLocks = new ConcurrentHashMap<>();

    public EngineWebSocketHandler(
            ExecutionEngine engine, NodeRegistry registry, TypeSystem types, ObjectMapper mapper) {
        this.engine = engine;
        this.registry = registry;
        this.types = types;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        runsBySession.put(session.getId(), ConcurrentHashMap.newKeySet());
        sendLocks.put(session.getId(), new ReentrantLock());
        log.debug("Editor connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var runs = runsBySession.remove(session.getId());
        sendLocks.remove(session.getId());
        if (runs != null) {
            runs.forEach(engine::cancel);
        }
        log.debug("Editor disconnected: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (RuntimeException malformed) {
            send(session, EventCodec.error("Could not read that message: " + malformed.getMessage()));
            return;
        }

        var type = frame.path("type").asString("");
        try {
            switch (type) {
                case "run" -> handleRun(session, frame);
                case "cancel" -> handleCancel(session, frame);
                case "validate" -> handleValidate(session, frame);
                case "ping" -> send(session, EventCodec.simple("pong", null, null));
                default -> send(session, EventCodec.error("Unknown command: " + type));
            }
        } catch (IllegalArgumentException badRequest) {
            // Malformed graphs and unknown ids are the client's problem to fix, and the message
            // says which element was wrong. Not worth a stack trace in the log.
            send(session, EventCodec.error(badRequest.getMessage()));
        } catch (RuntimeException unexpected) {
            log.error("Failed to handle '{}' from {}", type, session.getId(), unexpected);
            send(session, EventCodec.error("The server could not handle that request"));
        }
    }

    private void handleRun(WebSocketSession session, JsonNode frame) {
        var requestId = frame.path("requestId").asString("");
        var graph = GraphCodec.read(frame.get("graph"));
        var sessionId = session.getId();

        var runId = engine.submit(graph, event -> send(session, EventCodec.write(event)));

        var runs = runsBySession.get(sessionId);
        if (runs != null) {
            runs.add(runId);
        }
        send(session, EventCodec.runAccepted(requestId, runId));
    }

    private void handleCancel(WebSocketSession session, JsonNode frame) {
        var runId = frame.path("runId").asString("");
        if (!engine.cancel(runId)) {
            send(session, EventCodec.error("That run has already finished"));
        }
    }

    private void handleValidate(WebSocketSession session, JsonNode frame) {
        var requestId = frame.path("requestId").asString("");
        var graph = GraphCodec.read(frame.get("graph"));
        var report = new GraphValidator(registry, types).validate(graph);
        send(session, EventCodec.validation(requestId, report.issues()));
    }

    private void send(WebSocketSession session, ObjectNode payload) {
        var lock = sendLocks.get(session.getId());
        if (lock == null) {
            return; // session already closed
        }
        lock.lock();
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (IOException | RuntimeException undeliverable) {
            // Thrown back into the engine's SafeEmitter, which stops the run from failing over it.
            throw new IllegalStateException("Could not deliver frame to " + session.getId(), undeliverable);
        } finally {
            lock.unlock();
        }
    }
}
