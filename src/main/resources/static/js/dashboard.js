import { api, API_BASE_URL } from "./api.js";
import { connectLive } from "./websocket.js";

const $ = (selector) => document.querySelector(selector);
const state = { transactionPage: 0, blockPage: 0, txQuery: "", txLevel: "", currentView: "overview", socket: "connecting", refreshSeconds: Number(localStorage.getItem("dashboard-refresh-seconds")) || 15, forecastResults: new Map(), forecastErrors: new Map(), forecastBulkKey: "", forecastBulkPromise: null, forecastBulkDoneKey: "", forecastBulkCompleted: 0 };
let refreshTimer;
let previousTrafficSnapshotAt = 0;
let previousTrafficLevel = "";
const fmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const dateTime = (value) => value ? new Date(value).toLocaleString() : "—";
const wait = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));
const shortHash = (value, length = 16) => value && value.length > length ? `${value.slice(0, length)}…` : value || "—";
const safe = (value) => String(value ?? "—").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
const emptyRow = (columns, text) => `<tr><td colspan="${columns}" class="empty-row">${safe(text)}</td></tr>`;
const fallbackMarketAssets = [
    ["bitcoin", "Bitcoin", "BTC"], ["ethereum", "Ethereum", "ETH"], ["tether", "Tether", "USDT"],
    ["binancecoin", "BNB", "BNB"], ["solana", "Solana", "SOL"], ["ripple", "XRP", "XRP"],
    ["usd-coin", "USD Coin", "USDC"], ["dogecoin", "Dogecoin", "DOGE"], ["cardano", "Cardano", "ADA"],
    ["tron", "TRON", "TRX"], ["avalanche-2", "Avalanche", "AVAX"], ["chainlink", "Chainlink", "LINK"],
    ["bitcoin-cash", "Bitcoin Cash", "BCH"], ["polkadot", "Polkadot", "DOT"], ["stellar", "Stellar", "XLM"],
    ["litecoin", "Litecoin", "LTC"], ["shiba-inu", "Shiba Inu", "SHIB"], ["sui", "Sui", "SUI"],
    ["the-open-network", "Toncoin", "TON"], ["wrapped-bitcoin", "Wrapped Bitcoin", "WBTC"],
    ["dai", "Dai", "DAI"], ["uniswap", "Uniswap", "UNI"], ["ethereum-classic", "Ethereum Classic", "ETC"],
    ["near", "NEAR Protocol", "NEAR"], ["aptos", "Aptos", "APT"], ["internet-computer", "Internet Computer", "ICP"],
    ["pepe", "Pepe", "PEPE"]
].map(([id, name, symbol]) => ({ id, name, symbol }));

function renderBlocks(rows, target) {
    target.innerHTML = rows?.length ? rows.map((block) => `<tr><td><span class="height-link" data-block-height="${block.height}">${fmt.format(block.height)}</span></td><td class="mono hash-cell" data-block-hash="${safe(block.hash)}" title="${safe(block.hash)}">${safe(shortHash(block.hash, 18))}</td><td>${safe(dateTime(block.timestamp))}</td><td class="mono">${fmt.format(block.transactionCount)}</td><td>${fmt.format(block.size)} B</td><td class="mono">${fmt.format(block.totalFees || 0)} BTC</td></tr>`).join("") : emptyRow(6, "No block data yet. The dashboard will update when a real block is synchronized.");
}

function renderTransactionRows(rows, target, detailed = false) {
    target.innerHTML = rows?.length ? rows.map((tx) => {
        const level = tx.anomalyLevel || "NORMAL";
        const common = `<td class="mono hash-cell" data-txid="${safe(tx.txid)}" title="${safe(tx.txid)}">${safe(shortHash(tx.txid, 18))}</td><td><span class="height-link" data-block-height="${tx.blockHeight}">${fmt.format(tx.blockHeight)}</span></td><td>${safe(dateTime(tx.timestamp))}</td>`;
        if (detailed) return `<tr>${common}<td class="mono">${tx.inputCount} / ${tx.outputCount}</td><td class="mono">${fmt.format(tx.outputValue)} BTC</td><td class="mono">${fmt.format(tx.feeRate)} sat/vB</td><td>${fmt.format(tx.transactionSize)} B</td><td class="mono">${fmt.format(tx.anomalyScore)}</td><td><span class="risk-badge ${level.toLowerCase()}">${safe(level)}</span></td></tr>`;
        return `<tr>${common}<td class="mono">${fmt.format(tx.outputValue)} BTC</td><td class="mono">${fmt.format(tx.feeRate)} sat/vB</td><td><span class="risk-badge ${level.toLowerCase()}">${safe(level)} · ${fmt.format(tx.anomalyScore)}</span></td></tr>`;
    }).join("") : emptyRow(detailed ? 9 : 6, "No transactions match this view yet.");
}

function drawChart(canvas, values, color = "#ee762a", labels = [], hoverPoints = null, showTime = false, layoutOptions = {}) {
    if (!canvas) return;
    const host = canvas.parentElement;
    const rect = canvas.getBoundingClientRect();
    const scale = window.devicePixelRatio || 1;
    const context = canvas.getContext("2d");
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    canvas.width = Math.max(1, Math.round(rect.width * scale));
    canvas.height = Math.max(1, Math.round(rect.height * scale));
    context.scale(scale, scale);
    const width = rect.width;
    const height = rect.height;
    const points = (values || []).map(Number).filter(Number.isFinite);
    host.classList.toggle("has-data", points.length > 0);
    if (points.length === 0) {
        hideChartHover(host);
        return;
    }
    const pad = { left: 28, right: 10, top: 12, bottom: 23 };
    const chartWidth = width - pad.left - pad.right;
    const chartHeight = height - pad.top - pad.bottom;
    const max = Math.max(...points, 1);
    const min = Math.min(...points, 0);
    context.font = '9px "DM Mono", monospace';
    context.fillStyle = "#a3a9a3";
    context.strokeStyle = "#eef0ec";
    context.lineWidth = 1;
    for (let tick = 0; tick <= 3; tick++) {
        const y = pad.top + chartHeight * tick / 3;
        context.beginPath(); context.moveTo(pad.left, y); context.lineTo(width - pad.right, y); context.stroke();
        context.fillText(fmt.format(max - (max - min) * tick / 3), 0, y + 3);
    }
    const coords = points.map((point, index) => ({
        x: points.length === 1 ? pad.left + chartWidth / 2 : pad.left + chartWidth * index / (points.length - 1),
        y: pad.top + chartHeight * (1 - (point - min) / (max - min || 1))
    }));
    if (points.length > 1) {
        const gradient = context.createLinearGradient(0, pad.top, 0, height - pad.bottom);
        gradient.addColorStop(0, `${color}28`); gradient.addColorStop(1, `${color}00`);
        context.beginPath(); coords.forEach((point, index) => index ? context.lineTo(point.x, point.y) : context.moveTo(point.x, point.y));
        context.lineTo(coords.at(-1).x, height - pad.bottom); context.lineTo(coords[0].x, height - pad.bottom); context.closePath(); context.fillStyle = gradient; context.fill();
        context.beginPath(); coords.forEach((point, index) => index ? context.lineTo(point.x, point.y) : context.moveTo(point.x, point.y));
        context.strokeStyle = color; context.lineWidth = 2; context.stroke();
    }
    coords.forEach((point, index) => { if (index % Math.max(1, Math.floor(points.length / 10)) === 0) { context.beginPath(); context.arc(point.x, point.y, 2.4, 0, Math.PI * 2); context.fillStyle = color; context.fill(); } });
    if (labels.length === points.length) {
        context.fillStyle = "#a3a9a3";
        for (const index of new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])) {
            context.fillText(labels[index], Math.max(pad.left, coords[index].x - 20), height - 5);
        }
    }
    if (hoverPoints?.length === points.length) {
        attachChartHover(canvas, hoverPoints, coords, { pad, chartHeight, chartWidth, showTime, ...layoutOptions });
    } else {
        canvas.onpointermove = null;
        canvas.onpointerdown = null;
        canvas.onpointerleave = null;
    }
}

function hideChartHover(host) {
    host.querySelector(".chart-hover-readout")?.setAttribute("hidden", "");
    host.querySelector(".chart-hover-line")?.setAttribute("hidden", "");
    host.querySelector(".chart-hover-dot")?.setAttribute("hidden", "");
}

function trafficChartData(data, metric) {
    const values = Array.isArray(data?.[metric]) ? data[metric] : [];
    const timestamps = Array.isArray(data?.labels) ? data.labels : [];
    const points = timestamps.map((timestamp, index) => {
        const blockHeight = data.heights?.[index];
        const transactions = Number(data.transactions?.[index] || 0);
        const blockSize = Number(data.blockSize?.[index] || 0);
        const averageFee = Number(data.averageFeeBtc?.[index] || 0);
        const pointValue = Number(values[index]);
        return {
            timestamp,
            title: blockHeight == null ? "Observed block" : `Block ${fmt.format(blockHeight)}`,
            value: Number.isFinite(pointValue) ? pointValue : null,
            details: [
                [metric === "blockSize" ? "Block size" : "Transactions", metric === "blockSize" ? `${fmt.format(blockSize)} B` : fmt.format(transactions)],
                ["Average fee", `${fmt.format(averageFee)} BTC`],
                ["Sample time", new Date(timestamp).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })]
            ]
        };
    });
    return { values, points };
}

function renderTrafficChart(canvas, data, rangeId, prefix, metric, color = "#ee762a") {
    const { values, points } = trafficChartData(data, metric);
    const emptyMessage = canvas?.parentElement.querySelector(".chart-empty");
    if (emptyMessage) emptyMessage.textContent = "No monitored blocks in the selected period";
    const select = $(`#${rangeId}`);
    const range = select.value;
    let showTime = range !== "custom" && Number(range) <= 24;
    if (range === "custom") {
        const { from, to } = getCustomTrafficBounds(prefix);
        showTime = to - from <= 86_400_000;
    }
    const labels = (data.labels || []).map((timestamp) => {
        const date = new Date(timestamp);
        return showTime
            ? date.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })
            : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
    });
    const sizeMetric = metric === "blockSize";
    drawChart(canvas, values, color, labels, points, showTime, {
        metricLabel: sizeMetric ? "Block size" : "Transactions",
        formatValue: (value) => sizeMetric ? `${fmt.format(value)} B` : fmt.format(value)
    });
}

