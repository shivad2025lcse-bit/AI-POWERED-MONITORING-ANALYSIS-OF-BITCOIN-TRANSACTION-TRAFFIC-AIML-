package com.bitcoin.monitoring.config;

import com.bitcoin.monitoring.websocket.LiveWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final LiveWebSocketHandler handler;
    public WebSocketConfig(LiveWebSocketHandler handler) { this.handler = handler; }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/live").setAllowedOrigins("http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:5500");
    }
}