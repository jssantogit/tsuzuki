#!/usr/bin/env python3
"""Fresh single-owner catalog attestations for all frozen works, no fallback."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import (
    APP, DIGEST, RUNNER, TEST, adb, case_id, classify, locked_works,
)

PLAN=Path(".github/mangaall-frozen30-catalog-plan.json")
OUT=Path(".github/results/mangaall-frozen30-catalog-plan")
SCOPES=("mangaupdates","shikimori")


def plan():
    raw=PLAN.read_bytes()
    doc=json.loads(raw)
    rows=doc["works"]
    assert doc["schemaVersion"]==1 and doc["scope"]=="RESEARCH_EXPLICIT_OWNERS_NO_FALLBACK"
    assert doc["corpusSha256"]==DIGEST and [r["index"] for r in rows]==list(range(1,31))
    for row in rows:
        assert set(row)<= {"index","catalog","searchTitle"}
        assert row["catalog"] in SCOPES
        hint=row.get("searchTitle")
        assert hint is None or (row["catalog"]=="mangaupdates" and isinstance(hint,str) and 0<len(hint)<=256)
    return rows,hashlib.sha256(raw).hexdigest()


def shard_run(works, rows, plan_digest, shard):
    selected=rows[shard*10:(shard+1)*10]
    assert adb("shell","getprop","ro.kernel.qemu").stdout.strip()=="1"
    app=[p for p in Path("app/build/outputs/apk/debug").rglob("*.apk") if p.name=="app-debug.apk" or "universal" in p.name]
    tests=list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    assert len(app)==len(tests)==1
    results={}
    all_processes=True
    invalid=0
    try:
        adb("install","-r",str(app[0]))
        adb("install","-r",str(tests[0]))
        adb("shell","cmd","connectivity","airplane-mode","disable")
        for catalog in SCOPES:
            group=[r for r in selected if r["catalog"]==catalog]
            if not group:
                continue
            by_title={works[r["index"]-1]:r for r in group}
            hints={title:r["searchTitle"] for title,r in by_title.items() if "searchTitle" in r}
            payload=base64.b64encode("\n".join("manhwa::"+t for t in by_title).encode()).decode()
            args=["shell","am","instrument","-w","-r","-e","mangaAllLiveCatalogOptIn","true",
                  "-e","mangaAllLiveCatalogSource",catalog,"-e","worksBase64",payload,
                  "-e","shardIndex","0","-e","shardCount","1","-e","class",TEST]
            if hints:
                args.extend(["-e","catalogSearchHintsBase64",base64.b64encode(json.dumps(hints,ensure_ascii=False).encode()).decode()])
            args.append(RUNNER)
            try:
                run=adb(*args,timeout=950,check=False)
                raw=run.stdout
                all_processes &= run.returncode==0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
            except subprocess.SubprocessError:
                all_processes=False
                continue
            for match in re.finditer(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)",raw):
                try:
                    record=json.loads(base64.b64decode(match.group(1),validate=True))
                    title=record.get("query")
                    row=by_title.get(title)
                    if (row is None or record.get("caseId")!=case_id(title) or row["index"] in results
                            or record.get("retrievalHintUsed") is not (title in hints)):
                        invalid+=1
                        continue
                    status,identity=classify(record,catalog)
                    results[row["index"]]=dict(index=row["index"],catalog=catalog,status=status,identityDigest=identity)
                except (TypeError,ValueError,AttributeError):
                    invalid+=1
            adb("shell","am","force-stop",APP,check=False)
    finally:
        adb("shell","am","force-stop",APP,check=False)
        adb("shell","pm","clear",APP,check=False)
    reports=[results.get(row["index"],dict(index=row["index"],catalog=row["catalog"],status="NOT_OBSERVED",identityDigest=None)) for row in selected]
    ok=all_processes and invalid==0 and all(r["status"]=="CATALOG_IDENTITY_ATTESTED" for r in reports) and len({r["identityDigest"] for r in reports})==10
    out=OUT/f"shard-{shard}"
    out.mkdir(parents=True,exist_ok=True)
    (out/"verdict.json").write_text(json.dumps(dict(schemaVersion=1,scope="EXPLICIT_OWNER_PLAN",corpusSha256=DIGEST,
        planSha256=plan_digest,shard=shard,processesPassed=all_processes,invalidAttestations=invalid,outcome="PASS" if ok else "RED",works=reports),indent=2)+"\n")
    for row in reports:
        print(f"CATALOG_PLAN|index={row['index']}|catalog={row['catalog']}|outcome={row['status']}")
    return ok


def aggregate(rows, plan_digest):
    actual=[]
    valid=True
    for shard in range(3):
        p=OUT/f"shard-{shard}"/"verdict.json"
        if not p.is_file():
            valid=False
            continue
        doc=json.loads(p.read_text())
        found=doc.get("works",[])
        valid &= (doc.get("schemaVersion")==1 and doc.get("scope")=="EXPLICIT_OWNER_PLAN"
                  and doc.get("corpusSha256")==DIGEST and doc.get("planSha256")==plan_digest
                  and doc.get("shard")==shard and doc.get("outcome")=="PASS"
                  and doc.get("processesPassed") is True and doc.get("invalidAttestations")==0
                  and [(r.get("index"),r.get("catalog")) for r in found]==[(r["index"],r["catalog"]) for r in rows[shard*10:(shard+1)*10]])
        actual.extend(found)
    accepted=[r for r in actual if r.get("status")=="CATALOG_IDENTITY_ATTESTED" and isinstance(r.get("identityDigest"),str) and re.fullmatch(r"[0-9a-f]{64}",r["identityDigest"])]
    unique=len({r["identityDigest"] for r in accepted})
    ok=valid and len(actual)==len(accepted)==unique==30
    print(f"CATALOG_PLAN|outcome={'PASS' if ok else 'RED'}|attested={len(accepted)}|ownerScopedIdentities={unique}|expected=30|crosswalk=NOT_ASSERTED|canonical=NOT_TESTED")
    return ok


def main():
    parser=argparse.ArgumentParser()
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--shard",type=int,choices=range(3))
    group.add_argument("--aggregate",action="store_true")
    group.add_argument("--check-plan",action="store_true")
    args=parser.parse_args()
    works=locked_works()
    rows,digest=plan()
    if args.check_plan:
        print("CATALOG_PLAN|scope=EXPLICIT_OWNER_PLAN|works=30|fallback=NO")
        return True
    return aggregate(rows,digest) if args.aggregate else shard_run(works,rows,digest,args.shard)


if __name__=="__main__":
    raise SystemExit(0 if main() else 1)
