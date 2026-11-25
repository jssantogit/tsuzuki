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
assert.deepEqual(parse({...series, author:null, creator:series.author}), ["Example Creator"]);
assert.deepEqual(parse({...series, author:[], illustrator:{...series.author,name:"Example Artist"}}), ["Example Artist"]);
assert.equal(parse({...series, author:null, publisher:series.author}), null);
assert.equal(parse({...series, author:null, contributor:series.author}), null);
assert.equal(parse({...series, author:"Unverified author", creator:series.author}), null);
assert.equal(parse({...series, author:null, illustrator:{"@type":"Organization",name:"Publisher"}}), null);
assert.deepEqual(parse({...series, creator:{...series.author,name:"Co-Creator"},
  illustrator:{...series.author,name:"Illustrator"}}), ["Example Creator","Co-Creator","Illustrator"]);
assert.deepEqual(parse({...series, "@type":"https://schema.org/ComicSeries",
  author:{"@type":"https://schema.org/Person",alternateName:"Published Pen Name"}}), ["Published Pen Name"]);
assert.deepEqual(parse({...series, author:{"@type":"Role",author:series.author}}), ["Example Creator"]);
assert.equal(parse({...series, author:{"@type":"Role",creator:series.author}}), null);
assert.equal(parse({...series, author:{"@type":"Role",author:series.author,creator:series.author}}), null);
assert.equal(parse({...series, author:{"@type":"Person"}}), null);
assert.equal(parse({...series, author:{"@type":"Person",alternateName:[""]}}), null);
const person = {"@id":url+"#writer","@type":"Person",name:"Linked Creator",alternateName:"Linked Alias"};
const linked = {...series,author:{"@id":"#writer"}};
const graph = nodes => extractMangaDotCreators(JSON.stringify({"@graph":nodes}), "42", series.name);
assert.deepEqual(graph([linked,person]), ["Linked Creator","Linked Alias"]);
assert.deepEqual(graph([{...linked,author:{"@id":url+"/#writer"}},person]), ["Linked Creator","Linked Alias"]);
assert.equal(graph([linked]), null);
assert.equal(graph([linked,person,{...person,name:"Competing Creator"}]), null);
assert.equal(graph([linked,{...person,"@type":"Organization"}]), null);
assert.equal(graph([{...linked,author:{"@id":"https://other.invalid/manga/42#writer"}},person]), null);
assert.equal(graph([{...linked,author:null},person]), null);
assert.equal(graph([linked,{...person,"@id":url+"0#writer"}]), null);
assert.equal(graph([linked,{"@id":url+"#writer","@type":"Person",name:""}]), null);
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
const semanticCalls = [];
const microdataHost = {http:host.http,dom:{selectText:async(handle,selector)=>{
  assert.equal(handle,"res:opaque");
  if (selector === "script[type=application/ld+json]") return raw({...series,author:null});
  semanticCalls.push(selector);
  assert.ok(selector.startsWith(":tsuzuki-unique "));
  assert.ok(selector.includes('[itemid="'+url+'"]'));
  assert.ok(selector.includes('[itemtype="https://schema.org/ComicSeries"]'));
  assert.ok(selector.includes(' > [itemprop="name"]'));
  if (selector.includes('[itemprop="author"]')) return "Published Author";
  if (selector.includes('[itemprop="illustrator"]')) return "Published Artist";
  throw Error("missing credit");
}}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name},microdataHost),
  {detailOutcome:"VERIFIED_NO_ALIASES",authors:["Published Author","Published Artist"]});
assert.equal(semanticCalls.length,3);
const unrelatedMicrodata = {http:host.http,dom:{selectText:async(handle,selector)=>{
  if (selector === "script[type=application/ld+json]") return raw({...series,author:null});
  throw Error("no exact typed work scope");
}}};
assert.deepEqual(await observeMangaDotCreators({externalWorkId:"mangadot:42",title:series.name},unrelatedMicrodata),
  {detailOutcome:"VERIFIED_NO_ALIASES",authors:[]});
console.log("MANGAALL_DOT_CREATOR|strictParser=PASS|opaqueHandle=PASS|canonical=NOT_TESTED");