function attachChartHover(canvas, points, coords, layout) {
    const host = canvas.parentElement;
    const getOrCreate = (selector, tagName, className) => {
        let element = host.querySelector(selector);
        if (!element) {
            element = document.createElement(tagName);
            element.className = className;
            element.hidden = true;
            host.append(element);
        }
        return element;
    };
    const readout = getOrCreate(".chart-hover-readout", "output", "chart-hover-readout");
    const guide = getOrCreate(".chart-hover-line", "div", "chart-hover-line");
    const marker = getOrCreate(".chart-hover-dot", "div", "chart-hover-dot");

    const updateHover = (event) => {
        const canvasRect = canvas.getBoundingClientRect();
        const hostRect = host.getBoundingClientRect();
        const pointerX = event.clientX - canvasRect.left;
        const plotX = Math.max(layout.pad.left, Math.min(canvasRect.width - layout.pad.right, pointerX));
        const resolvedIndex = layout.chartWidth > 0 ? (plotX - layout.pad.left) / layout.chartWidth * (coords.length - 1) : 0;
        const index = Math.min(coords.length - 1, Math.max(0, Math.round(resolvedIndex)));
        const point = points[index];
        const coordinate = coords[index];
        if (!point || !coordinate) {
            hideChartHover(host);
            return;
        }
        const x = canvasRect.left - hostRect.left + coordinate.x;
        const y = canvasRect.top - hostRect.top + coordinate.y;
        const timestampValue = point?.timestamp ?? point?.time ?? point?.date;
        const timestamp = timestampValue ? new Date(timestampValue) : null;
        const validValue = Number.isFinite(Number(point?.value ?? point?.priceUsd)) ? Number(point.value ?? point.priceUsd) : null;
        const timeLabel = layout.showTime
            ? (timestamp ? timestamp.toLocaleString(undefined, { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" }) : "Time unavailable")
            : (timestamp ? timestamp.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "Date unavailable");

        const coin = layout.hoverDetails;
        const rows = coin ? [
            ["24h change", coin.priceChange24hPct == null ? "—" : `${fmt.format(coin.priceChange24hPct)}%`],
            ["Market cap", usd(coin.marketCapUsd, true)],
            ["24h volume", usd(coin.volume24hUsd, true)],
            ["Circulating supply", coin.circulatingSupply == null ? "Not reported" : fmt.format(coin.circulatingSupply)],
            ["Market rank", coin.marketCapRank == null ? "Not reported" : `#${fmt.format(coin.marketCapRank)}`]
        ] : point.details || [];
        const title = point.title || (coin ? `${coin.name} · ${String(coin.symbol || "").toUpperCase()}` : "");
        const value = validValue != null ? (layout.formatValue ? layout.formatValue(validValue) : fmt.format(validValue)) : (point.priceUsd != null ? usd(point.priceUsd) : "—");
        const detailRows = rows.map(([label, rowValue]) => `<span>${safe(label)}: ${safe(rowValue)}</span>`).join("");
        readout.innerHTML = `${title ? `<strong>${safe(title)}</strong>` : ""}<strong>${safe(layout.metricLabel ? `${layout.metricLabel}: ${value}` : value)}</strong><span>${safe(timeLabel)}</span>${detailRows}`;
        readout.hidden = false;
        guide.hidden = false;
        marker.hidden = false;
        guide.style.left = `${x}px`;
        guide.style.top = `${canvasRect.top - hostRect.top + layout.pad.top}px`;
        guide.style.height = `${layout.chartHeight}px`;
        marker.style.left = `${x}px`;
        marker.style.top = `${y}px`;
        const tooltipX = Math.max(6, Math.min(hostRect.width - readout.offsetWidth - 6, x + 10));
        const tooltipY = Math.max(6, Math.min(hostRect.height - readout.offsetHeight - 6, y - readout.offsetHeight - 10));
        readout.style.left = `${tooltipX}px`;
        readout.style.top = `${tooltipY}px`;
    };

    canvas.onpointermove = updateHover;
    canvas.onpointerdown = updateHover;
    canvas.onpointerleave = (event) => {
        if (event.pointerType !== "touch") hideChartHover(host);
    };
}

const usd = (value, compact = false) => value == null ? "—" : new Intl.NumberFormat(undefined, {
    style: "currency", currency: "USD", maximumFractionDigits: compact ? 2 : value < 1 ? 4 : 2,
    notation: compact ? "compact" : "standard"
}).format(Number(value));
const supply = (value) => value == null ? "Not reported" : `${fmt.format(Number(value))} BTC`;

function getCustomHistoryBounds() {
    const fromDate = $("#market-history-from").value;
    const toDate = $("#market-history-to").value;
    if (!fromDate || !toDate || fromDate > toDate) {
        throw new Error("Choose a valid start and end date for market analysis.");
    }
    const genesis = Math.floor(Date.parse("2009-01-03T18:15:05Z") / 1000);
    const from = Math.max(genesis, Math.floor(Date.parse(`${fromDate}T00:00:00Z`) / 1000));
    const requestedEnd = Math.floor(Date.parse(`${toDate}T23:59:59Z`) / 1000);
    const to = Math.min(requestedEnd, Math.floor(Date.now() / 1000));
    if (from >= to) throw new Error("The selected period must include at least part of a day.");
    return { from, to };
}

function getCustomTrafficBounds(prefix) {
    const fromDate = $(`#${prefix}-from`).value;
    const toDate = $(`#${prefix}-to`).value;
    if (!fromDate || !toDate || fromDate > toDate) {
        throw new Error("Choose a valid start and end date for traffic analysis.");
    }
    const from = new Date(`${fromDate}T00:00:00`).getTime();
    const to = new Date(`${toDate}T23:59:59.999`).getTime();
    if (!Number.isFinite(from) || !Number.isFinite(to) || from > to) {
        throw new Error("Choose a valid start and end date for traffic analysis.");
    }
    return { from, to };
}

function loadTrafficSnapshot(rangeId, prefix) {
    const range = $(`#${rangeId}`).value;
    const customRange = $(`#${prefix}-custom-range`);
    if (customRange) customRange.hidden = range !== "custom";
    if (range !== "custom") return api.traffic(Number(range));
    const { from, to } = getCustomTrafficBounds(prefix);
    return api.traffic(24, from, to);
}

const forecastStorageKey = "market-forecast-outcomes-v1";
const notificationStorageKey = "bitcoin-monitor-live-notifications-v1";
const notificationDedupKey = "bitcoin-monitor-last-notification-v1";
const compositionColors = ["#e77b36", "#37866b", "#4b82a5", "#cb5348", "#b39a36", "#795c4b", "#45a7a0", "#8c6c9e", "#789044", "#d18b45"];

function readNotificationEvents() {
    try {
        return JSON.parse(localStorage.getItem(notificationStorageKey) || "[]")
            .filter((event) => event.title !== "Market snapshot"
                && !String(event.message || "").startsWith("Market snapshot (24-hour change):")
                && !String(event.message || "").includes("General signal:")
                && !String(event.message || "").includes("-0.00%"));
    }
    catch { return []; }
}

function formatSignedPercent(value) {
    if (value == null || !Number.isFinite(Number(value))) return "0%";
    const rounded = Math.abs(Number(value));
    return `${Number(value) >= 0 ? "+" : "-"}${fmt.format(rounded)}%`;
}

function buildMarketSummaryMessage(assets = []) {
    const coinUpdates = (Array.isArray(assets) ? assets : []).map((asset) => {
        const name = String(asset.name || asset.symbol || "Coin");
        const symbol = String(asset.symbol || "").toUpperCase();
        const price = usd(asset.priceUsd);
        const change = Number(asset.priceChange24hPct);
        const hasChange = asset.priceChange24hPct != null && Number.isFinite(change);
        const unchanged = hasChange && Math.abs(change) < 0.005;
        const percentage = !hasChange ? "Change unavailable" : unchanged ? "0.00%" : `${change > 0 ? "+" : ""}${change.toFixed(2)}%`;
        const direction = !hasChange ? "" : unchanged ? " · no change" : change > 0 ? " · up" : " · down";
        return `${name} (${symbol})  |  ${price}  |  ${percentage}${direction}`;
    });
    return coinUpdates.length
        ? coinUpdates.join("\n")
        : "Market data is still syncing with the latest provider feed.";
}

function marketActivityScreen(asset) {
    const price = Number(asset.priceUsd);
    const change = Number(asset.priceChange24hPct);
    const volume = Number(asset.volume24hUsd);
    const marketCap = Number(asset.marketCapUsd);
    if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(change)
        || !Number.isFinite(volume) || volume < 0 || !Number.isFinite(marketCap) || marketCap <= 0) return null;

    const turnoverPct = volume / marketCap * 100;
    const signal = turnoverPct >= 5 && change <= -2 ? "SELL RISK"
        : turnoverPct >= 5 && change >= 2 ? "BUY WATCH" : "HOLD";
    return { asset, price, change, turnoverPct, signal };
}

function marketActivityScreens(assets = []) {
    const priority = { "SELL RISK": 0, "BUY WATCH": 1, HOLD: 2 };
    return assets.map(marketActivityScreen).filter(Boolean)
        .sort((a, b) => priority[a.signal] - priority[b.signal] || b.turnoverPct - a.turnoverPct);
}

function marketActivityReason(screen) {
    const turnover = `${fmt.format(screen.turnoverPct)}%`;
    if (screen.signal === "SELL RISK") {
        return `High 24h turnover (${turnover}) with a ${fmt.format(Math.abs(screen.change))}% price decline.`;
    }
    if (screen.signal === "BUY WATCH") {
        return `High 24h turnover (${turnover}) with a ${fmt.format(screen.change)}% price rise.`;
    }
    return `No high-turnover directional threshold met (${turnover} turnover).`;
}

function renderMarketActivityScreens(assets, stale) {
    const target = $("#market-traffic-signals");
    if (!target) return;
    if (stale) {
        target.innerHTML = emptyRow(6, "Market quotes are stale; current watch signals are unavailable.");
        return;
    }
    const screens = marketActivityScreens(assets).slice(0, 8);
    target.innerHTML = screens.length ? screens.map(({ asset, price, change, turnoverPct, signal }) => {
        const state = signal === "BUY WATCH" ? "buy" : signal === "SELL RISK" ? "sell" : "hold";
        return `<tr><td><strong>${safe(asset.name)}</strong><span class="asset-symbol">${safe(asset.symbol)}</span></td>
            <td class="mono">${safe(usd(price))}</td>
            <td class="mono ${change >= 0 ? "positive" : "negative"}">${change > 0 ? "+" : ""}${fmt.format(change)}%</td>
            <td class="mono">${fmt.format(turnoverPct)}%</td>
            <td><span class="market-signal-badge ${state}">${signal}</span></td>
            <td>${safe(marketActivityReason({ signal, change, turnoverPct }))}</td></tr>`;
    }).join("") : emptyRow(6, "Current provider quotes do not contain enough data to screen.");
}

function renderForecastAssetRows(assets, completed, total, loading = false) {
    const target = $("#forecast-assets-body");
    const status = $("#forecast-list-status");
    if (!target || !status) return;
    const available = assets.filter((asset) => state.forecastResults.get(asset.id)?.available);
    const insufficient = [...state.forecastResults.values()]
        .filter((result) => result.available === false && !result.historyError).length;
    const failed = state.forecastErrors.size;
    status.textContent = loading
        ? `Checking real price history at a provider-safe pace: ${completed} of ${total} coins · ${available.length} with enough history`
        : `${available.length} predictions from ${total} checked coins · ${insufficient} lacked sufficient history · ${failed} provider requests failed.`;
    target.innerHTML = available.length ? available.map((asset) => {
        const result = state.forecastResults.get(asset.id);
        const backtest = result.backtest || {};
        const change = Number(result.forecastChangePct);
        return `<tr><td><button class="forecast-asset-link" type="button" data-forecast-coin="${safe(asset.id)}"><strong>${safe(asset.name)}</strong><span class="asset-symbol">${safe(String(asset.symbol || "").toUpperCase())}</span></button></td>
            <td class="mono">${safe(usd(result.forecastPriceUsd))}</td>
            <td class="mono ${change >= 0 ? "positive" : "negative"}">${change > 0 ? "+" : ""}${fmt.format(change)}%</td>
            <td class="mono">${Number.isFinite(backtest.directionalAccuracyPct) ? `${fmt.format(backtest.directionalAccuracyPct)}%` : "—"}</td>
            <td class="mono">${Number.isFinite(backtest.precisionPct) ? `${fmt.format(backtest.precisionPct)}%` : "—"}</td>
            <td class="mono">${fmt.format(backtest.samples || 0)}</td>
            <td>${safe(result.source || "Historical provider")}${result.stale ? " · cached" : ""}</td></tr>`;
    }).join("") : `<tr><td colspan="7" class="empty-row">${loading
        ? "Checking market coins for sufficient real historical price data."
        : "No coins in the current market list have enough real history for this forecast."}</td></tr>`;
}

function loadAllAssetForecasts(assets, horizon, priorityCoinId, priorityForecast) {
    const marketAssets = assets.filter((asset) => asset?.id);
    const key = `${horizon}:${marketAssets.map((asset) => asset.id).sort().join(",")}`;
    if (state.forecastBulkKey === key) {
        if (state.forecastBulkPromise) return state.forecastBulkPromise;
        if (state.forecastBulkDoneKey === key) {
            renderForecastAssetRows(marketAssets, marketAssets.length, marketAssets.length);
            return Promise.resolve();
        }
    }
    const priorityAsset = marketAssets.find((asset) => asset.id === priorityCoinId);
    const eligibleAssets = priorityAsset
        ? [priorityAsset, ...marketAssets.filter((asset) => asset.id !== priorityCoinId)]
        : marketAssets;
    state.forecastBulkKey = key;
    state.forecastBulkDoneKey = "";
    state.forecastBulkCompleted = 0;
    state.forecastResults.clear();
    state.forecastErrors.clear();
    let completed = 0;
    renderForecastAssetRows(eligibleAssets, completed, eligibleAssets.length, true);
    let nextIndex = 0;
    let nextRequestAt = Date.now() + (priorityForecast ? 2200 : 0);
    const worker = async () => {
        while (nextIndex < eligibleAssets.length) {
            const asset = eligibleAssets[nextIndex++];
            try {
                let result;
                if (asset.id === priorityCoinId && priorityForecast) {
                    result = priorityForecast;
                } else {
                    const delay = Math.max(0, nextRequestAt - Date.now());
                    if (delay) await wait(delay);
                    nextRequestAt = Date.now() + 2200;
                    result = await api.marketForecast(asset.id, "365", horizon, undefined, undefined, asset.symbol);
                }
                if (state.forecastBulkKey !== key) return;
                const existing = state.forecastResults.get(asset.id);
                if (result.historyError) {
                    if (!existing?.available) state.forecastErrors.set(asset.id, result.historyError);
                } else if (result.available || !existing?.available) {
                    state.forecastResults.set(asset.id, result);
                    state.forecastErrors.delete(asset.id);
                }
            } catch (error) {
                if (state.forecastBulkKey === key && !state.forecastResults.get(asset.id)?.available) {
                    state.forecastErrors.set(asset.id, error.message || "Provider request failed");
                }
                console.warn(`Could not load ${asset.symbol || asset.id} forecast:`, error);
            } finally {
                completed++;
                if (state.forecastBulkKey === key) state.forecastBulkCompleted = completed;
                if (state.forecastBulkKey === key) {
                    renderForecastAssetRows(eligibleAssets, completed, eligibleAssets.length, completed < eligibleAssets.length);
                }
            }
        }
    };
    state.forecastBulkPromise = worker().then(() => {
        if (state.forecastBulkKey === key) {
            renderForecastAssetRows(eligibleAssets, completed, eligibleAssets.length, false);
            state.forecastBulkDoneKey = key;
        }
    }).finally(() => {
        if (state.forecastBulkKey === key) state.forecastBulkPromise = null;
    });
    return state.forecastBulkPromise;
}

function marketActivityNotification(market) {
    if (!market || market.stale) return "Market quotes are unavailable or stale; current watch signals are unavailable.";
    const candidates = marketActivityScreens(market?.assets)
        .filter((screen) => screen.signal !== "HOLD").slice(0, 5);
    if (!candidates.length) {
        return "No current BUY WATCH or SELL RISK flags meet the disclosed volume-and-price screen.";
    }
    return candidates.map(({ asset, price, change, turnoverPct, signal }) =>
        `${asset.name} (${String(asset.symbol || "").toUpperCase()}) | ${signal} | ${usd(price)} | ${change > 0 ? "+" : ""}${fmt.format(change)}% | ${fmt.format(turnoverPct)}% turnover`
    ).join("\n");
}

function buildTrafficSummaryMessage(level, count, sizeMb, fastestFee) {
    const txnText = fmt.format(Number(count || 0));
    const sizeText = fmt.format(Number(sizeMb || 0));
    const feeText = fmt.format(Number(fastestFee || 0));

    if (level === "HIGH") {
        return `Traffic is heavy: ${txnText} unconfirmed transactions are waiting, and the mempool is about ${sizeText} MB. Fees are around ${feeText} sat/vB, so confirmation may take longer.`;
    }
    if (level === "ELEVATED") {
        return `Traffic is increasing: ${txnText} transactions are waiting, and fee pressure is around ${feeText} sat/vB. This may slow confirmation times.`;
    }
    return `Traffic is normal: ${txnText} transactions are waiting and fee pressure is ${feeText} sat/vB. Network activity is stable right now.`;
}

function buildReadableMarketMove(asset, movementPct) {
    const direction = Number(movementPct) >= 0 ? "up" : "down";
    const pct = Math.abs(Number(movementPct) || 0);
    return `${asset.name} (${asset.symbol}) is ${direction} ${fmt.format(pct)}% and now trades at ${usd(asset.priceUsd)}.`;
}

function writeNotificationEvents(events) {
    localStorage.setItem(notificationStorageKey, JSON.stringify(events.slice(0, 12)));
}

function pushNotificationEvent(title, message, severity = "INFO") {
    const normalizedTitle = String(title || "Market alert").trim() || "Market alert";
    const normalizedMessage = String(message || "A new market update is available.").trim() || "A new market update is available.";
    const entry = { title: normalizedTitle, message: normalizedMessage, severity: String(severity).toUpperCase(), time: new Date().toISOString() };
    const events = readNotificationEvents();
    const signature = `${entry.severity}|${entry.title}|${entry.message}`;
    const lastSignature = localStorage.getItem(notificationDedupKey);
    if (lastSignature === signature) return;
    localStorage.setItem(notificationDedupKey, signature);
    const next = [entry, ...events].slice(0, 12);
    writeNotificationEvents(next);
    renderNotificationList(next);
    const badgeCount = next.length;
    const badge = document.getElementById("notification-badge");
    const bell = document.getElementById("notification-bell");
    if (badge && bell) {
        badge.textContent = badgeCount > 99 ? "99+" : String(badgeCount);
        badge.hidden = badgeCount === 0;
        bell.classList.toggle("has-alerts", badgeCount > 0);
    }
}

function notifyMarketUser(title, message, toastMessage = message) {
    const friendlyToast = String(toastMessage || message || title || "Market update").trim();
    showToast(`${title}: ${friendlyToast}`);
    pushNotificationEvent(title, message, "INFO");
    if ("Notification" in window && Notification.permission === "granted") {
        new Notification(title, { body: message });
    }
}

function readForecastRecords() {
    try { return JSON.parse(localStorage.getItem(forecastStorageKey) || "[]"); }
    catch { return []; }
}

function renderForecastOutcomes(records) {
    const recent = records.slice(-6).reverse();
    $("#forecast-outcomes").innerHTML = recent.map((item) => {
        const label = item.status === "pending" ? "PENDING" : item.status;
        const detail = item.status === "pending"
            ? `Due ${dateTime(item.forecastFor)}`
            : `${item.outcomePct >= 0 ? "+" : ""}${fmt.format(item.outcomePct)}% observed · ${dateTime(item.checkedAt)}`;
        return `<li data-state="${safe(item.status)}"><strong>${safe(item.symbol)} ${safe(item.signal)} · ${label}</strong><span>${safe(detail)}</span></li>`;
    }).join("");
}

const priceBaselineKey = "market-price-alert-baselines-v1";

function signedPercent(value) {
    return (Number(value) > 0 ? "+" : "") + fmt.format(value) + "%";
}

function reportedPercent(value) {
    if (value == null) return "Not reported";
    return signedPercent(value);
}

function reportedSupply(value, symbol) {
    if (value == null) return "Not reported";
    return fmt.format(Number(value)) + " " + String(symbol || "").toUpperCase();
}

function reportedRank(value) {
    if (value == null) return "Not reported";
    return "#" + fmt.format(value);
}

const alertLine = (label, value) => `${label}: ${value}`;

function marketPriceAlertDetails(asset, movementPct, quoteAt) {
    return [
        `${asset.name} (${asset.symbol}) changed ${formatSignedPercent(movementPct)} in the latest quote.`,
        `Current price: ${usd(asset.priceUsd)}.`,
        `24h change: ${reportedPercent(asset.priceChange24hPct)}.`,
        `Market cap: ${usd(asset.marketCapUsd, true)}.`,
        `24h volume: ${usd(asset.volume24hUsd, true)}.`,
        `Updated: ${dateTime(asset.lastUpdated || quoteAt)}.`,
        "This is an alert only—no trade was placed."
    ].join("\n");
}

function checkRapidPriceAlerts(snapshot) {
    if (!snapshot || snapshot.stale || !Array.isArray(snapshot.assets)) return;
    let baselines;
    try { baselines = JSON.parse(localStorage.getItem(priceBaselineKey) || "{}"); }
    catch { baselines = {}; }
    const now = Date.now();
    let changed = false;
    for (const asset of snapshot.assets) {
        const price = Number(asset.priceUsd);
        const quoteAt = Date.parse(asset.lastUpdated || snapshot.marketUpdatedAt || "");
        if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(quoteAt) || now - quoteAt > 120_000) continue;
        const previous = baselines[asset.id];
        if (previous && quoteAt > previous.quoteAt) {
            const elapsed = quoteAt - previous.quoteAt;
            const movementPct = (price / previous.priceUsd - 1) * 100;
            const dropLimit = Number($("#market-drop-threshold").value) || 3;
            const riseLimit = Number($("#market-rise-threshold").value) || 3;
            if (elapsed <= 120_000 && movementPct <= -dropLimit) {
                const summary = `${fmt.format(Math.abs(movementPct))}% down from the previous price update.`;
                notifyMarketUser(`${asset.name} (${asset.symbol}) price drop`, marketPriceAlertDetails(asset, movementPct, quoteAt), summary);
            } else if (elapsed <= 120_000 && movementPct >= riseLimit) {
                const summary = `${fmt.format(movementPct)}% up from the previous price update.`;
                notifyMarketUser(`${asset.name} (${asset.symbol}) price rise`, marketPriceAlertDetails(asset, movementPct, quoteAt), summary);
            }
        }
        if (!previous || quoteAt > previous.quoteAt) {
            baselines[asset.id] = { priceUsd: price, quoteAt };
            changed = true;
        }
    }
    if (changed) localStorage.setItem(priceBaselineKey, JSON.stringify(baselines));
}

function assessTraffic(snapshot) {
    const mempool = snapshot?.mempool;
    const fees = snapshot?.fees;
    if (!mempool || snapshot.status === "DISCONNECTED") {
        return {
            level: "UNKNOWN",
            summary: snapshot?.error || "Mempool data is unavailable; traffic risk cannot be assessed.",
            suggestions: ["Check mempool.space or a trusted block explorer before retrying a time-sensitive transaction."]
        };
    }
    const count = Number(mempool.count || 0);
    const sizeMb = Number(mempool.vsize || 0) / 1_000_000;
    const fastestFee = Number(fees?.fastestFee || 0);
    const high = count >= 100_000 || sizeMb >= 150 || fastestFee >= 50;
    const elevated = count >= 30_000 || sizeMb >= 50 || fastestFee >= 20;
    if (high) {
        return {
            level: "HIGH",
            summary: buildTrafficSummaryMessage("HIGH", count, sizeMb, fastestFee),
            suggestions: [
                "Check the transaction ID in your wallet or a block explorer before taking action.",
                "For urgent confirmation, use your wallet's fee bump: RBF when the original transaction is replaceable, or CPFP when you control a spendable output.",
                "Do not resend the payment as a new transaction until you confirm the original transaction's status."
            ]
        };
    }
    if (elevated) {
        return {
            level: "ELEVATED",
            summary: buildTrafficSummaryMessage("ELEVATED", count, sizeMb, fastestFee),
            suggestions: [
                "Compare the wallet fee with the current next-block estimate before sending.",
                "If an existing payment is urgent and unconfirmed, check whether your wallet supports RBF or CPFP.",
                "Otherwise, wait for a block and recheck the fee estimate."
            ]
        };
    }
    return {
        level: "NORMAL",
        summary: buildTrafficSummaryMessage("NORMAL", count, sizeMb, fastestFee),
        suggestions: ["No congestion response is indicated by the latest provider snapshot."]
    };
}

function updateTrafficAssessment(snapshot) {
    const assessment = assessTraffic(snapshot);
    const label = $("#traffic-risk-label");
    if (!label) return;
    label.textContent = assessment.level === "UNKNOWN" ? "Traffic status unavailable" : `${assessment.level} CONGESTION`;
    label.dataset.level = assessment.level.toLowerCase();
    $("#traffic-assessment-summary").textContent = assessment.summary;
    $("#traffic-assessment-updated").textContent = snapshot?.lastUpdated
        ? `Provider snapshot ${dateTime(snapshot.lastUpdated)} · local check every 2 seconds`
        : "Provider snapshots refresh about every 30 seconds.";
    $("#traffic-suggestions").innerHTML = assessment.suggestions.map((step) => `<li>${safe(step)}</li>`).join("");

    const snapshotAt = Date.parse(snapshot?.lastUpdated || "");
    if (!Number.isFinite(snapshotAt) || snapshotAt <= previousTrafficSnapshotAt) return;
    if (["HIGH", "ELEVATED"].includes(assessment.level) && assessment.level !== previousTrafficLevel) {
        notifyMarketUser("Bitcoin traffic alert", assessment.summary, `Traffic is ${assessment.level.toLowerCase()}. ${assessment.summary}`);
    } else if (assessment.level === "NORMAL" && ["HIGH", "ELEVATED"].includes(previousTrafficLevel)) {
        notifyMarketUser("Bitcoin traffic eased", "The latest mempool snapshot is back below the congestion alert thresholds, so confirmation times should be more predictable.", "Traffic is back to normal.");
    }
    previousTrafficSnapshotAt = snapshotAt;
    previousTrafficLevel = assessment.level;
}

function trackMarketForecast(forecast, assets, marketData) {
    const records = readForecastRecords();
    const updates = [];
    const quotes = new Map(assets.map((asset) => [asset.id, asset]));
    if (!marketData.stale) {
        for (const item of records) {
            if (item.status !== "pending" || Date.now() < Date.parse(item.forecastFor)) continue;
            const quote = quotes.get(item.coinId);
            const quoteTime = Date.parse(quote?.lastUpdated || marketData.marketUpdatedAt || "");
            if (!Number.isFinite(quoteTime) || quoteTime < Date.parse(item.forecastFor)) continue;
            const actualPrice = Number(quote?.priceUsd);
            if (!Number.isFinite(actualPrice) || actualPrice <= 0) continue;
            const outcomePct = (actualPrice / item.basePriceUsd - 1) * 100;
            const correct = Math.sign(outcomePct) === Math.sign(item.expectedChangePct);
            item.status = correct ? "correct" : "wrong";
            item.outcomePct = outcomePct;
            item.checkedAt = new Date(quoteTime).toISOString();
            updates.push(item);
        }
    }

    if (forecast?.available && forecast.coinId && ["BUY WATCH", "SELL WATCH"].includes(forecast.signal)
        && Date.parse(forecast.forecastFor) > Date.now()) {
        const key = `${forecast.coinId}:${forecast.horizonDays}:${new Date().toISOString().slice(0, 10)}`;
        if (!records.some((item) => item.key === key)) {
            const asset = assets.find((item) => item.id === forecast.coinId);
            records.push({
                key, coinId: forecast.coinId, symbol: asset?.symbol || forecast.coinId.toUpperCase(),
                signal: forecast.signal, expectedChangePct: forecast.forecastChangePct,
                basePriceUsd: forecast.lastObservedPriceUsd, forecastFor: forecast.forecastFor,
                status: "pending", createdAt: new Date().toISOString()
            });
            const signalText = `${asset?.symbol || forecast.coinId.toUpperCase()} ${forecast.signal}`;
            const detailText = `Trend baseline projects ${fmt.format(forecast.forecastChangePct)}% over ${forecast.horizonDays} day(s). Review the chart and risk before acting.`;
            notifyMarketUser(signalText, detailText, `${signalText} · ${detailText}`);
        }
    }
    for (const item of updates) {
        notifyMarketUser(`${item.symbol} forecast ${item.status.toUpperCase()}`,
            `The ${item.signal} trend call was ${item.status} after ${fmt.format(item.outcomePct)}% observed movement in the selected market window.`);
    }
    localStorage.setItem(forecastStorageKey, JSON.stringify(records.slice(-100)));
    renderForecastOutcomes(records);
}

function drawCompositionChart(items, emptyMessage = "No composition data available.") {
    const canvas = $("#market-composition-chart");
    const host = canvas.parentElement;
    const legend = $("#market-composition-legend");
    const readout = host.querySelector(".composition-hover-readout");
    readout.hidden = true;
    legend.onpointerover = null;
    legend.onpointerdown = null;
    legend.onfocusin = null;
    legend.onclick = null;
    canvas.onpointermove = null;
    canvas.onpointerdown = null;
    canvas.onpointerleave = null;
    const context = canvas.getContext("2d");
    const bounds = canvas.getBoundingClientRect();
    const scale = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(bounds.width * scale));
    canvas.height = Math.max(1, Math.round(bounds.height * scale));
    context.scale(scale, scale);
    context.clearRect(0, 0, bounds.width, bounds.height);
    const valid = items.filter((item) => Number.isFinite(item.value) && item.value > 0);
    const total = valid.reduce((sum, item) => sum + item.value, 0);
    if (!valid.length || total <= 0) {
        legend.innerHTML = `<li class="composition-empty">${safe(emptyMessage)}</li>`;
        return;
    }
    const centerX = bounds.width / 2;
    const centerY = bounds.height / 2;
    const radius = Math.max(20, Math.min(bounds.width, bounds.height) * 0.43);
    let angle = -Math.PI / 2;
    const segments = [];
    valid.forEach((item, index) => {
        const next = angle + item.value / total * Math.PI * 2;
        context.beginPath();
        context.moveTo(centerX, centerY);
        context.arc(centerX, centerY, radius, angle, next);
        context.closePath();
        context.fillStyle = compositionColors[index % compositionColors.length];
        context.fill();
        segments.push({ item, index, startAngle: angle, sweep: next - angle });
        angle = next;
    });
    legend.innerHTML = valid.map((item, index) => {
        const value = item.formattedValue || fmt.format(item.value);
        const share = `${fmt.format(item.value / total * 100)}%`;
        return `<li><i style="--legend-color:${compositionColors[index % compositionColors.length]}"></i><button class="composition-legend-name" type="button" data-composition-index="${index}" aria-label="Show details for ${safe(item.label)}">${safe(item.label)}</button><strong>${safe(value)} · ${share}</strong></li>`;
    }).join("");

    const showDetails = (segment, x, y) => {
        const { item, index } = segment;
        const value = item.formattedValue || fmt.format(item.value);
        const share = `${fmt.format(item.value / total * 100)}%`;
        const details = (item.details || []).filter(([label]) => label.toLowerCase() !== (item.metric || "Value").toLowerCase());
        const rows = [[item.metric || "Value", value], ["Share", share], ...details];
        const detailRows = rows.map(([label, detail]) => `<span>${safe(label)}: ${safe(detail)}</span>`).join("");
        readout.innerHTML = `<strong>${safe(item.label)}</strong>${detailRows}`;
        readout.hidden = false;
        const hostRect = host.getBoundingClientRect();
        const tooltipX = Math.max(6, Math.min(hostRect.width - readout.offsetWidth - 6, x + 10));
        const tooltipY = Math.max(6, Math.min(hostRect.height - readout.offsetHeight - 6, y - readout.offsetHeight / 2));
        readout.style.left = `${tooltipX}px`;
        readout.style.top = `${tooltipY}px`;
        readout.style.setProperty("--slice-color", compositionColors[index % compositionColors.length]);
    };

    const showLegendDetails = (event) => {
        const button = event.target.closest("[data-composition-index]");
        if (!button) return;
        const segment = segments[Number(button.dataset.compositionIndex)];
        if (segment) showDetails(segment, bounds.width / 2, bounds.height / 2);
    };
    legend.onpointerover = showLegendDetails;
    legend.onpointerdown = showLegendDetails;
    legend.onfocusin = showLegendDetails;
    legend.onclick = showLegendDetails;

    const updateHover = (event) => {
        const rect = canvas.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const dx = x - centerX;
        const dy = y - centerY;
        if (Math.hypot(dx, dy) > radius) {
            readout.hidden = true;
            return;
        }
        const pointerAngle = (Math.atan2(dy, dx) + Math.PI * 2) % (Math.PI * 2);
        const segment = segments.find(({ startAngle, sweep }) => {
            const start = (startAngle + Math.PI * 2) % (Math.PI * 2);
            return (pointerAngle - start + Math.PI * 2) % (Math.PI * 2) < sweep;
        });
        if (!segment) {
            readout.hidden = true;
            return;
        }
        const hostRect = host.getBoundingClientRect();
        showDetails(segment, rect.left - hostRect.left + x, rect.top - hostRect.top + y);
    };
    canvas.onpointermove = updateHover;
    canvas.onpointerdown = updateHover;
    canvas.onpointerleave = (event) => {
        if (event.pointerType !== "touch") readout.hidden = true;
    };
}

