# Default Skin consolidation — migration record

## Result and boundaries

Settings / Default Skin is visually closed; this migration versions existing evidence and its approved specification. Android code, Notion and product behavior are unchanged. No merge or push is performed.

Base: `834ea8ecdcfb7349c6b21b6265dede46ce04a297` (freshly fetched main). Branch: `design/default-skin-consolidation`. Source project: `be4c235f-35dc-40b3-9faf-0952dc3e5868`.

## Structure decisions

Root DESIGN.md keeps identity and Reader invariants and links to the living specification. Its former narrow authorization scope is archived and superseded. docs/design owns the full skin, while docs/brand and docs/branding retain their existing identity responsibilities. No material is placed in superpowers specs.

Canonical prototype stays in mockups/index.html with its relative assets. Old local HTML/tensions are preserved separately before removing input duplicates from the repository root. The external workspace is retained. The old HTML uses adjusted relative links to shared identical font assets, avoiding duplicate font binaries. Original records are contextualized as history instead of silently turning every Provisional detail into an approved global token.

## Inventory disposition

177 external files: 94 migrated, 1 byte-identical visual duplicate deduplicated, 82 excluded. Four pre-existing untracked repository files were preserved or safely relocated. The machine-readable [inventory](inventory.json) records every byte size/hash and mapping.

