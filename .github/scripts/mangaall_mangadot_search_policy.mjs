// Retrieval-only policy for the unsigned manhwa experiment.
// Never use the transformed query as a title identity or creator proof.
export function mangaDotRetrievalQuery(title) {
  if (typeof title !== "string") return title;
  // A leading English article adds no specificity to a one-word search.
  // Leave multi-word, non-English, punctuated and very short titles alone.
  const match = /^the\s+([\p{L}\p{M}]{4,64})$/iu.exec(title.trim());
  return match ? match[1] : title;
}
export function mangaDotResearchPageLimit() {
  // Uniform transport cursor ceiling, not permission to fetch 80 pages
  // in one invocation. The Host retains its independent page/time budgets.
  return 80;
}