async function loadCompositionChart(assets) {
    const mode = $("#market-composition-mode").value;
    if (mode === "blocks" || mode === "blockFees") {
        $("#market-composition-caption").textContent = mode === "blocks"
            ? "Share of transactions in the eight latest locally observed blocks."
            : "Share of total fees in BTC across the eight latest locally observed blocks.";
        const blocks = await api.blocks(0, 8);
        const items = blocks.map((block, index) => ({
            label: `Block ${fmt.format(block.height)}`,
            value: Number(mode === "blocks" ? block.transactionCount : block.totalFees),
            formattedValue: mode === "blocks" ? `${fmt.format(block.transactionCount)} tx` : `${fmt.format(block.totalFees || 0)} BTC`,
            metric: mode === "blocks" ? "Transactions" : "Fees",
            details: [["Height", fmt.format(block.height)], ["Time", dateTime(block.timestamp)], ["Size", `${fmt.format(block.size)} B`]],
            index
        }));
        drawCompositionChart(items, mode === "blocks" ? "No recent monitored blocks to compare." : "No block fee values are available yet.");
        return;
    }
    const settings = {
        marketCapUsd: ["Tracked assets' share of their combined market cap.", "Market cap"],
        volume24hUsd: ["Tracked assets' share of their combined 24-hour trading volume.", "24h volume"],
        circulatingSupply: ["Provider-reported token units; units differ between coins and are not directly comparable.", "Circulating supply"]
    };
    const [caption, label] = settings[mode];
    $("#market-composition-caption").textContent = caption;
    const items = assets.map((asset) => ({
        label: `${asset.name} · ${asset.symbol}`, value: Number(asset[mode]),
        formattedValue: mode === "circulatingSupply" ? fmt.format(Number(asset[mode]) || 0) : usd(asset[mode], true),
        metric: label,
        details: [
            ["Price", usd(asset.priceUsd)],
            ["24h change", asset.priceChange24hPct == null ? "—" : `${fmt.format(asset.priceChange24hPct)}%`],
            ["Market cap", usd(asset.marketCapUsd, true)],
            ["24h volume", usd(asset.volume24hUsd, true)],
            ["Circulating supply", asset.circulatingSupply == null ? "Not reported" : fmt.format(asset.circulatingSupply)],
            ["Total supply", asset.totalSupply == null ? "Not reported" : fmt.format(asset.totalSupply)],
            ["Market rank", asset.marketCapRank == null ? "Not reported" : `#${fmt.format(asset.marketCapRank)}`],
            ["Updated", dateTime(asset.lastUpdated)]
        ]
    }));
    drawCompositionChart(items, "The provider has not returned values for this measure.");
}