| External path | Action | Destination or reason |
|---|---|---|
| `%SystemDrive%/ProgramData/Microsoft/Windows/Caches/cversions.2.db` | excluded | Windows cache accidentally present in workspace; not design content |
| `%SystemDrive%/ProgramData/Microsoft/Windows/Caches/{6AF0698E-D558-4F6E-9B3C-3716689AF493}.2.ver0x0000000000000001.db` | excluded | Windows cache accidentally present in workspace; not design content |
| `%SystemDrive%/ProgramData/Microsoft/Windows/Caches/{DDF571F2-BE98-426D-8288-1A9A39C3FDA2}.2.ver0x0000000000000001.db` | excluded | Windows cache accidentally present in workspace; not design content |
| `.account_html_update.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `.file-versions/0eb547304658805aad788d32/0001-497a8691-8f3b-4176-a129-69e9a714ba21.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0002-c453d81e-6ee2-4bba-bb63-47ebc4b7da91.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0003-c0652b2e-8ccd-411a-9dfb-5543d04fecba.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0004-51461f9e-06ff-48f7-b11f-10ca75c00d60.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0005-29662015-8e2b-4d56-847a-5540815466d1.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0006-588ce720-eb5a-47d2-a804-ef7e1a04c52b.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0007-377c6fcb-cf93-4fc5-9572-5abb2f265eb2.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0008-e211a9e9-47a6-44ed-81ba-a74aed818c55.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0009-8ccdb9ed-cb35-4bad-a091-953274b47f59.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0010-924d46b8-b4e4-431a-bc3d-ad8690adfb5e.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0011-91f4f520-9a51-44f4-b059-a19aa32e29fb.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0012-eef25109-deff-4db4-8a37-6e9cc691ea42.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0013-f989c0aa-749c-4d84-8059-9fe189582b7a.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0014-96887361-1c35-472d-a412-06f13ecff83a.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0015-2577f17e-f06c-412e-854c-47dc294a63ac.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0016-c6f14a20-67ae-45fc-9075-f98fb777cfd8.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0017-49d03802-0f7d-4ba9-b301-63ec5e308ea1.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0018-e963f22a-ce4c-4f24-960e-6e58d5669695.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0019-1ea752f1-246c-4971-9aeb-bf5431e54cda.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0020-d0a82b12-b8a9-45c2-a08e-677be2647d6e.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0021-2ac32fbb-54e3-4ff7-a6a6-3e514d5027d8.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0022-6bbe7d07-f394-4dc4-9b97-3c3d61b1f13b.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0023-f89a3db5-52cc-4ae3-b556-fc5ca00d3a18.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0024-04ddee5a-7e63-493b-9bc9-d8ead2514748.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0025-541cc1c5-73dd-4b1f-9678-bfbbb559f626.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0026-ad62239f-9f2c-4e31-9a55-d4cd515ce1ad.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0027-db473f0f-a065-4f64-a589-3918084c5d21.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0028-4e08269f-6479-499f-8cd7-7addfaaacf25.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0029-b87be1d4-b240-4c37-8f26-defa435accd3.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0030-9df2ab4f-605b-4078-94a6-893dd40e007a.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0031-6df45f58-ba30-4b25-8f39-1edb4a537094.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0032-6cb13d2e-8413-446f-a824-5ff9cd094688.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0033-906eb41c-6619-4abd-b58c-4ca02bf57150.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0034-fa31891b-9d82-4d75-9ded-67afc456d103.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0035-3cd0f525-c87e-4e5c-bc7f-9218f3ecd9d5.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0036-393aa749-157a-4914-b607-3f8b4059b146.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0037-77e75c1f-a3d4-4465-8f80-b590a94c8bdb.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0038-40416b9a-4dba-4069-b983-ddc10fc4bc44.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0039-e5f5b671-e31f-4777-80f3-565271a3e4c4.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0040-ad140cf0-5673-434a-9aad-1196df16fcb1.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0041-f37e631e-f3e8-44bc-a765-55eabc349ed3.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0042-40de0c80-e9f6-4275-b989-d4e66da1e3a4.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0043-853a3487-a6c9-4d44-9ea3-6cc5bd236a52.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0044-f7f00488-19d4-4c88-998a-48a66a1c1a66.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0045-06febdf3-78d1-43eb-aa58-afe8ad140818.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0046-8638b26b-2cb8-476a-a286-c36e4eb82d1c.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0047-176d5d3b-e9e9-41d1-96ef-021b9f224435.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0048-616c2904-61ce-423c-9c1d-ce0f5241a0e9.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0049-30b5f1d4-a335-49b7-9501-16e911d47089.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0050-7ba2d6d5-9c90-49e8-9c84-8bd7a7dc7c75.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0051-d04561d1-a232-4a28-acfb-f614180829b5.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0052-27ba5fbe-ca0d-4cb1-aa5e-4f428d21e831.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0053-6e5e4cbc-cb57-49d5-bc56-792e3959f59f.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0054-27dd5757-29ab-47b9-88c8-e485b42f0532.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0055-da0aa64f-ebbc-40df-9d49-6f243739fe80.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0056-82fa5c9c-8b8f-444b-b783-764a0591ddf6.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0057-d77ade13-0a93-4dc8-a540-4862be5e4447.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0058-92c0750a-365a-4bca-a113-e513aad11b2d.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0059-d97f08df-068e-4492-b7bc-3b36975386ec.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0060-abdd0364-fcd4-4058-8e21-9fd02ecf2f51.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/0061-6b48130d-a176-42a6-a11a-a5c1db9b8a6c.html` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.file-versions/0eb547304658805aad788d32/manifest.json` | excluded | OpenDesign undo archive; previous useful baseline and source evidence preserved separately |
| `.od-frames/.od-next-device-frames.json` | excluded | Staged presentation templates; the prototype already embeds the required shell and layout primitives |
| `.od-frames/android.html` | excluded | Staged presentation templates; the prototype already embeds the required shell and layout primitives |
| `.od-frames/iphone.html` | excluded | Staged presentation templates; the prototype already embeds the required shell and layout primitives |
| `.od-frames/layout.css` | excluded | Staged presentation templates; the prototype already embeds the required shell and layout primitives |
| `.od-frames/neutral.html` | excluded | Staged presentation templates; the prototype already embeds the required shell and layout primitives |
| `assets/avancado.css` | migrated | docs/design/default-skin/mockups/assets/avancado.css |
| `assets/avancado.js` | migrated | docs/design/default-skin/mockups/assets/avancado.js |
| `assets/data-storage.css` | migrated | docs/design/default-skin/mockups/assets/data-storage.css |
| `assets/data-storage.js` | migrated | docs/design/default-skin/mockups/assets/data-storage.js |
| `assets/diagnostico.css` | migrated | docs/design/default-skin/mockups/assets/diagnostico.css |
| `assets/diagnostico.js` | migrated | docs/design/default-skin/mockups/assets/diagnostico.js |
| `assets/downloads.css` | migrated | docs/design/default-skin/mockups/assets/downloads.css |
| `assets/downloads.js` | migrated | docs/design/default-skin/mockups/assets/downloads.js |
| `assets/fonts/bahnschrift.ttf` | migrated | docs/design/default-skin/mockups/assets/fonts/bahnschrift.ttf |
| `assets/fonts/segoeui.ttf` | migrated | docs/design/default-skin/mockups/assets/fonts/segoeui.ttf |
| `assets/fonts/segoeuib.ttf` | migrated | docs/design/default-skin/mockups/assets/fonts/segoeuib.ttf |
| `assets/lookups/mangaupdates-genres.json` | migrated | docs/design/default-skin/mockups/assets/lookups/mangaupdates-genres.json |
| `assets/lookups/shikimori-publishers.json` | migrated | docs/design/default-skin/mockups/assets/lookups/shikimori-publishers.json |
| `assets/perfil/attack-on-titan-v01.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/attack-on-titan-v01.jpg |
| `assets/perfil/death-note-v01.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/death-note-v01.jpg |
| `assets/perfil/fullmetal-alchemist-v01.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/fullmetal-alchemist-v01.jpg |
| `assets/perfil/one-piece-v01.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/one-piece-v01.jpg |
| `assets/perfil/one-piece-v02.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/one-piece-v02.jpg |
| `assets/perfil/one-piece-v03.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/one-piece-v03.jpg |
| `assets/perfil/witch-hat-atelier-v01.jpg` | migrated | docs/design/default-skin/mockups/assets/perfil/witch-hat-atelier-v01.jpg |
| `assets/privacy-security.css` | migrated | docs/design/default-skin/mockups/assets/privacy-security.css |
| `assets/privacy-security.js` | migrated | docs/design/default-skin/mockups/assets/privacy-security.js |
| `assets/sobre/tsuzuki-lockup.svg` | migrated | docs/design/default-skin/mockups/assets/sobre/tsuzuki-lockup.svg |
| `assets/sobre.css` | migrated | docs/design/default-skin/mockups/assets/sobre.css |
| `assets/sobre.js` | migrated | docs/design/default-skin/mockups/assets/sobre.js |
| `assets/tracking/bangumi.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/bangumi.svg |
| `assets/tracking/hikka.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/hikka.svg |
| `assets/tracking/kitsu.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/kitsu.svg |
| `assets/tracking/mangaupdates.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/mangaupdates.svg |
| `assets/tracking/myanimelist.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/myanimelist.svg |
| `assets/tracking/shikimori.svg` | migrated | docs/design/default-skin/mockups/assets/tracking/shikimori.svg |
| `build_advanced_filters.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `build_preserved_query.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `build_providers.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `build_quick_builder.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `build_remote_lookup.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `build_source_switch.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `correct_quick_builder.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `correct_quick_builder_scroll.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `design.md` | migrated | docs/design/default-skin/references/history/default-skin-before-settings-closure.md |
| `docs/design/aparencia-tensions.md` | migrated | docs/design/default-skin/tensions/aparencia-tensions.md |
| `docs/design/avancado-tensions.md` | migrated | docs/design/default-skin/tensions/avancado-tensions.md |
| `docs/design/biblioteca-tensions.md` | migrated | docs/design/default-skin/tensions/biblioteca-tensions.md |
| `docs/design/collections-tensions.md` | migrated | docs/design/default-skin/tensions/collections-tensions.md |
| `docs/design/conta-tensions.md` | migrated | docs/design/default-skin/tensions/conta-tensions.md |
| `docs/design/dados-armazenamento-tensions.md` | migrated | docs/design/default-skin/tensions/dados-armazenamento-tensions.md |
| `docs/design/diagnostico-tensions.md` | migrated | docs/design/default-skin/tensions/diagnostico-tensions.md |
| `docs/design/downloads-tensions.md` | migrated | docs/design/default-skin/tensions/downloads-tensions.md |
| `docs/design/folder-catalog-tensions.md` | migrated | docs/design/default-skin/tensions/folder-catalog-tensions.md |
| `docs/design/integracoes-tensions.md` | migrated | docs/design/default-skin/tensions/integracoes-tensions.md |
| `docs/design/leitura-tensions.md` | migrated | docs/design/default-skin/tensions/leitura-tensions.md |
| `docs/design/perfil-tensions.md` | migrated | docs/design/default-skin/tensions/perfil-tensions.md |
| `docs/design/personalizacao-tensions.md` | migrated | docs/design/default-skin/tensions/personalizacao-tensions.md |
| `docs/design/preset-visual-tensions.md` | migrated | docs/design/default-skin/tensions/preset-visual-tensions.md |
| `docs/design/privacidade-seguranca-tensions.md` | migrated | docs/design/default-skin/tensions/privacidade-seguranca-tensions.md |
| `docs/design/providers-tensions.md` | migrated | docs/design/default-skin/tensions/providers-tensions.md |
| `docs/design/sobre-tensions.md` | migrated | docs/design/default-skin/tensions/sobre-tensions.md |
| `docs/design/tracking-tensions.md` | migrated | docs/design/default-skin/tensions/tracking-tensions.md |
| `drawing-2026-10-04T20-39-41-967Z.png` | migrated | docs/design/default-skin/references/visual-history/drawing-2026-10-04T20-39-41-967Z.png |
| `Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_14.png` | migrated | docs/design/default-skin/references/visual-history/Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_14.png |
| `Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_17.jpg` | migrated | docs/design/default-skin/references/visual-history/Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_17.jpg |
| `Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_24.png` | migrated | docs/design/default-skin/references/visual-history/Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_24.png |
| `index.html` | migrated | docs/design/default-skin/mockups/index.html |
| `index.html.artifact.json` | excluded | OpenDesign host metadata; not needed to open the prototype |
| `Interface-escura-de-configurações-móveis.png` | migrated | docs/design/default-skin/references/visual-history/Interface-escura-de-configurações-móveis.png |
| `refine_providers_discover.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `refine_quick_builder_light.py` | excluded | One-off editing script for earlier iterations; frozen editable HTML is the maintained source, not these scripts |
| `screenshot-2026-10-03T18-19-16-598Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-03T18-19-16-598Z.png |
| `screenshot-2026-10-03T21-20-00-055Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-03T21-20-00-055Z.png |
| `screenshot-2026-10-03T22-58-32-691Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-03T22-58-32-691Z.png |
| `screenshot-2026-10-04T01-01-03-908Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T01-01-03-908Z.png |
| `screenshot-2026-10-04T01-01-08-035Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T01-01-08-035Z.png |
| `screenshot-2026-10-04T01-22-10-714Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T01-22-10-714Z.png |
| `screenshot-2026-10-04T18-09-27-234Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-09-27-234Z.png |
| `screenshot-2026-10-04T18-10-19-685Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-10-19-685Z.png |
| `screenshot-2026-10-04T18-18-16-799Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-18-16-799Z.png |
| `screenshot-2026-10-04T18-18-30-015Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-18-30-015Z.png |
| `screenshot-2026-10-04T18-28-30-862Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-28-30-862Z.png |
| `screenshot-2026-10-04T18-39-47-928Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-39-47-928Z.png |
| `screenshot-2026-10-04T18-40-07-814Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T18-40-07-814Z.png |
| `screenshot-2026-10-04T20-29-19-015Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T20-29-19-015Z.png |
| `screenshot-2026-10-04T20-32-23-969Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T20-32-23-969Z.png |
| `screenshot-2026-10-04T20-36-35-023Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T20-36-35-023Z.png |
| `screenshot-2026-10-04T20-39-51-108Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T20-39-51-108Z.png |
| `screenshot-2026-10-04T21-13-32-649Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-04T21-13-32-649Z.png |
| `screenshot-2026-10-07T20-38-23-964Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T20-38-23-964Z.png |
| `screenshot-2026-10-07T20-38-49-095Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T20-38-49-095Z.png |
| `screenshot-2026-10-07T20-46-13-578Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T20-46-13-578Z.png |
| `screenshot-2026-10-07T21-20-57-377Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T21-20-57-377Z.png |
| `screenshot-2026-10-07T21-23-09-327Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T21-23-09-327Z.png |
| `screenshot-2026-10-07T21-24-41-950Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T21-24-41-950Z.png |
| `screenshot-2026-10-07T21-46-44-190Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-07T21-46-44-190Z.png |
| `screenshot-2026-10-09T18-19-44-099Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T18-19-44-099Z.png |
| `screenshot-2026-10-09T18-34-46-791Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T18-34-46-791Z.png |
| `screenshot-2026-10-09T18-35-03-950Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T18-35-03-950Z.png |
| `screenshot-2026-10-09T19-27-44-512Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-27-44-512Z.png |
| `screenshot-2026-10-09T19-27-51-035Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-27-51-035Z.png |
| `screenshot-2026-10-09T19-29-45-380Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-29-45-380Z.png |
| `screenshot-2026-10-09T19-53-29-415Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-53-29-415Z.png |
| `screenshot-2026-10-09T19-55-57-924Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-55-57-924Z.png |
| `screenshot-2026-10-09T19-56-09-702Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T19-56-09-702Z.png |
| `screenshot-2026-10-09T20-15-13-307Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T20-15-13-307Z.png |
| `screenshot-2026-10-09T20-17-13-008Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T20-17-13-008Z.png |
| `screenshot-2026-10-09T20-17-43-433Z.png` | migrated | docs/design/default-skin/references/visual-history/screenshot-2026-10-09T20-17-43-433Z.png |
| `Wireframe-de-Perfil-em-Modo-Escuro-1.png` | migrated | docs/design/default-skin/references/visual-history/Wireframe-de-Perfil-em-Modo-Escuro-1.png |
| `Wireframe-de-Perfil-em-Modo-Escuro.png` | deduplicated | docs/design/default-skin/references/visual-history/Wireframe-de-Perfil-em-Modo-Escuro-1.png |

