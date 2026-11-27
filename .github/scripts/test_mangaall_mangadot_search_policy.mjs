import assert from "node:assert/strict";
import { mangaDotRetrievalQuery, mangaDotResearchPageLimit } from "./mangaall_mangadot_search_policy.mjs";
import { mangaDotExactTitle } from "./mangaall_mangadot_creator_evidence.mjs";
for (const [title, query] of [
  ["The Voyager", "Voyager"],
  ["the Lantern", "Lantern"],
  ["THE Compass", "Compass"],
  ["The Blue Planet", "The Blue Planet"],
  ["The It", "The It"],
  ["The A-B", "The A-B"],
  ["별빛 여행", "별빛 여행"],
  ["Theater", "Theater"],
]) {
  assert.equal(mangaDotRetrievalQuery(title), query);
  assert.equal(mangaDotExactTitle({ title }, title), true);
  if (query !== title) assert.equal(mangaDotExactTitle({ title: query }, title), false);
}
assert.equal(mangaDotResearchPageLimit(), 80);
console.log("MANGAALL_DOT_SEARCH_POLICY|generic=PASS|identity=UNCHANGED|cursorBound=80");