async function loadMarketData() {
    const range = $("#market-history-range").value;
    const horizon = Number($("#forecast-horizon").value);
    const customRange = range === "custom";
    $("#custom-history-controls").hidden = !customRange;
    const bounds = customRange ? getCustomHistoryBounds() : {};
    const data = await api.market();
    const providerAssets = Array.isArray(data.assets) ? data.assets : [];
    const assetsById = new Map(fallbackMarketAssets.map((asset) => [asset.id, asset]));
    providerAssets.forEach((asset) => assetsById.set(asset.id, asset));
    const assets = [...assetsById.values()];
    const marketAssetId = localStorage.getItem("market-analysis-asset")
        || $("#market-asset-select").value || "bitcoin";
    const forecastAssetId = localStorage.getItem("market-forecast-asset") || "bitcoin";
    const selectedAsset = assets.find((asset) => asset.id === marketAssetId) || assets[0];
    const forecastAsset = assets.find((asset) => asset.id === forecastAssetId) || assets[0];
    if (selectedAsset && forecastAsset) {
        const options = assets.map((asset) => `<option value="${safe(asset.id)}">${safe(asset.name)} · ${safe(asset.symbol)}</option>`).join("");
        $("#market-asset-select").innerHTML = options;
        $("#forecast-asset-select").innerHTML = options;
        $("#market-asset-select").value = selectedAsset.id;
        $("#forecast-asset-select").value = forecastAsset.id;
        localStorage.setItem("market-analysis-asset", selectedAsset.id);
        localStorage.setItem("market-forecast-asset", forecastAsset.id);
    }
    const coinId = selectedAsset?.id || "bitcoin";
    const forecastCoinId = forecastAsset?.id || "bitcoin";
    const [historyResult, forecastResult] = await Promise.all([
        api.marketHistory(coinId, range, bounds.from, bounds.to, selectedAsset?.symbol)
            .then((value) => ({ value }), (error) => ({ error })),
        api.marketForecast(forecastCoinId, "365", horizon, undefined, undefined, forecastAsset?.symbol)
            .then((value) => ({ value }), (error) => ({ error }))
    ]);
    const market = data.market;
    const global = data.global;
    const status = data.status || "DISCONNECTED";
    const statusText = data.error || (data.lastUpdated
        ? `Updated ${dateTime(data.lastUpdated)}${data.stale ? " · cached values shown" : ""}`
        : "Waiting for data from CoinGecko and Alternative.me");
    $("#market-status-text").textContent = statusText;
    $("#market-status-pill").textContent = data.stale ? "STALE" : status;
    $("#market-status-pill").dataset.state = data.stale ? "degraded" : status.toLowerCase();
    $("#market-summary-price").textContent = market ? usd(market.priceUsd) : "—";
    $("#market-summary-change").textContent = market?.priceChange24hPct == null ? "—" : `${Number(market.priceChange24hPct) > 0 ? "+" : ""}${fmt.format(market.priceChange24hPct)}%`;
    $("#market-summary-cap").textContent = market ? usd(market.marketCapUsd, true) : "—";
    $("#market-summary-updated").textContent = data.marketUpdatedAt ? `Updated ${dateTime(data.marketUpdatedAt)}` : "Awaiting CoinGecko data";
    const readings = Array.isArray(data.fearGreed) ? data.fearGreed : [];
    const latestSentiment = readings[0];
    window.marketAssets = assets;
    renderMarketActivityScreens(assets, data.stale);
    $("#market-summary-sentiment").textContent = latestSentiment?.value ?? "—";
    $("#market-summary-sentiment-label").textContent = latestSentiment?.classification || "Alternative.me index";
    $("#market-price").textContent = market ? usd(market.priceUsd) : "—";
    $("#market-change").textContent = market?.priceChange24hPct == null ? "—" : `${Number(market.priceChange24hPct) > 0 ? "+" : ""}${fmt.format(market.priceChange24hPct)}%`;
    $("#market-cap").textContent = market ? usd(market.marketCapUsd, true) : "—";
    $("#market-volume").textContent = market ? usd(market.volume24hUsd, true) : "—";
    $("#market-circulating").textContent = market ? supply(market.circulatingSupply) : "—";
    $("#market-total-supply").textContent = market ? supply(market.totalSupply) : "—";
    if ($("#market-max-supply")) $("#market-max-supply").textContent = market ? supply(market.maxSupply) : "—";
    if ($("#market-ath")) $("#market-ath").textContent = market ? usd(market.allTimeHighUsd) : "—";
    const athChange = market?.allTimeHighChangePct;
    if ($("#market-ath-detail")) $("#market-ath-detail").textContent = market?.allTimeHighAt
        ? `${athChange == null ? "" : `${fmt.format(athChange)}% from ATH · `}${dateTime(market.allTimeHighAt)}`
        : "CoinGecko reported";
    $("#market-rank").textContent = market?.marketCapRank == null ? "—" : `#${fmt.format(market.marketCapRank)}`;
    $("#market-dominance").textContent = global?.btcDominancePct == null ? "—" : `${fmt.format(global.btcDominancePct)}%`;
    $("#market-global-cap").textContent = global ? usd(global.totalMarketCapUsd, true) : "—";
    $("#market-global-volume").textContent = global ? usd(global.totalVolume24hUsd, true) : "—";
    $("#market-fng-value").textContent = latestSentiment?.value == null ? "—" : `${latestSentiment.value} / 100`;
    $("#market-fng-label").textContent = latestSentiment?.classification || "Awaiting index";
    $("#market-fng-marker").style.left = `${Math.max(0, Math.min(100, Number(latestSentiment?.value || 0)))}%`;
    $("#market-fng-updated").textContent = latestSentiment?.timestamp ? `Reading from ${dateTime(latestSentiment.timestamp)}` : "No sentiment data yet";
    const historyData = historyResult.value;
    const history = Array.isArray(historyData?.history) ? historyData.history : [];
    $("#market-history-source").textContent = historyData?.source
        ? `${historyData.source.toUpperCase()} · HISTORICAL PRICE DATA`
        : `PUBLIC ${selectedAsset?.symbol || "BTC"}/USD PRICE HISTORY`;
    const historyChart = $("#market-price-chart");
    const oneDayView = range === "1" || (customRange && bounds.to - bounds.from < 86_400);
    drawChart(historyChart, history.map((point) => point.priceUsd), "#ee762a",
        history.map((point) => oneDayView
            ? new Date(point.timestamp).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })
            : new Date(point.timestamp).toLocaleDateString(undefined, { month: "short", day: "numeric" })),
        history, oneDayView, { hoverDetails: selectedAsset });
    const dateLabel = (value) => value ? new Date(value).toLocaleDateString() : "—";
    const rangeLabels = { "1": "24 hours", "7": "7 days", "30": "30 days", "365": "1 year", max: "all available history", custom: "custom dates" };
    $("#market-history-title").textContent = `${selectedAsset?.symbol || "BTC"} / USD · ${rangeLabels[range] || "selected range"}`;
    $("#forecast-heading").textContent = `${forecastAsset?.symbol || "BTC"} price trend estimate`;
    $("#market-history-period").textContent = history.length
        ? `${historyData.source} · ${dateLabel(historyData.startDate)} to ${dateLabel(historyData.endDate)} · ${fmt.format(historyData.observationCount || history.length)} observations`
        : historyResult.error?.message || "No real provider observations are available for this range.";
    $("#market-history-empty").textContent = historyResult.error?.message || "No provider price history is available.";
    const prices = history.map((point) => Number(point.priceUsd)).filter(Number.isFinite);
    const firstPrice = prices[0];
    const lastPrice = prices.at(-1);
    $("#market-history-return").textContent = prices.length > 1
        ? `${lastPrice >= firstPrice ? "+" : ""}${fmt.format((lastPrice / firstPrice - 1) * 100)}%` : "—";
    $("#market-history-high").textContent = prices.length ? usd(Math.max(...prices)) : "—";
    $("#market-history-low").textContent = prices.length ? usd(Math.min(...prices)) : "—";
    const periodReturn = prices.length > 1 ? (lastPrice / firstPrice - 1) * 100 : null;
    const direction = $("#market-history-direction");
    direction.textContent = periodReturn == null ? "—" : periodReturn > 1 ? "Upward" : periodReturn < -1 ? "Downward" : "Sideways";
    direction.dataset.trend = periodReturn == null ? "" : periodReturn > 1 ? "up" : periodReturn < -1 ? "down" : "flat";

    const forecast = forecastResult.value;
    if (state.currentView === "forecast") {
        loadAllAssetForecasts(assets, horizon, forecastCoinId, forecast).catch(reportError);
    }
    if (state.currentView === "forecast" && forecast?.available) {
        state.forecastResults.set(forecast.coinId || forecastCoinId, forecast);
        state.forecastErrors.delete(forecast.coinId || forecastCoinId);
        renderForecastAssetRows(assets, state.forecastBulkCompleted, assets.length, Boolean(state.forecastBulkPromise));
    }
    const backtest = forecast?.backtest || {};
    $("#forecast-price").textContent = forecast?.available ? usd(forecast.forecastPriceUsd) : "—";
    $("#forecast-date").textContent = forecast?.available
        ? `Estimated for ${dateTime(forecast.forecastFor)} · last observed ${usd(forecast.lastObservedPriceUsd)}`
        : forecast?.message || forecastResult.error?.message || "Waiting for full provider history";
    $("#forecast-model").textContent = forecast?.model || "30-day log-linear trend baseline";
    $("#forecast-coverage").textContent = forecast?.available
        ? `${fmt.format(forecast.historyPoints)} observations · ${dateLabel(forecast.historyStart)} to ${dateLabel(forecast.historyEnd)}${forecast.stale ? " · cached" : ""}`
        : "Uses all available real provider history";
    $("#forecast-mae").textContent = Number.isFinite(backtest.maeUsd) ? usd(backtest.maeUsd) : "—";
    $("#forecast-mape").textContent = Number.isFinite(backtest.mapePct) ? `${fmt.format(backtest.mapePct)}%` : "—";
    $("#forecast-direction").textContent = Number.isFinite(backtest.directionalAccuracyPct) ? `${fmt.format(backtest.directionalAccuracyPct)}%` : "—";
    $("#forecast-precision").textContent = Number.isFinite(backtest.precisionPct) ? `${fmt.format(backtest.precisionPct)}%` : "—";
    $("#forecast-recall").textContent = Number.isFinite(backtest.recallPct) ? `${fmt.format(backtest.recallPct)}%` : "—";
    $("#forecast-f1").textContent = Number.isFinite(backtest.f1Pct) ? `${fmt.format(backtest.f1Pct)}%` : "—";
    $("#forecast-samples").textContent = fmt.format(backtest.samples || 0);
    $("#forecast-signal").textContent = forecast?.available ? forecast.signal || "HOLD" : "No reliable signal";
    $("#forecast-signal").dataset.state = forecast?.signal === "BUY WATCH" ? "up"
        : forecast?.signal === "SELL WATCH" ? "down" : "neutral";
    $("#forecast-note").textContent = forecast?.available
        ? `Walk-forward test at ${horizon}-day horizon · upward precision is the share of upward calls that were correct; recall is the share of actual upward moves found; F1 balances both · ${backtest.samples || 0} historical forecasts · baseline only, not financial advice.`
        : forecast?.message || forecastResult.error?.message || "Scores will appear when enough real history is available.";
    trackMarketForecast(forecast, assets, data);
    await loadCompositionChart(assets);

    const globalCap = Number(global?.totalMarketCapUsd);
    const assetRows = assets.map((asset) => {
        const share = globalCap > 0 && Number(asset.marketCapUsd) > 0
            ? `${fmt.format(Number(asset.marketCapUsd) / globalCap * 100)}%` : "—";
        const change = (value) => value == null ? "—" : `<span class="${Number(value) >= 0 ? "positive" : "negative"}">${Number(value) > 0 ? "+" : ""}${fmt.format(value)}%</span>`;
        return `<tr><td><strong>${safe(asset.name)}</strong><span class="asset-symbol">${safe(asset.symbol)}</span></td><td class="mono">${safe(usd(asset.priceUsd))}</td><td>${change(asset.priceChange24hPct)}</td><td>${change(asset.priceChange7dPct)}</td><td class="mono">${safe(usd(asset.marketCapUsd, true))}</td><td class="mono">${share}</td><td class="mono">${safe(usd(asset.volume24hUsd, true))}</td><td><a class="asset-detail-link" href="https://www.coingecko.com/en/coins/${encodeURIComponent(asset.id)}" target="_blank" rel="noopener noreferrer">Source ↗</a></td></tr>`;
    });
    $("#market-assets-table").innerHTML = assetRows.length
        ? assetRows.join("") : emptyRow(8, data.error || "Market asset list is unavailable until CoinGecko responds.");
    $("#market-trend-name").textContent = assets.length
        ? `${assets.reduce((leader, asset) => Number(asset.priceChange24hPct) > Number(leader.priceChange24hPct) ? asset : leader).name} · strongest 24h change`
        : "Market data unavailable";
    const table = $("#market-fng-history");
    table.innerHTML = readings.length ? readings.map((reading) => `<tr><td>${safe(dateTime(reading.timestamp))}</td><td class="mono">${safe(reading.value)} / 100</td><td>${safe(reading.classification)}</td></tr>`).join("")
        : emptyRow(3, "Sentiment history is unavailable until Alternative.me responds.");
}

