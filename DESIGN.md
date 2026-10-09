# Tsuzuki — design contract

Tsuzuki remains manga-first. Canonical work and chapter identity are separate from metadata, reading sources and Provider identity. Mihon remains the operational reading mechanism; its legacy UI taxonomy does not define the public Tsuzuki information architecture.

## Default Skin

Settings / Default Skin is **visually complete and approved**. Implementation, Android tokens, accessibility and device acceptance remain separate work.

Settings follows: **Conta → Geral → Conteúdo → Downloads → Dados e armazenamento → Privacidade e segurança → Diagnóstico → Avançado → Sobre**. These are ordered first-level destinations, not a wizard.

Preserve quiet, low-density grouped surfaces, strong titles and drill-down for complex rules. Provider is the only public extensibility concept. Provider settings remain with the Provider; direct-P2P consent is a global, explicit privacy permission, off by default. Logs and technical tools belong to Diagnóstico. Sobre uses the official lockup and attributes Mihon as upstream.

## Reader invariant

Find/Add Reading Source is a temporary task, not navigation away from the reading session. Preserve the binding interaction: closing it and pressing Back once returns to the same chapter; searching or binding must not silently change reading progress or source preference. Library-origin title navigation keeps its existing behavior. Reader controls stay quiet and keep the current chapter and reading position central.

## Detailed references

- [Design documentation map](docs/design/README.md)
- [Approved Default Skin specification](docs/design/default-skin/design.md)
- [Navigable prototype and usage limits](docs/design/default-skin/mockups/README.md)
- [Design rationale and historical decisions](docs/design/default-skin/tensions/README.md)
- [Canonical brand identity](docs/brand/README.md)
- [Previous root baseline, preserved verbatim](docs/design/default-skin/references/history/root-design-before-consolidation.md)

The former baseline's limit to one Reader direction is superseded by the approved Default Skin scope. Its identity and Reader invariants remain valid. This contract does not authorize unrelated redesigns or implementation changes.
