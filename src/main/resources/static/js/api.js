export const API_BASE_URL = window.API_BASE_URL || window.location.origin;

export async function request(path, options = {}) {
    const response = await fetch(`${API_BASE_URL}${path}`, {
        headers: { Accept: "application/json", ...(options.headers || {}) },
        ...options
    });
    const contentType = response.headers.get("content-type") || "";
    const body = contentType.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) throw new Error(body?.message || `Request failed (${response.status})`);
    return body;
}

export const api = {
    stats: () => request("/api/statistics/current"),
    status: () => request("/api/monitoring/status"),
    mempool: () => request("/api/network/mempool"),
    market: () => request("/api/market/overview"),
    marketHistory: (coinId, range, from, to, symbol) => {
        const params = new URLSearchParams({ coinId: coinId || "bitcoin", range: range || "7" });
        if (symbol) params.set("symbol", symbol);
        if (from != null && to != null) { params.set("from", from); params.set("to", to); }
        return request(`/api/market/history?${params}`);
    },
    marketForecast: (coinId, range, horizonDays, from, to, symbol) => {
        const params = new URLSearchParams({
            coinId: coinId || "bitcoin", range: range || "30", horizonDays: String(horizonDays || 7)
        });
        if (symbol) params.set("symbol", symbol);
        if (from != null && to != null) { params.set("from", from); params.set("to", to); }
        return request(`/api/market/forecast?${params}`);
    },
    blocks: (page = 0, size = 20) => request(`/api/blocks?page=${page}&size=${size}`),
    transactions: (page = 0, size = 25, query = "", level = "") => {
        const params = new URLSearchParams({ page, size });
        if (query) params.set("q", query);
        if (level) params.set("level", level);
        return request(`/api/transactions?${params}`);
    },
    transaction: (txid) => request(`/api/transactions/${encodeURIComponent(txid)}`),
    traffic: (hours = 24, from, to) => {
        const params = new URLSearchParams({ hours: String(hours) });
        if (from != null && to != null) {
            params.set("from", String(from));
            params.set("to", String(to));
        }
        return request(`/api/statistics/traffic?${params}`);
    },
    anomalies: () => request("/api/anomalies?page=0&size=100"),
    alerts: (severity = "") => request(`/api/alerts?page=0&size=100${severity ? `&severity=${severity}` : ""}`),
    search: (query) => request(`/api/search?q=${encodeURIComponent(query)}`),
    resolveAlert: (id) => request(`/api/alerts/${id}/resolve`, { method: "PUT" })
};