function setHealth(name, value) {
    const label = $(`#status-${name}`);
    const dot = $(`#health-${name}`);
    if (label) label.textContent = value || "UNKNOWN";
    if (dot) dot.className = `health-dot ${["CONNECTED", "RUNNING"].includes(value) ? "ok" : ["DEGRADED", "CONNECTING"].includes(value) ? "warn" : "bad"}`;
}

function formatMarketSummaryRows(message) {
    if (!message || !String(message).includes("|")) return null;
    const rows = String(message).split(/\n/)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
            const match = line.match(/^(.+?)\s*\(([^)]+)\)\s*\|\s*([^|]+?)\s*\|\s*(.+)$/);
            if (!match) return null;
            const [, name, code, price, changeText] = match;
            const normalized = String(changeText).replace(/\s+/g, " ").trim();
            const changeMatch = normalized.match(/([+-]?\d+(?:\.\d+)?)%/);
            const changeValue = Number(changeMatch ? changeMatch[1] : 0);
            const hasMovement = changeText && Number.isFinite(changeValue);
            const direction = hasMovement && changeValue < 0 ? "down" : "up";
            const arrow = direction === "down" ? "↓" : "↑";
            const percent = changeMatch ? `${changeMatch[1]}%` : normalized;
            return `<div class="coin-price-row"><div class="coin-row-main"><span class="coin-row-name">${safe(name.trim())}</span><span class="coin-row-code">${safe(code.trim())}</span></div><span class="coin-row-price">${safe(price.trim())}</span><span class="coin-row-change ${direction}">${arrow} ${safe(percent)}</span></div>`;
        })
        .filter(Boolean);

    return rows.length ? `<div class="notification-market-summary">${rows.join("")}</div>` : null;
}

