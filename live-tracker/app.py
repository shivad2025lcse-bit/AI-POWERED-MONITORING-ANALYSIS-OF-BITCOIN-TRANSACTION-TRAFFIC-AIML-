"""Live Bitcoin transaction-traffic tracker.
Run:   python app.py              (real data from mempool.space)
       python app.py --demo       (simulated data, works offline)
Open:  http://localhost:5000
"""
import argparse, csv, io, math, random, sqlite3, threading, time
from flask import Flask, jsonify, render_template, request, Response
import btc_common as bc

ap = argparse.ArgumentParser()
ap.add_argument("--demo", action="store_true"); ap.add_argument("--port", type=int, default=5000)
ap.add_argument("--poll", type=float, default=6, help="seconds between mempool polls")
ap.add_argument("--db", default="tracker.db")
args, _ = ap.parse_known_args()

# ---------------- data sources ----------------
class LiveSource:
    def tip(self): return bc.get_json("/blocks/tip/height")
    def blocks_from(self, h): return bc.get_json(f"/v1/blocks/{h}")
    def recent(self): return bc.get_json("/mempool/recent")
    def mempool_count(self): return bc.get_json("/mempool")["count"]
    def block_txs(self, bid, start): return bc.get_json(f"/block/{bid}/txs/{start}")