## Pre-existing repository copies

| Input | Preserved destination |
|---|---|
| `index.html` | `docs/design/default-skin/mockups/history/personalizacao/index.html` |
| `docs/design/personalizacao-tensions.md` | `docs/design/default-skin/references/history/personalizacao-tensions-before-migration.md` |
| `assets/fonts/segoeui.ttf` | `docs/design/default-skin/mockups/assets/fonts/segoeui.ttf` |
| `assets/fonts/segoeuib.ttf` | `docs/design/default-skin/mockups/assets/fonts/segoeuib.ttf` |

## Verification

Static integrity verification passed. See [machine-readable results](checks.json). Local Markdown links and HTML/CSS/JS asset paths resolve; both current and historical HTML entries have complete document boundaries; SVG/JSON inputs parse. Source/copy comparisons passed and the external workspace remains unchanged. No intended file is ignored by .gitignore, so no ignore-rule change was necessary.

The source/copy hashes preserve original working-copy bytes. `gitContentSha256` additionally records the staged content after the repository’s existing text/eol=lf normalization. Six source-derived text files normalize CRLF to LF; binary assets are unchanged. The historical HTML has only font-path/newline normalization, pointing to shared identical fonts. No current UI source was rewritten.

Only DESIGN.md and docs/design are staged; product code, build files and CI files are unchanged. git diff --cached --check passed. No preview, rendering, Android tests or visual acceptance was performed; the checks establish migration integrity. No network services were exercised by the prototype.


