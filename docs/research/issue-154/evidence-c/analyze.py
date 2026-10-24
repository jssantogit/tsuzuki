"""Offline checks of published observations. Does not execute Tsuzuki code."""
import collections
import json
import pathlib
import statistics

ROOT = pathlib.Path(__file__).resolve().parent


def analyze():
    corpus = json.loads((ROOT/'corpus.json').read_text(encoding='utf8'))
    inventory = json.loads((ROOT/'inventory-results.json').read_text(encoding='utf8'))
    followups = json.loads((ROOT/'followups.json').read_text(encoding='utf8'))
    receipts = [json.loads(x) for x in (ROOT/'requests.jsonl').read_text(encoding='utf8').splitlines()]
    assert len({(x['work'],x['source']) for x in inventory})==len(inventory)
    keys = {x['key']:x for x in corpus}
    for x in inventory:
        assert str(keys[x['work']]['ids'][x['source']])==str(x['externalId'])
        assert x['returned']==len(x['rows']) and x['receipt']['status']==200
        assert x['receipt'] in receipts
        if x['source']=='mu':
            assert all(str(r['seriesId'])==str(x['externalId']) for r in x['rows'])
    # These assertions constrain the observations, not a production reconciler.
    bgm = [x for x in inventory if x['source']=='bgm']
    assert len(bgm)==12 and all(x['returned']==0 for x in bgm)
    tails = [x for x in followups if x['source']=='kitsu' and x.get('totalReportedByEndpoint')==5000]
    assert len(tails)==2 and all([r['number'] for r in x['rows']]==list(range(4981,5001)) for x in tails)
    totals = {}
    for source in ['kitsu','mu','bgm','md']:
        records = [x for x in receipts if x['source']==source]
        sample = [x for x in inventory if x['source']==source]
        ok = [x for x in records if x['status']==200]
        totals[source] = {'requests':len(records), 'statuses':dict(collections.Counter(str(x['status']) for x in records)),
            'responseBytes':sum(x['bytes'] for x in records),
            'medianHttpMsSuccessful':statistics.median(x['elapsedMs'] for x in ok),
            'sampledScopes':len(sample), 'nonemptyScopes':sum(bool(x['rows']) for x in sample),
            'firstPageRows':sum(x['returned'] for x in sample),
            'endpointTotalSumNotChapterCount':sum(x['totalReportedByEndpoint'] or 0 for x in sample)}
    return {'requests':len(receipts),'firstObservation':min(x['observedAt'] for x in receipts),
        'lastObservation':max(x['observedAt'] for x in receipts), 'sources':totals,
        'inventorySamples':len(inventory),'followupSamples':len(followups),
        'checks':'PASS', 'warning':'Nonrandom corpus; HTTP and endpoint coverage are not editorial completeness or uptime.'}


if __name__=='__main__':
    print(json.dumps(analyze(),ensure_ascii=False,indent=2))