class DemoSource:
    def __init__(s): s.h = 850000; s.t0 = time.time(); s.blocks = {}
    def tip(s): return 850000 + int((time.time() - s.t0) // 45)          # a "block" every 45 s
    def _blk(s, h):
        r = random.Random(h); n = r.randint(2000, 3800)
        return dict(height=h, id=f"{h:064x}", timestamp=int(s.t0 + (h - 850000) * 45 - 600), tx_count=n,
                    size=n * r.randint(380, 520), extras=dict(totalFees=int(r.uniform(.12, .65) * 1e8)))
    def blocks_from(s, h): return [s._blk(x) for x in range(h, max(h - 15, 849990), -1)]
    def _tx(s):
        rate = random.lognormvariate(2.6, .5); v = int(1e8 * random.lognormvariate(-2.3, 1.1)); vs = int(random.lognormvariate(5.6, .5))
        if random.random() < .04: rate *= random.uniform(15, 60)                       # inject outliers
        if random.random() < .02: vs *= random.randint(20, 80); v *= random.randint(20, 200)
        return dict(txid=f"{random.getrandbits(256):064x}", fee=int(rate * vs), vsize=vs, value=max(v, 1000))
    def recent(s): return [s._tx() for _ in range(10)]
    def mempool_count(s): return int(random.gauss(30000, 2500))
    def block_txs(s, bid, start):
        out = []
        for _ in range(25):
            t = s._tx(); ni = random.randint(1, 4)
            out.append(dict(txid=t["txid"], size=t["vsize"], weight=t["vsize"] * 4, fee=t["fee"],
                vin=[dict(prevout=dict(value=(t["value"] + t["fee"]) // ni)) for _ in range(ni)],
                vout=[dict(value=t["value"] // 2)] * 2))
        return out

src = DemoSource() if args.demo else LiveSource()

# ---------------- storage ----------------
lock = threading.RLock(); det = bc.Detector(); state = dict(last_height=0, status="starting", last_error="")
db = sqlite3.connect(args.db, check_same_thread=False); db.row_factory = sqlite3.Row
db.executescript("""
CREATE TABLE IF NOT EXISTS blocks(block_height INTEGER PRIMARY KEY, block_hash TEXT, timestamp TEXT, transaction_count INT,
  block_size_bytes INT, average_transaction_size_bytes INT, total_fees_btc REAL);
CREATE TABLE IF NOT EXISTS live_tx(txid TEXT PRIMARY KEY, seen_at TEXT, fee_btc REAL, vsize INT, value_btc REAL,
  fee_rate_sat_vbyte REAL, fee_to_value_ratio REAL, anomaly_score REAL, anomaly_level TEXT, reason TEXT);
CREATE TABLE IF NOT EXISTS monitoring(timestamp TEXT PRIMARY KEY, transactions_per_minute INT, average_fee_btc REAL,
  average_transaction_size_bytes INT, active_blocks INT, anomaly_count INT, mempool_tx_count INT);
""")

def q(sql, p=()):
    with lock: return [dict(r) for r in db.execute(sql, p).fetchall()]

def add_block(b):
    with lock:
        db.execute("INSERT OR IGNORE INTO blocks VALUES(?,?,?,?,?,?,?)", (b["height"], b["id"], bc.fmt_ts(b["timestamp"]),
            b["tx_count"], b["size"], round(b["size"] / max(b["tx_count"], 1)), round(b["extras"]["totalFees"] / bc.SAT, 8)))
        db.commit()

def process(t):
    r = bc.parse_mempool_tx(t)
    if not r: return
    v = bc.feat_vec(r)
    with lock:
        score, level, why = det.score(v); det.add(v)
        db.execute("INSERT OR IGNORE INTO live_tx VALUES(?,?,?,?,?,?,?,?,?,?)", (r["txid"], bc.now_ts(), r["fee_btc"], r["vsize"],
            r["output_value_btc"], round(r["fee_rate_sat_vbyte"], 2), r["fee_to_value_ratio"], score, level, why))
        db.commit()

def bootstrap():
    tip = src.tip(); state["last_height"] = tip
    for b in src.blocks_from(tip): add_block(b)
    for b in src.blocks_from(tip)[:2]:                       # train the model on real confirmed txs first
        for start in (0, 25, 50, 75):
            for tx in src.block_txs(b["id"], start):
                p = bc.parse_block_tx(tx)
                if p:
                    with lock: det.add(bc.feat_vec(p))
            time.sleep(.3)

def worker():
    try: bootstrap(); state["status"] = "running"
    except Exception as e: state.update(status="error", last_error=str(e)); print("bootstrap failed:", e)
    last_block = last_mon = 0
    while True:
        try:
            for t in src.recent(): process(t)
            if time.time() - last_block > 20:
                last_block = time.time(); tip = src.tip()
                if tip > state["last_height"]:
                    for b in src.blocks_from(tip):
                        if b["height"] > state["last_height"]: add_block(b)
                    state["last_height"] = tip
            if time.time() - last_mon > 60:
                last_mon = time.time(); write_monitoring()
            state.update(status="running", last_error="")
        except Exception as e:
            state.update(status="error", last_error=str(e)); print("poll error:", e)
        time.sleep(args.poll)

def write_monitoring():
    cutoff = bc.fmt_ts(time.time() - 60); hour = bc.fmt_ts(time.time() - 3600)
    rows = q("SELECT fee_btc, vsize, anomaly_level FROM live_tx WHERE seen_at>=?", (cutoff,))
    n = len(rows); mp = src.mempool_count(); blk = q("SELECT COUNT(*) c FROM blocks WHERE timestamp>=?", (hour,))[0]["c"]
    with lock:
        db.execute("INSERT OR REPLACE INTO monitoring VALUES(?,?,?,?,?,?,?)", (bc.now_ts(), n,
            round(sum(r["fee_btc"] for r in rows) / n, 8) if n else 0, round(sum(r["vsize"] for r in rows) / n) if n else 0,
            blk, sum(1 for r in rows if r["anomaly_level"] != "NORMAL"), mp))
        db.commit()

# ---------------- web ----------------
app = Flask(__name__)
@app.route("/")
def index(): return render_template("index.html", demo=args.demo)

@app.route("/api/summary")
def summary():
    hour = bc.fmt_ts(time.time() - 3600)
    m = q("SELECT * FROM monitoring ORDER BY timestamp DESC LIMIT 1"); b = q("SELECT * FROM blocks ORDER BY block_height DESC LIMIT 1")
    lv = q("SELECT anomaly_level l, COUNT(*) c FROM live_tx WHERE seen_at>=? GROUP BY anomaly_level", (hour,))
    return jsonify(status=state["status"], error=state["last_error"], demo=args.demo, latest_block=b[0] if b else None,
        latest_monitoring=m[0] if m else None, levels={r["l"]: r["c"] for r in lv},
        total_tx=q("SELECT COUNT(*) c FROM live_tx")[0]["c"], model_ready=det.model is not None)

@app.route("/api/monitoring")
def monitoring(): return jsonify(list(reversed(q("SELECT * FROM monitoring ORDER BY timestamp DESC LIMIT ?", (int(request.args.get("limit", 60)),)))))
@app.route("/api/blocks")
def blocks(): return jsonify(q("SELECT * FROM blocks ORDER BY block_height DESC LIMIT 15"))
@app.route("/api/transactions")
def txs(): return jsonify(q("SELECT * FROM live_tx ORDER BY seen_at DESC LIMIT 25"))
@app.route("/api/anomalies")
def anomalies(): return jsonify(q("SELECT * FROM live_tx WHERE anomaly_level!='NORMAL' ORDER BY seen_at DESC LIMIT 25"))

EXPORTS = {"blocks": "SELECT * FROM blocks ORDER BY block_height", "monitoring_statistics": "SELECT * FROM monitoring ORDER BY timestamp",
    "anomaly_detection": "SELECT txid, anomaly_score, anomaly_level, reason, seen_at AS detected_at FROM live_tx ORDER BY seen_at",
    "live_transactions": "SELECT * FROM live_tx ORDER BY seen_at"}
@app.route("/export/<name>.csv")
def export(name):
    if name not in EXPORTS: return "not found", 404
    rows = q(EXPORTS[name]); out = io.StringIO()
    if rows:
        w = csv.DictWriter(out, fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    return Response(out.getvalue(), mimetype="text/csv", headers={"Content-Disposition": f"attachment; filename={name}.csv"})

if __name__ == "__main__":
    threading.Thread(target=worker, daemon=True).start()
    print(f"Dashboard: http://localhost:{args.port}   ({'DEMO' if args.demo else 'LIVE mempool.space'})")
    app.run(host="0.0.0.0", port=args.port, threaded=True)
