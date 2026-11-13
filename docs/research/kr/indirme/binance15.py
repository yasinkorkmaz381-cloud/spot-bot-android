import os, sys, time, urllib.request, zipfile, io, gzip, threading, queue
OUT = '/home/user/research-v8/binance/m15'; os.makedirs(OUT, exist_ok=True)
MONTHS = [f'{y}-{m:02d}' for y in (2023, 2024, 2025) for m in range(1, 13) if (y, m) >= (2023, 11) and (y, m) <= (2025, 10)]
syms = [l.strip() for l in open(sys.argv[1]) if l.strip()]
q = queue.Queue(); [q.put(s) for s in syms]; lock = threading.Lock(); n = [0]
def fetch(url):
    for a in range(6):
        try: return urllib.request.urlopen(url, timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code == 404: return None
            time.sleep(3 * (a + 1))
        except Exception: time.sleep(3 * (a + 1))
    raise RuntimeError(url)
def work():
    while True:
        try: s = q.get_nowait()
        except queue.Empty: return
        f = f'{OUT}/{s}.csv.gz'
        if os.path.exists(f): continue
        rows = []; got = 0
        for m in MONTHS:
            b = fetch(f'https://data.binance.vision/data/spot/monthly/klines/{s}/15m/{s}-15m-{m}.zip')
            if b is None: continue
            got += 1
            z = zipfile.ZipFile(io.BytesIO(b))
            for line in z.read(z.namelist()[0]).decode().splitlines():
                p = line.split(',')
                if not p[0].isdigit(): continue
                t = int(p[0]);
                if t > 10**14: t //= 1000  # microseconds (2025+ files)
                rows.append(f'{t},{p[1]},{p[2]},{p[3]},{p[4]},{p[5]},{p[7]}')
        with lock: n[0] += 1; print(n[0], s, got, len(rows), flush=True)
        if not rows: continue
        with gzip.open(f + '.tmp', 'wt') as g: g.write('\n'.join(rows) + '\n')
        os.rename(f + '.tmp', f)
ts = [threading.Thread(target=work) for _ in range(6)]; [t.start() for t in ts]; [t.join() for t in ts]; print('done')
