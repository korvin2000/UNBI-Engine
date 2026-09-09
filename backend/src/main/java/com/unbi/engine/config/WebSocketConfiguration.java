package com.unbi.engine.config;

import com.unbi.engine.transport.EngineWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the engine socket.
 *
 * <p>Raw WebSocket rather than STOMP: there is one channel, a handful of frame types and no
 * subscription topology to speak of, so a broker layer would be machinery without a job.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final EngineWebSocketHandler handler;
    private final String[] allowedOrigins;

    public WebSocketConfiguration(
            EngineWebSocketHandler handler,
            @Value("${unbi.cors.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins.clone();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/engine").setAllowedOrigins(allowedOrigins);
    }
}
