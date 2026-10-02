# Bitcoin Traffic Monitor

A monitoring-only educational application that records real Bitcoin mainnet blocks from the public mempool.space API, calculates metrics from its locally collected sample, and highlights statistical outliers. It does not handle wallets, keys, signing, or funds.

## Start Quickly

Requirements: Java 25 or newer and Maven 3.9+. A local file-backed H2 database is the default so the dashboard can start without installing MySQL. MySQL 8 is supported with the `mysql` Spring profile.

From Windows PowerShell in the project folder:

```powershell
mvn clean test
mvn spring-boot:run
```

Open [http://localhost:8081](http://localhost:8081). The first provider poll starts shortly after startup. If the public API cannot be reached, the app and dashboard remain available and show provider health plus the last successful sync.

Build a runnable artifact with `mvn clean package`, then run `java -jar target/bitcoin-traffic-monitor-1.0.0.jar`.

## MySQL Setup

1. Install and start MySQL 8.
2. In MySQL Workbench, open and run `database/schema.sql`. The application can also create/update tables through Hibernate.
3. Set the profile and credentials in PowerShell. Do not put a real password in source control:

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_NAME = "bitcoin_monitoring"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-local-password"
mvn spring-boot:run
```

The default H2 data file is `./data/bitcoin-monitor`; it is local development data and is excluded from Git. Use a persistent MySQL database for shared or long-running deployments.

## Configuration

`src/main/resources/application.properties` contains safe defaults. Environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `BLOCKCHAIN_API_BASE_URL` | `https://mempool.space/api` | Public provider base URL |
| `MONITOR_INTERVAL_MS` | `15000` | Minimum polling interval |
| `MAX_TRANSACTIONS_PER_BLOCK` | `25` | Transactions sampled from each new block |
| `RETENTION_DAYS` | `30` | Stored record retention |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `3306`, `bitcoin_monitoring` | MySQL connection |
| `DB_USERNAME`, `DB_PASSWORD` | `root`, unset | MySQL credentials; configure outside source |

Copy `.env.example` as a reference; Spring does not load `.env` automatically. Set values in the process environment or your IDE launch configuration.

## What Is Collected

The provider is isolated in `provider/` and uses mempool.space endpoints for tip height, block hash/details, block transaction pages, transaction details, current mempool size, recommended fee rates, and recent unconfirmed transactions. If mempool.space is unavailable, the same Esplora requests fall back to Blockstream (`BLOCKCHAIN_FALLBACK_API_BASE_URL`). Mempool data is cached in memory and refreshed every 30 seconds by default; configure `MEMPOOL_INTERVAL_MS` to change the interval. Requests have connect/read timeouts and bounded retries, including HTTP 429. The monitor stores each block once by hash and each transaction once by txid. Block height is not unique so a chain reorganization does not overwrite a competing block.

To limit public API load, at most 25 transactions are sampled from each newly observed block by default. Block transaction count and size come from the provider; transaction-derived values and statistics describe the locally observed sample, not the complete Bitcoin network. The initial monitor follows the chain tip and does not backfill old blocks. A provider outage does not generate substitute or random records.

## Anomaly Analysis

The CPU-only detector compares fee rate, transaction size, and output value with the last 100 stored transactions after at least eight observations exist. It uses absolute z-scores from the sample mean and standard deviation, maps the maximum deviation to a bounded 0–100 score, and includes the contributing feature in its explanation. Thresholds are a transparent baseline, not a trained classifier. An anomaly is not evidence of fraud, crime, or identity.

## Dashboard and Live Updates

Spring serves the static website at `/`. The dashboard includes live traffic, paginated transactions, AI anomaly analysis, risk alerts, network analytics, CSV reports for the latest 100 transactions/blocks and alerts, and connection settings with a configurable 15/30/60-second refresh. The market page supports custom BTC/USD dates and all available historical price observations from Blockchain.com, plus shorter preset ranges from CoinGecko. Bitcoin began in 2009, so twenty years of Bitcoin prices do not exist; historical coverage starts when price observations are available. Search covers locally collected records. A native WebSocket at `/ws/live` publishes newly synchronized blocks; the browser reconnects with bounded backoff and refreshes REST data on the selected interval. For standalone frontend work, the API allows `http://localhost:5500`; change `window.API_BASE_URL` before loading `js/dashboard.js` if the backend is elsewhere.

Charts use only stored records and native canvas drawing; no chart CDN is required. Browser fonts are optional and fall back to system sans-serif if Google Fonts are unreachable.

## API

All responses are JSON. Transaction/block collections are paginated and capped at 100 records per request.

| Method and path | Purpose |
| --- | --- |
| `GET /api/blocks/latest` | Most recently stored block |
| `GET /api/blocks?page=0&size=20` | Stored blocks, newest first |
| `GET /api/blocks/{height}` | Locally stored block by height |
| `GET /api/blocks/hash/{hash}` | Locally stored block by hash |
| `GET /api/transactions?page=0&size=25&q=&level=` | Paginated transaction sample/search/filter |
| `GET /api/transactions/{txid}` | Local record or external provider lookup |
| `GET /api/statistics/current` | Summary metrics and sync status |
| `GET /api/statistics/transactions-per-minute` | Per-minute counts for the last hour |
| `GET /api/statistics/fees` | Recent sampled fee rates |
| `GET /api/statistics/traffic?hours=24` | Per-block transaction, size, fee series |
| `GET /api/anomalies` | Outlier transaction records |
| `GET /api/anomalies/{txid}` | Outlier details |
| `GET /api/alerts?severity=HIGH` | Recent alert list/filter |
| `PUT /api/alerts/{id}/resolve` | Resolve a stored alert |
| `GET /api/search?q={txid-or-hash-or-height}` | Search locally collected records |
| `GET /api/monitoring/status` | Backend, DB, provider, scheduler health |
| `GET /actuator/health` | Spring health endpoint |

Not-found and unexpected errors return a consistent JSON object and never include server stack traces.

## Tests and VS Code

Run `mvn test` or `mvn clean test`. Tests cover insufficient anomaly history, feature outlier scoring, and duplicate-block idempotency without requiring a live API or database. Open the folder in VS Code and use the `Bitcoin Traffic Monitor` launch configuration, or run the PowerShell commands above. Maven wrapper files are not included; use a Maven installation available on PATH.

## Limitations and Troubleshooting

- A new block may not appear until the next poll. Only current tip blocks are monitored; missed blocks are not backfilled yet.
- The 25 transaction cap means the database is a sample. Fee/volume averages must not be read as official network-wide statistics. Mempool counts and fee estimates are live provider snapshots and may be unavailable during upstream outages.
- Wallet addresses are not stored or indexed, so active-wallet counts and wallet identity analysis are not available. The dashboard labels this limitation instead of estimating identities.
- Public provider availability and rate limits are outside the application's control. Check the dashboard provider status and `lastError`; retry intervals are bounded.
- If port 8081 is busy, set `$env:SERVER_PORT = "8082"` or another free port and open that port.
- If MySQL is selected and startup fails, verify the service, database name, driver profile, and credentials. Never commit real credentials.
- H2 console (development only) is at `/dev/h2-console`; it is disabled in the MySQL profile.
- Retention cleanup runs once per day after the configured age. The current tip is retained under normal operation.
- This first version stores transaction summaries, not normalized input/output address records. It does not store private keys or attempt wallet ownership attribution.

The application is intended for educational/research use, not financial, security, or law-enforcement conclusions.