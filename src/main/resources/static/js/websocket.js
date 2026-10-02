import { API_BASE_URL } from "./api.js";

export function connectLive(onMessage, onState) {
    let socket;
    let retryTimer;
    let closed = false;
    let retryDelay = 1000;
    const connect = () => {
        if (closed) return;
        const url = new URL(API_BASE_URL);
        url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
        url.pathname = "/ws/live";
        onState("connecting");
        socket = new WebSocket(url);
        socket.onopen = () => { retryDelay = 1000; onState("connected"); };
        socket.onmessage = (event) => {
            try { onMessage(JSON.parse(event.data)); } catch { /* Ignore malformed provider events. */ }
        };
        socket.onerror = () => socket.close();
        socket.onclose = () => {
            onState("disconnected");
            if (!closed) {
                retryTimer = window.setTimeout(connect, retryDelay);
                retryDelay = Math.min(retryDelay * 2, 30000);
            }
        };
    };
    connect();
    return () => { closed = true; window.clearTimeout(retryTimer); socket?.close(); };
}