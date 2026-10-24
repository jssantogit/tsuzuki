# Design documentation

This directory is living product documentation. It is not an implementation spec under `docs/superpowers/specs`, and is broader than brand identity.

## Sources of truth

| Source | Responsibility |
|---|---|
| [Root DESIGN.md](../../DESIGN.md) | Short identity, invariants and documentation entry |
| [Default Skin specification](default-skin/design.md) | Current approved visual architecture and component behavior |
| [Tensions](default-skin/tensions/README.md) | Rationale, trade-offs, original iteration records and remaining implementation questions |
| [Mockups](default-skin/mockups/README.md) | Editable, executable visual evidence; fixtures do not prove Android behavior |
| [References](default-skin/references/README.md) | Provenance, migration inventory and historical visual evidence |
| [Brand](../brand/README.md) | Master mark, wordmark and official identity assets |
| [Tsuzuki Rating](../branding/tsuzuki-rating/README.md) | Existing specialized mark; not a second Default Skin specification |

Notion is canonical project memory for decisions and milestone status. This Git documentation is the versioned design handoff. Android code is the functional implementation contract. If they disagree, document the discrepancy rather than silently changing behavior or treating a mockup as implemented functionality.

## Reading order for implementation agents

Read root DESIGN.md, then Default Skin `design.md`, the relevant subsystem tension record and the mockup usage notes. Consult the Android preference/control implementation before porting behavior. Honor AGENTS.md and the repository's verification policy for any subsequent code work.

## Maintenance

Keep current rules in the specification; date and scope changes to rationale. Retain rejected alternatives only as explicitly historical evidence. Never promote synthetic entities, sample values or superseded screens to product truth. Add future surfaces under the same documentation family. Avoid parallel summaries in brand, research or one-off implementation plans.

Settings is visually closed. The next work is planning and implementing the approved Default Skin with Android tokens, insets, accessibility and real-device acceptance; this migration does not perform that work.
