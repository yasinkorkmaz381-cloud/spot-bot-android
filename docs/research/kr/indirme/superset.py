import json, glob
syms = set()
for f in glob.glob('/home/user/research-v8/cmc/hist/*.json') + ['/home/user/research-v8/cmc/latest.json']:
    for r in json.load(open(f))['data']: syms.add(r['symbol'].upper())
e = json.load(open('/home/user/research-v8/mexc/exchangeInfo.json'))['symbols']
out = sorted({s['symbol'] for s in e if s['quoteAsset'] == 'USDT' and s['baseAsset'].upper() in syms} | {'BTCUSDT'})
open('/home/user/research-v8/mexc/superset.txt', 'w').write('\n'.join(out) + '\n'); print(len(syms), len(out))