## Final tree

```text
docs/design/
├── default-skin/
│   ├── mockups/
│   │   ├── assets/
│   │   │   ├── fonts/
│   │   │   │   ├── bahnschrift.ttf
│   │   │   │   ├── segoeui.ttf
│   │   │   │   └── segoeuib.ttf
│   │   │   ├── lookups/
│   │   │   │   ├── mangaupdates-genres.json
│   │   │   │   └── shikimori-publishers.json
│   │   │   ├── perfil/
│   │   │   │   ├── attack-on-titan-v01.jpg
│   │   │   │   ├── death-note-v01.jpg
│   │   │   │   ├── fullmetal-alchemist-v01.jpg
│   │   │   │   ├── one-piece-v01.jpg
│   │   │   │   ├── one-piece-v02.jpg
│   │   │   │   ├── one-piece-v03.jpg
│   │   │   │   └── witch-hat-atelier-v01.jpg
│   │   │   ├── sobre/
│   │   │   │   └── tsuzuki-lockup.svg
│   │   │   ├── tracking/
│   │   │   │   ├── bangumi.svg
│   │   │   │   ├── hikka.svg
│   │   │   │   ├── kitsu.svg
│   │   │   │   ├── mangaupdates.svg
│   │   │   │   ├── myanimelist.svg
│   │   │   │   └── shikimori.svg
│   │   │   ├── avancado.css
│   │   │   ├── avancado.js
│   │   │   ├── data-storage.css
│   │   │   ├── data-storage.js
│   │   │   ├── diagnostico.css
│   │   │   ├── diagnostico.js
│   │   │   ├── downloads.css
│   │   │   ├── downloads.js
│   │   │   ├── privacy-security.css
│   │   │   ├── privacy-security.js
│   │   │   ├── sobre.css
│   │   │   └── sobre.js
│   │   ├── history/
│   │   │   └── personalizacao/
│   │   │       └── index.html
│   │   ├── index.html
│   │   └── README.md
│   ├── references/
│   │   ├── history/
│   │   │   ├── default-skin-before-settings-closure.md
│   │   │   ├── personalizacao-tensions-before-migration.md
│   │   │   └── root-design-before-consolidation.md
│   │   ├── visual-history/
│   │   │   ├── drawing-2026-10-04T20-39-41-967Z.png
│   │   │   ├── Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_14.png
│   │   │   ├── Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_17.jpg
│   │   │   ├── Imagem-do-ChatGPT-3-de-out.-de-2026_-14_57_24.png
│   │   │   ├── Interface-escura-de-configurações-móveis.png
│   │   │   ├── screenshot-2026-10-03T18-19-16-598Z.png
│   │   │   ├── screenshot-2026-10-03T21-20-00-055Z.png
│   │   │   ├── screenshot-2026-10-03T22-58-32-691Z.png
│   │   │   ├── screenshot-2026-10-04T01-01-03-908Z.png
│   │   │   ├── screenshot-2026-10-04T01-01-08-035Z.png
│   │   │   ├── screenshot-2026-10-04T01-22-10-714Z.png
│   │   │   ├── screenshot-2026-10-04T18-09-27-234Z.png
│   │   │   ├── screenshot-2026-10-04T18-10-19-685Z.png
│   │   │   ├── screenshot-2026-10-04T18-18-16-799Z.png
│   │   │   ├── screenshot-2026-10-04T18-18-30-015Z.png
│   │   │   ├── screenshot-2026-10-04T18-28-30-862Z.png
│   │   │   ├── screenshot-2026-10-04T18-39-47-928Z.png
│   │   │   ├── screenshot-2026-10-04T18-40-07-814Z.png
│   │   │   ├── screenshot-2026-10-04T20-29-19-015Z.png
│   │   │   ├── screenshot-2026-10-04T20-32-23-969Z.png
│   │   │   ├── screenshot-2026-10-04T20-36-35-023Z.png
│   │   │   ├── screenshot-2026-10-04T20-39-51-108Z.png
│   │   │   ├── screenshot-2026-10-04T21-13-32-649Z.png
│   │   │   ├── screenshot-2026-10-07T20-38-23-964Z.png
│   │   │   ├── screenshot-2026-10-07T20-38-49-095Z.png
│   │   │   ├── screenshot-2026-10-07T20-46-13-578Z.png
│   │   │   ├── screenshot-2026-10-07T21-20-57-377Z.png
│   │   │   ├── screenshot-2026-10-07T21-23-09-327Z.png
│   │   │   ├── screenshot-2026-10-07T21-24-41-950Z.png
│   │   │   ├── screenshot-2026-10-07T21-46-44-190Z.png
│   │   │   ├── screenshot-2026-10-09T18-19-44-099Z.png
│   │   │   ├── screenshot-2026-10-09T18-34-46-791Z.png
│   │   │   ├── screenshot-2026-10-09T18-35-03-950Z.png
│   │   │   ├── screenshot-2026-10-09T19-27-44-512Z.png
│   │   │   ├── screenshot-2026-10-09T19-27-51-035Z.png
│   │   │   ├── screenshot-2026-10-09T19-29-45-380Z.png
│   │   │   ├── screenshot-2026-10-09T19-53-29-415Z.png
│   │   │   ├── screenshot-2026-10-09T19-55-57-924Z.png
│   │   │   ├── screenshot-2026-10-09T19-56-09-702Z.png
│   │   │   ├── screenshot-2026-10-09T20-15-13-307Z.png
│   │   │   ├── screenshot-2026-10-09T20-17-13-008Z.png
│   │   │   ├── screenshot-2026-10-09T20-17-43-433Z.png
│   │   │   └── Wireframe-de-Perfil-em-Modo-Escuro-1.png
│   │   ├── checks.json
│   │   ├── inventory.json
│   │   ├── migration.md
│   │   └── README.md
│   ├── tensions/
│   │   ├── aparencia-tensions.md
│   │   ├── avancado-tensions.md
│   │   ├── biblioteca-tensions.md
│   │   ├── collections-tensions.md
│   │   ├── conta-tensions.md
│   │   ├── dados-armazenamento-tensions.md
│   │   ├── diagnostico-tensions.md
│   │   ├── downloads-tensions.md
│   │   ├── folder-catalog-tensions.md
│   │   ├── integracoes-tensions.md
│   │   ├── leitura-tensions.md
│   │   ├── perfil-tensions.md
│   │   ├── personalizacao-tensions.md
│   │   ├── preset-visual-tensions.md
│   │   ├── privacidade-seguranca-tensions.md
│   │   ├── providers-tensions.md
│   │   ├── README.md
│   │   ├── sobre-tensions.md
│   │   └── tracking-tensions.md
│   └── design.md
└── README.md
```

## Files created or changed

DESIGN.md is the only previously tracked file changed. All files in the tree above are new tracked design documentation/assets; source relocation is represented as additions because the earlier repository copies were untracked. The per-input disposition table and inventory identify every migrated file. Temporary migration/check helpers were kept outside the repository and are not versioned.
