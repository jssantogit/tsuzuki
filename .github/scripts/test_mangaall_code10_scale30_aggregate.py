#!/usr/bin/env python3
"""Thirty-work scaled Host signed-source aggregation: OFFLINE only."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mangaall_code10_canonical_smoke_plan import parse_plan, ACK
from mangaall_code10_scale30_aggregate import summarize

ROOT = Path(__file__).resolve().parents[2]
WORKS = "\n".join(f"manga :: Synthetic Three Source Work {i:02d}" for i in range(1,31))


def summaries():
    cases = parse_plan(WORKS,"scale",ACK)
    results = []
    for shard in range(3):
        result = {
            "schemaVersion":1,"runScope":"CODE10_SIGNED_V1_CAPTURE_TO_CANONICAL_SQLITE",
            "providerCode":10,"signedIndexSequence":36,
            "catalogIndependent":True,"allThreeSourcesInOneMangaAllLookup":True,
            "canonicalGrantFromV2":False,"readerPixels":"NOT_TESTED_REAL_CDN",
            "shardIndex":shard,"shardCount":3,"fullVectorWorks":30,
            "shardSource":"ORIGINAL_SIGNED_CODE10_PROVIDER_CAPTURE",
            "works":10,
            "results":[{
                "position":x["position"],"caseId":x["caseId"],
                "outcome":"INCONCLUSIVE_SOURCE","readerPixels":"NOT_TESTED_REAL_CDN",
                "sourceIncomplete":True,"sqliteConfirmed":0,
                "sqliteProvisional":0,"sqliteBindings":0,
                "editorialObservation":"NO_CHAPTER_BINDING_OBSERVED",
            } for i,x in enumerate(cases) if i%3==shard]
        }
        results.append(result)
    return results


class TestScale30Aggregator(unittest.TestCase):
    def test_shards_merge_in_input_order_and_never_claim_reader_pixels(self):
        shuffled=summaries()[::-1]
        result=summarize(WORKS,ACK,shuffled)
        self.assertEqual(result["totalWorks"],30)
        self.assertEqual(result["shardCount"],3)
        self.assertEqual(result["outcomes"],{"INCONCLUSIVE_SOURCE":30})
        self.assertFalse(result["allThirtyFunctional"])
        self.assertEqual(result["readerPixels"],"NOT_TESTED_REAL_CDN")
        self.assertFalse(result["automaticEditorialBindingFromPartialV1"])
        self.assertFalse(result["releaseSigningAuthorized"])
        self.assertEqual([x["position"] for x in result["results"]],list(range(1,31)))
        self.assertEqual(len({x["caseId"] for x in result["results"]}),30)
        self.assertNotIn("Synthetic Three Source Work",json.dumps(result))
        self.assertNotIn("http",json.dumps(result))

    def test_missing_duplicate_foreign_or_forged_shard_fails(self):
        cases=summaries()
        bads=[]
        bads.append(cases[:2])
        x=copy.deepcopy(cases);x[1]["shardIndex"]=0;bads.append(x)
        x=copy.deepcopy(cases);x[2]["results"][0]["caseId"]="user-111111111111";bads.append(x)
        x=copy.deepcopy(cases);x[0]["results"][0]["position"]=2;bads.append(x)
        x=copy.deepcopy(cases);x[0]["results"].pop();bads.append(x)
        x=copy.deepcopy(cases);x[0]["results"][0]["readerPixels"]="REAL_CDN_VISIBLE";bads.append(x)
        x=copy.deepcopy(cases);x[0]["results"][0]["sqliteConfirmed"]=999999;bads.append(x)
        x=copy.deepcopy(cases);x[0]["results"][0]["outcome"]="FAKE_FUNCTIONAL_PASS";bads.append(x)
        x=copy.deepcopy(cases);x[1]["allThreeSourcesInOneMangaAllLookup"]=False;bads.append(x)
        x=copy.deepcopy(cases);x[2]["signedIndexSequence"]=35;bads.append(x)
        for idx,bad in enumerate(bads):
            with self.subTest(idx=idx),self.assertRaises(ValueError):
                summarize(WORKS,ACK,bad)

    def test_cli_original_three_summary_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder)
            for idx,part in enumerate(summaries()):
                (folder / f"{idx}.json").write_text(json.dumps(part),encoding="utf-8")
            dest=folder/"summary.json"
            run=subprocess.run([
                sys.executable,str(ROOT/".github/scripts/mangaall_code10_scale30_aggregate.py"),
                *[str(folder/f"{idx}.json") for idx in range(3)],str(dest),
            ],cwd=ROOT,text=True,capture_output=True,timeout=8,
                env={**os.environ,"MANGAALL_WORKS":WORKS,"MANGAALL_SMOKE_ACK":ACK})
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("works=30|shards=3",run.stdout)
            saved=json.loads(dest.read_text())
            self.assertEqual(saved["outcomes"],{"INCONCLUSIVE_SOURCE":30})
            self.assertFalse(saved["allThirtyFunctional"])


if __name__=="__main__":
    unittest.main()
