"""Download REAL Bitcoin data from mempool.space and write the 4 project CSVs.
Usage:  python export_real_dataset.py --blocks 61 --txs-per-block 4 --out real_dataset
"""
import argparse, csv, os, random, time
import numpy as np
import btc_common as bc

ap = argparse.ArgumentParser()
ap.add_argument("--blocks", type=int, default=61)
ap.add_argument("--txs-per-block", type=int, default=4)
ap.add_argument("--out", default="real_dataset")
ap.add_argument("--base", default=bc.BASE, help="API base, e.g. https://mempool.space/testnet4/api")
ap.add_argument("--sleep", type=float, default=0.35, help="delay between calls (rate limits)")
a = ap.parse_args(); bc.BASE = a.base
os.makedirs(a.out, exist_ok=True); random.seed(1)

print("Fetching blocks ...")
h = bc.get_json("/blocks/tip/height"); raw = []
while len(raw) < a.blocks:
    batch = bc.get_json(f"/v1/blocks/{h}"); raw += batch; h = batch[-1]["height"] - 1; time.sleep(a.sleep)
raw = sorted(raw[:a.blocks], key=lambda b: b["height"])

blocks, txs = [], []
for i, b in enumerate(raw):
    tc = b["tx_count"]
    blocks.append(dict(block_height=b["height"], block_hash=b["id"], timestamp=bc.fmt_ts(b["timestamp"]),
        transaction_count=tc, block_size_bytes=b["size"], average_transaction_size_bytes=round(b["size"] / tc),
        total_fees_btc=round(b["extras"]["totalFees"] / bc.SAT, 8)))
    pages = max(1, (tc + 24) // 25)
    got = []
    for start in random.sample(range(pages), min(pages, 2)):
        got += bc.get_json(f"/block/{b['id']}/txs/{start * 25}"); time.sleep(a.sleep)
    parsed = [p for p in (bc.parse_block_tx(t) for t in got) if p]
    random.shuffle(parsed)
    for p in parsed[:a.txs_per_block]:
        p.update(block_height=b["height"], block_hash=b["id"], timestamp=bc.fmt_ts(b["timestamp"]),
                 transactions_per_block=tc, block_size_bytes=b["size"]); txs.append(p)
    print(f"  block {b['height']} ({i+1}/{len(raw)}) sampled {min(len(parsed), a.txs_per_block)} txs")

# anomaly detection
X = [bc.feat_vec(t) for t in txs]; det = bc.Detector(); det.fit_batch(X)
anoms = []
for t, v in zip(txs, X):
    s, lv, why = det.score(v); anoms.append(dict(txid=t["txid"], anomaly_score=s, anomaly_level=lv, reason=why, detected_at=bc.now_ts()))

# monitoring statistics (one record per block interval)
mon, prev = [], None
for b, raw_b in zip(blocks, raw):
    if prev:
        mins = max((raw_b["timestamp"] - prev) / 60, 0.1)
        n_anom = sum(1 for t, an in zip(txs, anoms) if t["block_height"] == b["block_height"] and an["anomaly_level"] != "NORMAL")
        mon.append(dict(timestamp=b["timestamp"], transactions_per_minute=round(b["transaction_count"] / mins),
            average_fee_btc=round(b["total_fees_btc"] / b["transaction_count"], 8),
            average_transaction_size_bytes=b["average_transaction_size_bytes"], active_blocks=1, anomaly_count=n_anom))
    prev = raw_b["timestamp"]

def write(name, rows, cols):
    with open(os.path.join(a.out, name), "w", newline="") as f:
        w = csv.writer(f); w.writerow(cols)
        for r in rows:
            w.writerow([f"{r[c]:.8f}".rstrip("0").rstrip(".") if isinstance(r[c], float) else r[c] for c in cols])
write("bitcoin_blocks.csv", blocks, list(blocks[0]))
tcols = ["txid","block_height","block_hash","timestamp","input_count","output_count","input_value_btc","output_value_btc",
         "fee_btc","fee_rate_sat_vbyte","transaction_size_bytes","transaction_weight","transactions_per_block",
         "block_size_bytes","fee_to_value_ratio","input_output_ratio"]
write("bitcoin_transactions.csv", txs, tcols)
write("anomaly_detection.csv", anoms, list(anoms[0]))
write("monitoring_statistics.csv", mon, list(mon[0]))
print(f"Done -> {a.out}/  blocks={len(blocks)} txs={len(txs)} anomalies={len(anoms)} monitoring={len(mon)}")