function formatMarketActivityRows(message) {
    if (!message || !String(message).includes("|")) return `<span>${safe(message || "No current market screen.")}</span>`;
    const rows = String(message).split(/\n/).map((line) => {
        const [asset, signal, price, change, turnover] = line.split("|").map((value) => value.trim());
        const match = asset?.match(/^(.+?)\s*\(([^)]+)\)$/);
        if (!match || !signal || !price || !change || !turnover) return null;
        const state = signal === "BUY WATCH" ? "buy" : "sell";
        return `<div class="notification-signal-row"><div><strong>${safe(match[1])}</strong><small>${safe(match[2])}</small></div><span class="market-signal-badge ${state}">${safe(signal)}</span><small>${safe(price)} · ${safe(change)} · ${safe(turnover)}</small></div>`;
    }).filter(Boolean);
    return rows.length ? `<div class="notification-market-signals">${rows.join("")}</div>` : `<span>${safe(message)}</span>`;
}

function renderNotificationList(items = []) {
    const list = document.getElementById("notification-list");
    if (!list) return;
    if (!Array.isArray(items) || items.length === 0) {
        list.innerHTML = '<div class="notification-empty">No alerts right now.</div>';
        return;
    }
    list.innerHTML = items.slice(0, 5).map((item) => {
        const severity = (item.severity || "INFO").toUpperCase();
        const tone = severity === "CRITICAL" || severity === "HIGH" ? "warning" : "alert";
        const body = item.title?.startsWith("Coin prices · 24-hour change")
            ? formatMarketSummaryRows(item.message)
            : item.title === "Market activity watch"
                ? formatMarketActivityRows(item.message)
            : `<span>${safe(item.message || "Monitoring event detected")}</span>`;
        return `<div class="notification-item ${tone}"><strong>${safe(item.title || "Network alert")}</strong>${body}<small>${safe(item.time || "Just now")}</small></div>`;
    }).join("");
}

function updateNotificationBell(stats = {}) {
    const badge = document.getElementById("notification-badge");
    const bell = document.getElementById("notification-bell");
    if (!badge || !bell) return;
    const stored = readNotificationEvents();
    const count = Math.max(stored.length, Number(stats.openAlerts || 0));
    const display = count > 99 ? "99+" : String(count);
    badge.textContent = display;
    badge.hidden = count === 0;
    bell.classList.toggle("has-alerts", count > 0);
}

async function refreshNotificationCenter() {
    try {
        const [stats, alerts, marketResult] = await Promise.all([
            api.stats(), api.alerts(), api.market().then((value) => ({ value }), (error) => ({ error }))
        ]);
        const market = marketResult.value;
        updateNotificationBell(stats);
        const stored = readNotificationEvents();
        const items = stored.length ? stored.slice(0, 5).map((item) => ({
            title: market?.stale && item.title === "Coin prices · 24-hour change"
                ? `${item.title} · cached` : item.title || "Network alert",
            message: item.message || "Monitoring event detected",
            severity: item.severity || "INFO",
            time: item.time ? dateTime(item.time) : "Just now"
        })) : Array.isArray(alerts) && alerts.length ? alerts.slice(0, 5).map((alert) => ({
            title: market?.stale && alert.alertType === "Coin prices · 24-hour change"
                ? `${alert.alertType} · cached` : alert.alertType || "Network alert",
            message: alert.message || "Monitoring event detected",
            severity: alert.severity || "INFO",
            time: dateTime(alert.detectedAt)
        })) : [{ title: "Monitor healthy", message: "No active alerts right now.", severity: "INFO", time: "System ready" }];
        items.unshift({
            title: "Market activity watch",
            message: marketActivityNotification(market),
            severity: "INFO",
            time: market?.marketUpdatedAt ? dateTime(market.marketUpdatedAt) : "Quote status unavailable"
        });
        renderNotificationList(items.slice(0, 5));
    } catch (error) {
        updateNotificationBell({ openAlerts: 0 });
        const stored = readNotificationEvents();
        renderNotificationList(stored.slice(0, 5));
    }
}

async function loadOverview() {
    const [stats, status, blockRows, txPage, traffic, market] = await Promise.all([
        api.stats(), api.status(), api.blocks(0, 6), api.transactions(0, 6),
        loadTrafficSnapshot("traffic-range", "traffic"), api.market()
    ]);
    const latest = stats.latestBlock;
    const previousBlockKey = "bitcoin-monitor-last-block-height";
    const previousBlock = Number(localStorage.getItem(previousBlockKey) || "0");
    if (latest && latest.height > previousBlock) {
        pushNotificationEvent("New block confirmed", `Block ${fmt.format(latest.height)} just confirmed with ${fmt.format(latest.transactionCount || 0)} transactions at ${dateTime(latest.timestamp)}.`, "INFO");
        localStorage.setItem(previousBlockKey, String(latest.height));
    } else if (latest && latest.height > 0 && previousBlock === 0) {
        localStorage.setItem(previousBlockKey, String(latest.height));
    }
    if (!market.stale && Array.isArray(market.assets) && market.assets.length) {
        const marketSummary = buildMarketSummaryMessage(market.assets);
        const marketSeenKey = "bitcoin-monitor-last-market-summary";
        const lastMarketSummary = localStorage.getItem(marketSeenKey);
        if (lastMarketSummary !== marketSummary) {
            pushNotificationEvent("Coin prices · 24-hour change", marketSummary, "INFO");
            localStorage.setItem(marketSeenKey, marketSummary);
        }
    }
    const hasCollectedTransactions = Number(stats.monitoredTransactions) > 0;
    $("#metric-block-time").textContent = latest ? `Observed ${dateTime(latest.timestamp)}` : "Waiting for network data";
    $("#metric-total").textContent = hasCollectedTransactions ? fmt.format(stats.transactionsPerHour || 0) : "—";
    $("#metric-volume").textContent = hasCollectedTransactions ? fmt.format(Number(stats.volumeBtcLastHour || 0)) : "—";
    if (!hasCollectedTransactions) {
        $("#metric-total-note").textContent = "Waiting for the first real block sample";
        $("#metric-volume-note").textContent = "No sampled transaction volume available yet";
    }
    const activeAddresses = market.activeAddresses;
    $("#metric-wallets").textContent = activeAddresses?.count == null
        ? "Unavailable" : fmt.format(activeAddresses.count);
    $("#metric-wallets-note").textContent = activeAddresses?.timestamp
        ? `Distinct addresses used · ${dateTime(activeAddresses.timestamp)} · Blockchain.com`
        : "Daily on-chain address count unavailable";
    $("#nav-alert-count").textContent = fmt.format(stats.openAlerts || 0);
    updateNotificationBell(stats);
    refreshNotificationCenter().catch(() => { });
    $("#last-sync").textContent = status.lastBlockSync ? `Last sync ${dateTime(status.lastBlockSync)}` : "Waiting for first sync";
    $("#health-sync").textContent = status.lastBlockSync ? dateTime(status.lastBlockSync) : "Not synced yet";
    setHealth("backend", status.backend); setHealth("database", status.database); setHealth("provider", status.blockchainProvider); setHealth("monitor", status.monitoring);
    $("#settings-api").textContent = API_BASE_URL;
    $("#settings-provider").textContent = status.lastError || "Provider status from the monitor";
    $("#settings-provider-state").textContent = status.blockchainProvider || "UNKNOWN";
    const risks = stats.riskCounts || {};
    for (const level of ["critical", "high", "medium", "normal"]) {
        $(`#risk-${level}`).textContent = fmt.format(risks[level.toUpperCase()] || 0);
    }
    const isDegraded = !["CONNECTED"].includes(status.blockchainProvider);
    $("#provider-banner").hidden = !isDegraded || sessionStorage.getItem("hide-provider-banner") === "true";
    $("#provider-message").textContent = status.lastError || "Showing the last successfully stored data. Automatic retries continue in the background.";
    $("#sidebar-dot").style.background = status.blockchainProvider === "CONNECTED" ? "#62b783" : "#e3a14c";
    $("#sidebar-provider").textContent = status.blockchainProvider === "CONNECTED" ? "Provider connected" : `Provider ${String(status.blockchainProvider).toLowerCase()}`;
    renderBlocks(blockRows, $("#recent-blocks"));
    renderTransactionRows(txPage.content, $("#recent-transactions"));
    renderTrafficChart($("#traffic-chart"), traffic, "traffic-range", "traffic", "transactions");
}

async function loadTransactions() {
    const result = await api.transactions(state.transactionPage, 25, state.txQuery, state.txLevel);
    renderTransactionRows(result.content, $("#transactions-table"), true);
    $("#transaction-count").textContent = `${fmt.format(result.totalElements || 0)} records`;
    $("#transaction-page-label").textContent = `Page ${result.number + 1} of ${Math.max(1, result.totalPages)}`;
    $("#transaction-prev").disabled = result.first;
    $("#transaction-next").disabled = result.last;
}

async function loadBlocks() {
    const result = await api.blocks(state.blockPage, 25);
    const target = $("#blocks-table");
    target.innerHTML = result?.length ? result.map((block) => `<tr><td><span class="height-link" data-block-height="${block.height}">${fmt.format(block.height)}</span></td><td class="mono hash-cell" data-block-hash="${safe(block.hash)}" title="${safe(block.hash)}">${safe(shortHash(block.hash, 20))}</td><td>${safe(dateTime(block.timestamp))}</td><td class="mono">${fmt.format(block.transactionCount)}</td><td>${fmt.format(block.size)} B</td><td class="mono">${fmt.format(block.weight)}</td><td class="mono">${fmt.format(block.averageFee || 0)} BTC</td></tr>`).join("") : emptyRow(7, "No monitored blocks yet.");
    $("#block-page-label").textContent = `Page ${state.blockPage + 1}`;
    $("#block-prev").disabled = state.blockPage === 0;
    $("#block-next").disabled = result.length < 25;
}

