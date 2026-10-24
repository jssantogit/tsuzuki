"""Explicit opt-in replay of inventory/followup reads, with an external cache."""
import argparse
import json
import pathlib
import probe

p=argparse.ArgumentParser()
p.add_argument('--live',action='store_true')
p.add_argument('--cache-dir',type=pathlib.Path,required=True)
args=p.parse_args()
if not args.live:
    p.error('Network execution requires explicit --live; use analyze.py for offline verification')
root=pathlib.Path(__file__).resolve().parent
cache=args.cache_dir.resolve()
if root==cache or root in cache.parents:
    p.error('Cache must be outside the published evidence directory')
cache.mkdir(parents=True,exist_ok=True)
probe.ROOT=cache
probe.LIVE=True
samples=json.loads((root/'inventory-results.json').read_text(encoding='utf8'))+json.loads((root/'followups.json').read_text(encoding='utf8'))
seen=set()
for sample in samples:
    receipt=sample['receipt']
    key=(receipt['method'],receipt['url'],json.dumps(receipt['body'],sort_keys=True))
    if key in seen: continue
    seen.add(key)
    result=probe.request(receipt['source'],receipt['url'],receipt['body'])
    print(json.dumps({'work':sample['work'],'source':sample['source'],
        'status':result['receipt']['status'],
        'sameRawHash':result['receipt']['responseSha256']==receipt['responseSha256']}))
