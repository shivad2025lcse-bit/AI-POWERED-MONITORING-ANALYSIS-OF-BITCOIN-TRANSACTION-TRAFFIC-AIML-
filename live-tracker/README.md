# Bitcoin Live Tracker

Real Bitcoin mainnet data is fetched from the public mempool.space REST API. No API key or wallet credentials are needed. This tracker is a separate Python/Flask dashboard alongside the Java monitor in the parent folder.

## Requirements

- Python 3.9 or newer
- Internet access to `https://mempool.space`
- Install packages from this directory:

```powershell
python -m pip install -r requirements.txt
```

## Run the live tracker

From the workspace root in PowerShell:

```powershell
python live-tracker/app.py --port 5000 --poll 6 --db live-tracker/tracker.db
```

Open [http://localhost:5000](http://localhost:5000). The `--demo` switch uses generated sample data and is never enabled by default. For actual monitoring leave it out. If port 5000 is busy, use `--port 5001`.

The worker bootstraps recent blocks and confirmed transactions for its CPU Isolation Forest, then polls the recent mempool transactions every 6 seconds, checks the chain tip every 20 seconds, and stores one-minute metrics. The SQLite database is created at the path supplied to `--db`.

## Data labels and limitations

- Block, transaction, and mempool values originate from mempool.space.
- The recent mempool endpoint returns at most 10 transactions per request. The dashboard labels its transactions/minute as a captured sample, not total network throughput.
- The model learns from confirmed recent transactions, evaluates fee rate, size, value, and fee/value ratio, and explains model-derived outliers. Outliers are not proof of fraud and do not identify an address owner.
- Public API availability/rate limits can interrupt collection. The Flask dashboard remains available and shows the collector's error status; it does not substitute generated transactions in live mode.
- Historical exports include only collected records. `export_real_dataset.py` can separately fetch sampled historical records when the public API is reachable.

## APIs and exports

JSON: `/api/summary`, `/api/monitoring?limit=60`, `/api/blocks`, `/api/transactions`, `/api/anomalies`.

CSV: `/export/blocks.csv`, `/export/live_transactions.csv`, `/export/anomaly_detection.csv`, `/export/monitoring_statistics.csv`.

Fetch an optional historical sample from this directory:

```powershell
python live-tracker/export_real_dataset.py --blocks 61 --txs-per-block 4 --out live-tracker/real_dataset
```

This export makes real external API requests; it will fail rather than invent records when network access is unavailable.
