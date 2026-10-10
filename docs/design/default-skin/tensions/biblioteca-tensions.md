# Biblioteca — tensões em aberto

The main Biblioteca visual proposal is documented in the [current Default Skin specification](../design.md). Its six board states are ready for visual review; that review has not happened, so the proposal is not marked approved or closed. The approved **Configurações → Biblioteca** work remains current and is summarized in the specification. Its preference screens are outside this round.

No additional visual-direction decision is open. The remaining questions require the real Android implementation and device behavior; a browser mockup cannot settle them.

| Open tension | What needs Android evidence |
|---|---|
| Adaptive cover grid | Confirm column changes, natural cover ratios, spacing and usable cover targets across supported Android widths and orientations. |
| System insets | Confirm status/navigation bars, gesture navigation and the modal sheet clear system UI on supported devices. |
| Font scaling | Confirm the large title, status rail, sheet content and bottom navigation remain readable and usable at user-selected font scales. |
| TalkBack | Confirm reading order, selected status/filter announcements, cover names and modal focus/dismissal behavior. |
| Touch targets | Confirm every action and cover target meets Android touch guidance without changing the sparse composition. |
| Animations and transitions | Confirm search expansion, sheet motion and state feedback remain understandable, performant and respectful of reduced motion. |

The prototype's local CSS values and fonts are visual evidence only. They do not establish Android dp/sp tokens, accessibility conformance, runtime provider availability, or font redistribution rights. Existing font provenance and licensing notes remain in the [mockup usage notes](../mockups/README.md).

The board's Kitsu/Manga/+2 filter example, example custom list, Sem categoria and seven local cover fixtures are demonstrative content. They do not define the runtime Provider list, custom lists, user categories, canonical catalogue, title-to-cover mapping, or a real user's library. Runtime origins and custom lists remain dynamic, and canonical artwork remains preferred where available.
