"""Shared helpers: mempool.space client, feature extraction, Isolation Forest detector."""
import json, math, time, urllib.request, urllib.error
from collections import deque
from datetime import datetime, timezone
import numpy as np
from sklearn.ensemble import IsolationForest

BASE = "https://mempool.space/api"          # mainnet. Testnet: https://mempool.space/testnet4/api
SAT = 1e8

def get_json(path, base=None, retries=4):
    url = (base or BASE) + path
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "btc-live-tracker/1.0"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            last = e
            time.sleep(2 * (attempt + 1) if e.code == 429 else 1)   # 429 = rate limited
        except Exception as e:
            last = e
            time.sleep(1 + attempt)
    raise RuntimeError(f"request failed: {url} ({last})")

def fmt_ts(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

def now_ts():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

def parse_block_tx(tx):
    """Full transaction (block/:hash/txs) -> flat feature dict. Returns None for coinbase/invalid."""
    vin = tx.get("vin", [])
    if not vin or vin[0].get("is_coinbase"):
        return None
    inp = sum((v.get("prevout") or {}).get("value", 0) for v in vin)
    out = sum(v.get("value", 0) for v in tx.get("vout", []))
    if inp <= 0 or out <= 0:
        return None
    weight = tx["weight"]; vsize = math.ceil(weight / 4); fee = tx.get("fee", inp - out)
    return dict(txid=tx["txid"], input_count=len(vin), output_count=len(tx["vout"]),
                input_value_btc=inp / SAT, output_value_btc=out / SAT, fee_btc=fee / SAT,
                fee_rate_sat_vbyte=round(fee / vsize, 2), transaction_size_bytes=tx["size"],
                transaction_weight=weight, vsize=vsize,
                fee_to_value_ratio=fee / out, input_output_ratio=inp / out)

def parse_mempool_tx(t):
    """mempool/recent item {txid, fee, vsize, value} -> flat dict."""
    if t["value"] <= 0 or t["vsize"] <= 0:
        return None
    return dict(txid=t["txid"], fee_btc=t["fee"] / SAT, vsize=t["vsize"], output_value_btc=t["value"] / SAT,
                fee_rate_sat_vbyte=t["fee"] / t["vsize"], fee_to_value_ratio=t["fee"] / t["value"])

FEATURE_NAMES = ["fee rate", "tx size", "tx value", "fee-to-value ratio"]
def feat_vec(r):
    size = r.get("vsize") or r.get("transaction_size_bytes")
    return [math.log10(r["fee_rate_sat_vbyte"] + 1), math.log10(size),
            math.log10(r["output_value_btc"] * SAT + 1), math.log10(r["fee_to_value_ratio"] * 1e4 + 1)]

class Detector:
    """Isolation Forest + percentile-based levels + z-score explanations."""
    def __init__(self, min_train=150, refit_every=400, maxbuf=5000):
        self.buf = deque(maxlen=maxbuf); self.min_train = min_train; self.refit_every = refit_every
        self.model = None; self.new = 0

    def fit_batch(self, X):
        X = np.array(X, float)
        self.mu, self.sd = X.mean(0), X.std(0) + 1e-9
        self.model = IsolationForest(n_estimators=200, random_state=42).fit(X)
        self.ref = np.sort(-self.model.score_samples(X)); self.new = 0

    def add(self, vec):
        self.buf.append(vec); self.new += 1
        if (self.model is None and len(self.buf) >= self.min_train) or (self.model is not None and self.new >= self.refit_every):
            self.fit_batch(self.buf)

    def score(self, vec):
        if self.model is None:
            return 0.0, "NORMAL", f"Model warming up ({len(self.buf)}/{self.min_train} samples)"
        raw = -self.model.score_samples([vec])[0]
        pct = float(np.searchsorted(self.ref, raw) / len(self.ref))
        level = ("CRITICAL" if pct >= .995 else "HIGH" if pct >= .98 else
                 "MEDIUM" if pct >= .95 else "LOW" if pct >= .90 else "NORMAL")
        z = (np.array(vec) - self.mu) / self.sd
        parts = [f"{'High' if z[i] > 0 else 'Low'} {FEATURE_NAMES[i]} (z={z[i]:+.1f})"
                 for i in np.argsort(-np.abs(z)) if abs(z[i]) > 2.5][:3]
        if level == "NORMAL": reason = "All features within normal range"
        else: reason = ", ".join(parts) if parts else "Unusual combination of features"
        return round(pct, 4), level, reason
