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
assert.equal(parse({...series, name:"Unrelated"}), null);
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
console.log("MANGAALL_DOT_CREATOR|strictParser=PASS|opaqueHandle=PASS|canonical=NOT_TESTED");