async function loadAnalytics() {
    const data = await loadTrafficSnapshot("analytics-range", "analytics");
    renderTrafficChart($("#analytics-tx-chart"), data, "analytics-range", "analytics", "transactions");
    renderTrafficChart($("#analytics-size-chart"), data, "analytics-range", "analytics", "blockSize", "#4c8e70");
    const rows = data.labels.map((time, index) => `<tr><td>${safe(dateTime(time))}</td><td class="mono">—</td><td class="mono">${fmt.format(data.transactions[index])}</td><td>${fmt.format(data.blockSize[index])} B</td><td class="mono">${fmt.format(data.averageFeeBtc[index])} BTC</td></tr>`).reverse();
    $("#analytics-table").innerHTML = rows.length ? rows.slice(0, 50).join("") : emptyRow(5, "No observations available.");
}

async function loadLiveTraffic() {
    const [data, stats] = await Promise.all([loadTrafficSnapshot("live-traffic-range", "live-traffic"), api.stats()]);
    renderTrafficChart($("#live-traffic-chart"), data, "live-traffic-range", "live-traffic", "transactions");
    $("#traffic-total").textContent = fmt.format(stats.transactionsPerHour || 0);
    $("#traffic-volume").textContent = fmt.format(Number(stats.volumeBtcLastHour || 0));
    $("#traffic-height").textContent = stats.latestBlock ? fmt.format(stats.latestBlock.height) : "—";
    $("#traffic-time").textContent = stats.latestBlock ? dateTime(stats.latestBlock.timestamp) : "Waiting for network data";
}

async function loadMempool() {
    const snapshot = await api.mempool();
    updateTrafficAssessment(snapshot);
    const mempool = snapshot.mempool;
    const fees = snapshot.fees;
    const status = snapshot.status || "DISCONNECTED";
    const statusNode = $("#mempool-status");
    statusNode.dataset.state = status.toLowerCase();
    statusNode.textContent = snapshot.error || (snapshot.lastUpdated
        ? `${status} · Snapshot updated ${dateTime(snapshot.lastUpdated)}`
        : `${status} · Waiting for the first provider snapshot`);
    $("#mempool-count").textContent = mempool ? fmt.format(mempool.count || 0) : "—";
    $("#mempool-size").textContent = mempool ? fmt.format((mempool.vsize || 0) / 1_000_000) : "—";
    $("#mempool-fee").textContent = fees ? fmt.format(fees.fastestFee || 0) : "—";
    $("#mempool-fee-note").textContent = fees
        ? `30 min ${fmt.format(fees.halfHourFee || 0)} · 1 hour ${fmt.format(fees.hourFee || 0)} sat/vB`
        : "Fee estimates unavailable";
    $("#mempool-updated").textContent = snapshot.lastUpdated
        ? `Snapshot ${dateTime(snapshot.lastUpdated)}` : "Awaiting live provider data";
    const rows = Array.isArray(snapshot.recentTransactions) ? snapshot.recentTransactions : [];
    $("#mempool-transactions").innerHTML = rows.length ? rows.map((tx) => {
        const feeRate = tx.vsize ? tx.fee / tx.vsize : 0;
        return `<tr><td class="mono hash-cell" data-txid="${safe(tx.txid)}" title="${safe(tx.txid)}">${safe(shortHash(tx.txid, 20))}</td><td class="mono">${fmt.format(tx.vsize || 0)} vB</td><td class="mono">${fmt.format(tx.fee || 0)} sat</td><td class="mono">${fmt.format(feeRate)} sat/vB</td><td class="mono">${fmt.format((tx.value || 0) / 100_000_000)} BTC</td></tr>`;
    }).join("") : emptyRow(5, snapshot.error ? "Mempool provider is unavailable. Recent transactions will return when it reconnects." : "No recent unconfirmed transactions were returned.");
}

function csvCell(value) {
    const text = String(value ?? "");
    return `"${text.replaceAll('"', '""')}"`;
}

async function exportReport(type) {
    const loaders = {
        transactions: async () => (await api.transactions(0, 100)).content,
        blocks: () => api.blocks(0, 100),
        alerts: () => api.alerts()
    };
    const rows = await loaders[type]();
    if (!rows.length) throw new Error(`No ${type} data is available to export.`);
    const columns = Object.keys(rows[0]);
    const csv = [columns, ...rows.map((row) => columns.map((column) => row[column]))]
        .map((row) => row.map(csvCell).join(",")).join("\r\n");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    link.download = `bitcoin-${type}-${new Date().toISOString().slice(0, 10)}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
}

async function loadAnomalies() {
    const rows = await api.anomalies();
    $("#anomalies-table").innerHTML = rows.length ? rows.map((tx) => `<tr><td class="mono hash-cell" data-txid="${safe(tx.txid)}">${safe(shortHash(tx.txid, 19))}</td><td class="mono">${fmt.format(tx.blockHeight)}</td><td>${safe(dateTime(tx.timestamp))}</td><td class="mono">${fmt.format(tx.outputValue)} BTC</td><td class="mono">${fmt.format(tx.anomalyScore)}</td><td><span class="risk-badge ${tx.anomalyLevel.toLowerCase()}">${safe(tx.anomalyLevel)}</span></td><td class="explanation-cell">${safe(JSON.parse(tx.anomalyReasons || "[]").join(" "))}</td></tr>`).join("") : emptyRow(7, "No outliers detected in collected data.");
}

async function loadAlerts() {
    const rows = await api.alerts($("#alert-severity").value);
    $("#alerts-table").innerHTML = rows.length ? rows.map((alert) => `<tr><td class="severity ${alert.severity}">${safe(alert.severity)}</td><td class="mono">${safe(alert.alertType)}</td><td>${safe(alert.message)}</td><td>${safe(dateTime(alert.detectedAt))}</td><td class="mono">${safe(alert.metric)} · ${fmt.format(alert.observedValue)}</td><td>${alert.resolved ? "Resolved" : "Open"}</td><td>${alert.resolved ? "" : `<button class="resolve-button" data-resolve-alert="${alert.id}">Resolve</button>`}</td></tr>`).join("") : emptyRow(7, "No alerts have been generated.");
}

async function openTransaction(txid) {
    const data = await api.transaction(txid);
    const external = data.source === "external";
    const tx = external ? data.transaction : data;
    const title = external ? tx.txid : tx.txid;
    const fields = external ? [
        ["Status", tx.status?.confirmed ? "Confirmed" : "Unconfirmed"], ["Block height", tx.status?.block_height ?? "Unconfirmed"],
        ["Size", `${tx.size ?? "—"} bytes`], ["Weight", tx.weight ?? "—"], ["Fee", `${tx.fee ?? "—"} satoshis`]
    ] : [
        ["Block", tx.blockHeight], ["Timestamp", dateTime(tx.timestamp)], ["Inputs / outputs", `${tx.inputCount} / ${tx.outputCount}`],
        ["Input value", `${fmt.format(tx.inputValue)} BTC`], ["Output value", `${fmt.format(tx.outputValue)} BTC`],
        ["Fee", `${fmt.format(tx.fee)} BTC`], ["Fee rate", `${fmt.format(tx.feeRate)} sat/vB`],
        ["Size / weight", `${tx.transactionSize} B / ${tx.transactionWeight} WU`], ["Model score", `${fmt.format(tx.anomalyScore)} / 100`], ["Assessment", tx.anomalyLevel]
    ];
    $("#detail-content").innerHTML = `<h2 class="dialog-title">Transaction details</h2><p class="dialog-sub">${safe(title)} · ${external ? "Retrieved from external blockchain provider" : "Found locally"}</p><div class="detail-grid">${fields.map(([name, value]) => `<div class="detail-field"><span>${safe(name)}</span><strong>${safe(value)}</strong></div>`).join("")}</div><p class="detail-explanation">${external ? "External details are displayed as returned by the configured public provider." : safe(JSON.parse(tx.anomalyReasons || "[]").join(" "))}</p><a class="external-link" target="_blank" rel="noopener noreferrer" href="https://mempool.space/tx/${encodeURIComponent(tx.txid)}">Verify on mempool.space ↗</a>`;
    $("#detail-dialog").showModal();
}

async function openBlock(hashOrHeight) {
    let block;
    try { block = await api.search(String(hashOrHeight)); }
    catch (error) { showToast(error.message); return; }
    const item = block.result;
    $("#detail-content").innerHTML = `<h2 class="dialog-title">Block ${fmt.format(item.height)}</h2><p class="dialog-sub">${safe(item.hash)} · Found locally</p><div class="detail-grid">${[["Timestamp", dateTime(item.timestamp)], ["Transactions", item.transactionCount], ["Size", `${fmt.format(item.size)} bytes`], ["Weight", fmt.format(item.weight)], ["Total monitored fees", `${fmt.format(item.totalFees)} BTC`], ["Average fee", `${fmt.format(item.averageFee)} BTC`]].map(([name, value]) => `<div class="detail-field"><span>${safe(name)}</span><strong>${safe(value)}</strong></div>`).join("")}</div><p><a class="external-link" target="_blank" rel="noopener noreferrer" href="https://mempool.space/block/${encodeURIComponent(item.hash)}">Verify on mempool.space ↗</a></p>`;
    $("#detail-dialog").showModal();
}

let toastTimer;
function showToast(message) {
    let toast = $("#toast");
    if (!toast) { toast = document.createElement("div"); toast.id = "toast"; toast.className = "toast"; document.body.append(toast); }
    toast.textContent = message;
    toast.classList.add("visible");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 3200);
}

async function enableBrowserAlerts(button) {
    if (!("Notification" in window)) {
        showToast("Browser notifications are not supported here.");
        return;
    }
    const permission = await Notification.requestPermission();
    const enabled = permission === "granted";
    localStorage.setItem("browser-alerts-enabled", String(enabled));
    button.textContent = enabled ? "Browser alerts enabled" : "Browser alerts blocked";
    showToast(enabled ? "Price and traffic alerts are enabled." : "Allow notifications in browser settings to receive alerts.");
    if (enabled) void pollBrowserAlerts();
}

async function pollBrowserAlerts() {
    if (localStorage.getItem("browser-alerts-enabled") !== "true"
        || !("Notification" in window) || Notification.permission !== "granted") return;
    try {
        const [market, traffic] = await Promise.all([api.market(), api.mempool()]);
        checkRapidPriceAlerts(market);
        updateTrafficAssessment(traffic);
    } catch (error) {
        console.debug("Alert snapshot check failed:", error.message);
    }
}

const viewCopy = {
    overview: ["Dashboard", "Bitcoin Network Overview", "Live transaction activity and model-derived risk signals."],
    market: ["Market Data", "Bitcoin market overview", "Live price, supply, global crypto metrics, historical prices, and market sentiment."],
    forecast: ["AI Predictions", "Bitcoin and coin watch signals", "Review fitted price trends, walk-forward precision and accuracy, and high-turnover market screens."],
    "live-traffic": ["Live Traffic", "Live transaction traffic", "Mempool conditions, fee estimates, and observed confirmed-block activity."],
    transactions: ["Transactions", "Monitored transactions", "Search and inspect transactions collected from confirmed blocks."],
    blocks: ["Blocks", "Observed blocks", "Recent confirmed blocks recorded by this monitor."],
    analytics: ["Network Analytics", "Historical network analysis", "Calculated metrics from locally monitored blockchain data."],
    anomalies: ["AI Analysis", "Unusual transaction patterns", "Explainable statistical outliers from the recent monitored baseline."],
    alerts: ["Risk Alerts", "Network risk alerts", "Review and resolve model-generated monitoring alerts."],
    wallets: ["Wallets", "Wallet activity", "Address-level information is not collected by this monitor."],
    reports: ["Reports", "Export monitored data", "Download locally collected transaction, block, and alert records."],
    settings: ["Settings", "Monitor settings", "Review connection health and automatic dashboard refresh settings."]
};

function setView(name, updateHash = true) {
    state.currentView = name;
    const panelName = name === "forecast" ? "market" : name;
    document.querySelectorAll(".view-panel").forEach((panel) => panel.classList.toggle("active", panel.id === `view-${panelName}`));
    document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.view === name));
    const [page, title, subtitle] = viewCopy[name] || viewCopy.overview;
    $("#page-name").textContent = page; $("#view-title").textContent = title; $("#view-subtitle").textContent = subtitle;
    if (name === "transactions") loadTransactions().catch(reportError);
    if (name === "market" || name === "forecast") loadMarketData().catch(reportError);
    if (name === "blocks") loadBlocks().catch(reportError);
    if (name === "live-traffic") {
        loadLiveTraffic().catch(reportError);
        loadMempool().catch(reportError);
    }
    if (name === "analytics") loadAnalytics().catch(reportError);
    if (name === "anomalies") loadAnomalies().catch(reportError);
    if (name === "alerts") loadAlerts().catch(reportError);
    if (updateHash) location.hash = name === "overview" ? "dashboard" : name;
    if (name === "forecast") {
        window.setTimeout(() => $("#market-forecast-panel").scrollIntoView({ behavior: "instant", block: "start" }), 0);
    }
}

function reportError(error) { console.error(error); showToast(error.message || "Could not refresh data. Retrying automatically."); }
function refreshCurrent() {
    const jobs = [];
    if (state.currentView === "overview") jobs.push(loadOverview());
    if (state.currentView === "market" || state.currentView === "forecast") jobs.push(loadMarketData());
    if (state.currentView === "transactions") jobs.push(loadTransactions());
    if (state.currentView === "blocks") jobs.push(loadBlocks());
    if (state.currentView === "live-traffic") jobs.push(loadLiveTraffic(), loadMempool());
    if (state.currentView === "analytics") jobs.push(loadAnalytics());
    if (state.currentView === "anomalies") jobs.push(loadAnomalies());
    if (state.currentView === "alerts") jobs.push(loadAlerts());
    return Promise.allSettled(jobs).then((results) => results.filter((result) => result.status === "rejected").forEach((result) => reportError(result.reason)));
}

document.querySelectorAll(".nav-item").forEach((item) => item.addEventListener("click", () => setView(item.dataset.view)));
document.querySelectorAll("[data-open-view]").forEach((button) => button.addEventListener("click", () => setView(button.dataset.openView)));
$("#refresh-button").addEventListener("click", refreshCurrent);
async function runGlobalSearch() {
    const query = $("#global-search").value.trim();
    if (!query) return;
    try {
        const found = await api.search(query);
        if (found.type === "transaction") await openTransaction(found.result.txid);
        else await openBlock(found.result.hash);
    } catch (error) { showToast(error.message || "No monitored record found."); }
}
$("#global-search-submit").addEventListener("click", runGlobalSearch);
$("#global-search").addEventListener("keydown", (event) => { if (event.key === "Enter") runGlobalSearch(); });
function bindTrafficRangeControl(rangeId, prefix, canvasId, refresh) {
    const select = $(`#${rangeId}`);
    const customControls = $(`#${prefix}-custom-range`);
    const applyButton = $(`#${prefix}-apply`);
    if (!select) return;
    const apply = async () => {
        hideChartHover($(`#${canvasId}`).parentElement);
        if (applyButton) {
            applyButton.disabled = true;
            applyButton.dataset.originalText = applyButton.textContent;
            applyButton.textContent = "Loading...";
        }
        try {
            await refresh();
        } catch (error) {
            reportError(error);
        } finally {
            if (applyButton) {
                applyButton.disabled = false;
                applyButton.textContent = applyButton.dataset.originalText || "Apply";
                delete applyButton.dataset.originalText;
            }
        }
    };
    if (!customControls || !applyButton) {
        select.addEventListener("change", () => {
            if (select.value !== "custom") apply();
        });
        return;
    }
    select.addEventListener("change", () => {
        const custom = select.value === "custom";
        customControls.hidden = !custom;
        if (!custom) apply();
    });
    applyButton.addEventListener("click", apply);
}

