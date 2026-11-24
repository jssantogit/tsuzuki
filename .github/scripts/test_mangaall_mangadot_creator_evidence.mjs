import assert from "node:assert/strict";
import { extractMangaDotCreators, observeMangaDotCreators, mangaDotExactTitle } from "./mangaall_mangadot_creator_evidence.mjs";

const url = "https://mangadot.net/manga/42";
const series = { "@type": "ComicSeries", name: "Example’s Work", url,
  author: { "@type": "Person", name: "Example Creator" } };
const raw = node => JSON.stringify({ "@context": "https://schema.org", "@graph": [node] });
const parse = node => extractMangaDotCreators(raw(node), "42", "Example's Work");
assert.deepEqual(parse(series), ["Example Creator"]);
assert.deepEqual(parse({...series, author:[series.author, series.author]}), ["Example Creator"]);
assert.equal(parse({...series, url:url+"0"}), null);
assert.equal(parse({...series, "@id":"https://other.invalid/manga/42"}), null);
assert.deepEqual(parse({...series, "@id":url+"#comicseries"}), ["Example Creator"]);
assert.deepEqual(parse({...series, "@id":url+"/#comicseries"}), ["Example Creator"]);
assert.equal(parse({...series, "@id":url+"0#comicseries"}), null);
assert.equal(parse({...series, "@id":url+"?edition=2#comicseries"}), null);
assert.equal(parse({...series, "@id":url+"#../43"}), null);
assert.equal(parse({...series, url:url+"0", "@id":url+"#comicseries"}), null);
assert.equal(parse({...series, "@id":"#comicseries"}), null);
assert.equal(parse({...series, name:"Unrelated"}), null);
assert.deepEqual(parse({...series, name:"Original Work", alternateName:[series.name]}), ["Example Creator"]);
assert.equal(parse({...series, name:"Unrelated", alternateName:["Other"]}), null);
assert.equal(parse({...series, name:"Original", alternateName:series.name, url:url+"0"}), null);
assert.deepEqual(parse({...series, "@type":["CreativeWork","ComicSeries"],
  author:{"@type":["Person"],name:"Kim Yong-Hwan",alternateName:"김용환"}}),
  ["Kim Yong-Hwan","김용환"]);
assert.equal(parse({...series,author:{"@type":["Person","Organization"],name:"Company"}}), null);
assert.equal(parse({...series,author:{...series.author,alternateName:"x".repeat(129)}}), null);
assert.equal(parse({...series, "@type":"WebPage"}), null);
assert.equal(parse({...series, author:{ "@type":"Organization", name:"Publisher"}}), null);
assert.equal(parse({...series, author: { "@type":"Person", name:" "}}), null);
assert.equal(parse({...series, author:"Unverified author"}), null);
assert.equal(extractMangaDotCreators(JSON.stringify([series, series]), "42", series.name), null);
assert.equal(extractMangaDotCreators(raw(series)+"x".repeat(64*1024), "42", series.name), null);
assert.equal(extractMangaDotCreators(raw(series), "../42", series.name), null);
assert.equal(extractMangaDotCreators("{malformed", "42", series.name), null);
assert.equal(mangaDotExactTitle({title:series.name}, "Example's Work"), true);
assert.equal(mangaDotExactTitle({title:series.name+" (Novel)"}, series.name), false);

let calls = 0;
const host = { http: {getResource: async requested => {
  assert.equal(requested,url); calls++; return "res:opaque"; }},
  dom: {selectText: async (handle,selector) => {
    assert.equal(handle,"res:opaque"); assert.equal(selector,"script[type=application/ld+json]");
    return raw(series);
  }}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name}, host),
  {detailOutcome:"VERIFIED_NO_ALIASES", authors:["Example Creator"]});
assert.equal(calls,1);
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadex:42",title:series.name}, host),
  {detailOutcome:"IDENTITY_MISMATCH"});
assert.equal(calls,1);
const broken = {http:{getResource:async()=>{throw Error("unavailable");}}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name}, broken),
  {detailOutcome:"DETAIL_FAILED"});
const badDom = {http:host.http, dom:{selectText:async()=>{throw Error("bad structure");}}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name}, badDom),
  {detailOutcome:"INVALID_RESPONSE"});
const wrongWork = {http:host.http, dom:{selectText:async()=>raw({...series, url:url+"0"})}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name}, wrongWork),
  {detailOutcome:"IDENTITY_MISMATCH"});
const aliasHost = {http:host.http,dom:{selectText:async()=>raw({...series,name:"Native Title",
  alternateName:[series.name],author:{...series.author,alternateName:"Original Creator"}})}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name},aliasHost),
  {detailOutcome:"VERIFIED_WITH_ALIASES",authors:["Example Creator","Original Creator"],aliases:["Native Title"]});
const noAuthor = {http:host.http,dom:{selectText:async()=>raw({...series,author:null})}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name},noAuthor),
  {detailOutcome:"VERIFIED_NO_ALIASES",authors:[]});
console.log("MANGAALL_DOT_CREATOR|strictParser=PASS|opaqueHandle=PASS|canonical=NOT_TESTED");
