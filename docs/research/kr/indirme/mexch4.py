import json, os, sys, time, gzip, urllib.request, threading, queue
OUT = '/home/user/research-v8/mexc/h4'; os.makedirs(OUT, exist_ok=True)
START = 1748736000000
END = int(sys.argv[2]) if len(sys.argv) > 2 else 1794614400000    # 2026-11-13 00:00 UTC (exclusive)
STEP = 500 * 14400000
syms = [l.strip() for l in open(sys.argv[1]) if l.strip()]
q = queue.Queue(); [q.put(s) for s in syms]
lock = threading.Lock(); done = [0]
def get(url):
    for a in range(8):
        try:
            return json.load(urllib.request.urlopen(url, timeout=30))
        except Exception as e:
            if '400' in str(e): return None
            time.sleep(2 * (a + 1))
    raise RuntimeError(url)
def work():
    while True:
        try: s = q.get_nowait()
        except queue.Empty: return
        f = f'{OUT}/{s}.csv.gz'
        if os.path.exists(f): continue
        rows = {}; t = START; err = None
        try:
            while t < END:
                d = get(f'https://api.mexc.com/api/v3/klines?symbol={s}&interval=4h&startTime={t}&endTime={min(t+STEP, END)-1}&limit=1000')
                if d is None: err = 'invalid'; break
                for k in d:
                    if START <= k[0] < END: rows[k[0]] = (k[0], k[1], k[2], k[3], k[4], k[5], k[7])
                t += STEP; time.sleep(0.05)
        except Exception as e: err = str(e)
        if err == 'invalid': 
            with lock: print('INVALID', s, flush=True)
            continue
        if err: 
            with lock: print('ERR', s, err, flush=True)
            continue
        with gzip.open(f + '.tmp', 'wt') as g:
            for k in sorted(rows): g.write(','.join(map(str, rows[k])) + '\n')
        os.rename(f + '.tmp', f)
        with lock:
            done[0] += 1; print(done[0], s, len(rows), flush=True)
ts = [threading.Thread(target=work) for _ in range(6)]; [t.start() for t in ts]; [t.join() for t in ts]; print('done')