const marketHistoryRanges = ["1", "7", "30", "365", "max", "custom"];
const savedMarketRange = localStorage.getItem("market-history-range");
if (marketHistoryRanges.includes(savedMarketRange)) $("#market-history-range").value = savedMarketRange;
for (const [selector, storageKey] of [["#market-drop-threshold", "market-drop-threshold"], ["#market-rise-threshold", "market-rise-threshold"]]) {
    const input = $(selector);
    input.value = localStorage.getItem(storageKey) || "3";
    input.addEventListener("change", () => {
        input.value = String(Math.max(0.1, Math.min(50, Number(input.value) || 3)));
        localStorage.setItem(storageKey, input.value);
    });
}
const today = new Date().toISOString().slice(0, 10);
$("#market-history-from").value = localStorage.getItem("market-history-from") || "2009-01-03";
$("#market-history-to").value = localStorage.getItem("market-history-to") || today;
$("#market-history-from").max = today;
$("#market-history-to").max = today;
const defaultTrafficFrom = new Date(Date.now() - 6 * 86_400_000).toISOString().slice(0, 10);
for (const prefix of ["traffic", "live-traffic", "analytics"]) {
    const from = $(`#${prefix}-from`);
    const to = $(`#${prefix}-to`);
    if (!from || !to) continue;
    from.value = defaultTrafficFrom;
    to.value = today;
    from.max = today;
    to.max = today;
}
bindTrafficRangeControl("traffic-range", "traffic", "traffic-chart", loadOverview);
bindTrafficRangeControl("live-traffic-range", "live-traffic", "live-traffic-chart", loadLiveTraffic);
bindTrafficRangeControl("analytics-range", "analytics", "analytics-tx-chart", loadAnalytics);
const savedForecastHorizon = localStorage.getItem("market-forecast-horizon");
if (["1", "7", "30", "90"].includes(savedForecastHorizon)) $("#forecast-horizon").value = savedForecastHorizon;
$("#market-history-range").addEventListener("change", (event) => {
    hideChartHover($("#market-price-chart").parentElement);
    if (event.target.value === "custom") $("#custom-history-controls").hidden = false;
    localStorage.setItem("market-history-range", event.target.value);
    if (event.target.value !== "custom") loadMarketData().catch(reportError);
});
$("#market-history-apply").addEventListener("click", () => {
    hideChartHover($("#market-price-chart").parentElement);
    localStorage.setItem("market-history-from", $("#market-history-from").value);
    localStorage.setItem("market-history-to", $("#market-history-to").value);
    loadMarketData().catch(reportError);
});
$("#forecast-horizon").addEventListener("change", (event) => {
    localStorage.setItem("market-forecast-horizon", event.target.value);
    loadMarketData().catch(reportError);
});
$("#market-asset-select").addEventListener("change", (event) => {
    localStorage.setItem("market-analysis-asset", event.target.value);
    loadMarketData().catch(reportError);
});
$("#forecast-asset-select").addEventListener("change", (event) => {
    localStorage.setItem("market-forecast-asset", event.target.value);
    loadMarketData().catch(reportError);
});
$("#forecast-assets-body").addEventListener("click", (event) => {
    const button = event.target.closest("[data-forecast-coin]");
    if (!button) return;
    $("#forecast-asset-select").value = button.dataset.forecastCoin;
    $("#forecast-asset-select").dispatchEvent(new Event("change"));
    $("#market-forecast-panel").scrollIntoView({ behavior: "smooth", block: "start" });
});
$("#market-composition-mode").addEventListener("change", () => loadCompositionChart(window.marketAssets || []).catch(reportError));
$("#market-notifications").addEventListener("click", (event) => enableBrowserAlerts(event.currentTarget));
$("#traffic-notifications")?.addEventListener("click", (event) => enableBrowserAlerts(event.currentTarget));
$("#notification-bell")?.addEventListener("click", () => {
    const panel = $("#notification-panel");
    if (!panel) return;
    panel.hidden = !panel.hidden;
    if (!panel.hidden) refreshNotificationCenter().catch(reportError);
});
$("#notification-mark-read")?.addEventListener("click", () => {
    $("#notification-panel").hidden = true;
    setView("alerts");
});
document.addEventListener("click", (event) => {
    const target = event.target;
    if (!target.closest(".notification-wrap")) {
        const panel = $("#notification-panel");
        if (panel) panel.hidden = true;
    }
});
$("#transaction-search").addEventListener("input", (event) => { state.txQuery = event.target.value.trim(); state.transactionPage = 0; clearTimeout(window.txSearchTimer); window.txSearchTimer = setTimeout(() => loadTransactions().catch(reportError), 250); });
$("#transaction-level").addEventListener("change", (event) => { state.txLevel = event.target.value; state.transactionPage = 0; loadTransactions().catch(reportError); });
$("#transaction-prev").addEventListener("click", () => { state.transactionPage = Math.max(0, state.transactionPage - 1); loadTransactions().catch(reportError); });
$("#transaction-next").addEventListener("click", () => { state.transactionPage++; loadTransactions().catch(reportError); });
$("#block-prev").addEventListener("click", () => { state.blockPage = Math.max(0, state.blockPage - 1); loadBlocks().catch(reportError); });
$("#block-next").addEventListener("click", () => { state.blockPage++; loadBlocks().catch(reportError); });
$("#alert-severity").addEventListener("change", () => loadAlerts().catch(reportError));
$("#alerts-table").addEventListener("click", (event) => { const button = event.target.closest("[data-resolve-alert]"); if (button) api.resolveAlert(button.dataset.resolveAlert).then(() => Promise.all([loadAlerts(), loadOverview()])).catch(reportError); });
document.querySelectorAll("[data-report]").forEach((button) => button.addEventListener("click", () => exportReport(button.dataset.report).catch(reportError)));
$("#refresh-interval").value = String(state.refreshSeconds);
$("#refresh-interval").addEventListener("change", (event) => {
    state.refreshSeconds = Number(event.target.value);
    localStorage.setItem("dashboard-refresh-seconds", String(state.refreshSeconds));
    window.clearInterval(refreshTimer);
    refreshTimer = window.setInterval(refreshCurrent, state.refreshSeconds * 1000);
});
document.addEventListener("click", (event) => {
    const tx = event.target.closest("[data-txid]");
    const blockHeight = event.target.closest("[data-block-height]");
    const blockHash = event.target.closest("[data-block-hash]");
    if (tx) openTransaction(tx.dataset.txid).catch(reportError);
    else if (blockHeight) openBlock(blockHeight.dataset.blockHeight);
    else if (blockHash) openBlock(blockHash.dataset.blockHash);
});
$("#dialog-close").addEventListener("click", () => $("#detail-dialog").close());
$("#detail-dialog").addEventListener("click", (event) => { if (event.target === $("#detail-dialog")) $("#detail-dialog").close(); });
$("#banner-close").addEventListener("click", () => { sessionStorage.setItem("hide-provider-banner", "true"); $("#provider-banner").hidden = true; });
window.addEventListener("resize", () => {
    if (state.currentView === "overview") loadOverview().catch(() => { });
    if (state.currentView === "market") {
        loadMarketData().catch(() => { });
        loadCompositionChart(window.marketAssets || []).catch(() => { });
    }
});
window.addEventListener("hashchange", () => {
    const requested = location.hash.slice(1);
    const name = requested === "dashboard" ? "overview" : requested;
    if (viewCopy[name] && name !== state.currentView) setView(name, false);
});

function setSocketStatus(status) {
    state.socket = status;
    const labels = { connected: ["CONNECTED", "connected"], connecting: ["CONNECTING", "degraded"], disconnected: ["RECONNECTING", "degraded"] };
    const [text, css] = labels[status];
    $("#status-socket").textContent = text;
    $("#health-socket").className = `health-dot ${css === "connected" ? "ok" : "warn"}`;
    const chip = $("#connection-chip"); chip.className = `connection-chip ${css}`; chip.querySelector("span").textContent = status;
}

function updateClock() { $("#clock").textContent = `${new Date().toLocaleTimeString()} LOCAL`; }
updateClock(); window.setInterval(updateClock, 1000);
setSocketStatus("connecting");
const initialView = location.hash.slice(1);
if (initialView === "dashboard") setView("overview");
else if (viewCopy[initialView]) setView(initialView); else refreshCurrent();
connectLive((event) => {
    if (event.type === "block") {
        $("#provider-banner").hidden = true;
        sessionStorage.removeItem("hide-provider-banner");
        showToast(`New block observed: ${fmt.format(event.block.height)}`);
        refreshCurrent();
    } else if (event.type === "status") refreshCurrent();
}, setSocketStatus);
refreshTimer = window.setInterval(refreshCurrent, state.refreshSeconds * 1000);
window.setInterval(() => { void pollBrowserAlerts(); }, 2000);