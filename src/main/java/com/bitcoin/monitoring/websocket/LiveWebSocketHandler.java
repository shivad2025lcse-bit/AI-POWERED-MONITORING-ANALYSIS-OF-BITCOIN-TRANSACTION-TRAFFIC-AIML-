package com.bitcoin.monitoring.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public LiveWebSocketHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Dashboard WebSocket connected");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(Object event) {
        String json;
        try { json = mapper.writeValueAsString(event); }
        catch (IOException exception) { log.warn("Could not serialize live event", exception); return; }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) { sessions.remove(session); continue; }
            try { session.sendMessage(new TextMessage(json)); }
            catch (IOException exception) { sessions.remove(session); }
        }
    }
}