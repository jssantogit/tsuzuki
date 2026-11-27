// Execute against the actual patched adapter extracted from the unsigned ZIP.
// The sibling http.js is a deterministic mock; no live service is contacted.
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
const adapter = await import(pathToFileURL(process.argv[2]).href);
const work = "00000000-0000-0000-0000-000000000042";
const author = "00000000-0000-0000-0000-000000000001";
const artist = "00000000-0000-0000-0000-000000000002";
const a = "aaaaaaaa-0000-0000-0000-000000000001";
const b = "bbbbbbbb-0000-0000-0000-000000000002";
const c = "cccccccc-0000-0000-0000-000000000003";
const manga = {type:"manga",id:work,attributes:{title:{en:"Work"},altTitles:[],originalLanguage:"ja"}};
const chapter = (id,number,externalUrl=null) => ({
  id,type:"chapter",attributes:{chapter:String(number),translatedLanguage:"en",externalUrl},
  relationships:[{type:"manga",id:work}],
});
let staffIncludes = [];
globalThis.mangaDexFixture = async (path, params=[]) => {
  if (path === "/manga") return {data:[manga],total:1,offset:0};
  if (path === "/manga/"+work) {
    staffIncludes = params.filter(([k])=>k==="includes[]").map(([,v])=>v);
    return {data:{...manga,relationships:[
      {type:"author",id:author,attributes:{name:"Original Writer"}},
      {type:"artist",id:artist,attributes:{name:"Manga Illustrator"}},
      {type:"artist",id:"invalid",attributes:{name:"Unverified"}},
    ]}};
  }
  if (path === "/chapter") {
    const offset = Number(params.find(([k])=>k==="offset")?.[1]??0);
    if (params.some(([k])=>k==="order[chapter]")) {
      return offset===0
        ? {data:[chapter(a,1,"https://publisher.example/chapter"),chapter(b,1)],total:3,offset:0}
        : {data:[chapter(c,2,"")],total:3,offset:2};
    }
    return {data:[chapter(a,1,"https://publisher.example/chapter"),chapter(b,1,"")],total:2,offset:0};
  }
  throw Error("Unexpected fixture request");
};
const result = await adapter.lookup({titles:["Work"],requestedLanguage:"en",editorialEvidenceVersion:1,coverageEvidenceVersion:1});
assert.deepEqual(staffIncludes,["author","artist"]);
assert.deepEqual(result.items[0].authors,["Original Writer","Manga Illustrator"]);
assert.equal(result.items[0].coverageOutcome,"OBSERVED");
assert.equal(result.items[0].coverageObservedCount,1);
const binding = {externalWorkId:"mangadex:"+work,facetId:"en"};
const first = await adapter.chapters({binding});
assert.equal(first.nextCursor,"2");
assert.deepEqual(first.items.map(r=>r.providerChapterId),["mangadex:"+b]);
const last = await adapter.chapters({binding,cursor:first.nextCursor});
assert.equal(last.nextCursor,null);
assert.deepEqual(last.items.map(r=>r.providerChapterId),["mangadex:"+c]);
globalThis.mangaDexFixture=async()=>({data:[chapter(a,1),chapter(a.toUpperCase(),2)],total:2,offset:0});
await assert.rejects(adapter.chapters({binding}),/duplicate chapter identity/);
globalThis.mangaDexFixture=async()=>({data:[chapter(a,1,"https://publisher.example/chapter")],total:1,offset:0});
const externalOnly=await adapter.chapters({binding});
assert.deepEqual(externalOnly.items,[]);
assert.equal(externalOnly.nextCursor,null);
console.log("MANGADEX_READING_CONTRACT|authorArtist=PASS|externalDelivery=PASS|duplicateIds=PASS|rawCursor=PASS");
