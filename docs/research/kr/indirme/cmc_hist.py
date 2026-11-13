import json, os, sys, time, urllib.request, datetime as D
OUT = '/home/user/research-v8/cmc/hist'
H = {'X-CMC_PRO_API_KEY': 'proxy-injected', 'Accept': 'application/json'}
def get(url):
    for a in range(5):
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=60)
            return json.load(r)
        except Exception as e:
            print('retry', url, e, flush=True); time.sleep(5 * (a + 1))
    raise SystemExit('fail ' + url)
start = D.date(2025, 11, 14); end = D.date(2026, 11, 13)
d = start
while d <= end:
    f = f'{OUT}/{d}.json'
    if not os.path.exists(f):
        # snapshot at 00:00 UTC of day d (list known at start of that day)
        url = f'https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/historical?date={d}T00:00:00Z&start=1&limit=300&sort=cmc_rank&convert=USD&aux=platform,tags,cmc_rank,date_added'
        j = get(url)
        if j['status']['error_code'] != 0: print(d, j['status']); d += D.timedelta(days=1); continue
        rows = [{'id': c['id'], 'name': c['name'], 'symbol': c['symbol'], 'slug': c['slug'], 'rank': c['cmc_rank'], 'tags': c.get('tags') or [],
                 'platform': c.get('platform'), 'price': c['quote']['USD']['price'], 'mcap': c['quote']['USD']['market_cap'],
                 'last_updated': c.get('last_updated')} for c in j['data']]
        json.dump({'date': str(d), 'status': j['status'], 'data': rows}, open(f, 'w'))
        print(d, len(rows), flush=True); time.sleep(1.4)
    d += D.timedelta(days=1)
print('done')
