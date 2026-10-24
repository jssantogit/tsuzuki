# Default Skin Wave 3: Entry/Auth, Home and Search

Starting HEAD: `91361160a973f65ae870ce63825f60d01007bf9f`.
Branch: `design/default-skin-consolidation`. Status: implemented; local test execution
and Android visual acceptance remain blocked by the missing local Android SDK.

Implement the approved visual families; preserve real runtime and root shell.
Onboarding completion remains the first-install authority, independent of account.
Recovery preserves callback validation/session acceptance, routes explicitly to reset
and updates password through the existing authorized Supabase repository.
Hero Details remains canonical navigation; Read selects resumable canonical progress
or first outline chapter and shares the Details chapter-opening gateway.
Search's public CTA pushes existing Providers settings only.

TDD behavioral contracts, focused local verification and affected CI are required.
Impeccable compares approved composition with the implementation without redesign.
No APK, merge, Provider/P2P runtime, Reader internals or future wave changes.

## Implementation and contracts

- Welcome uses the existing onboarding completion preference. All three choices
  complete it before navigation; logout cannot reopen Welcome. Restore remains in
  the existing Data and storage destination; no fourth Welcome action was added.
- Login, register and recovery reuse AccountRepository. Backend work runs on IO;
  state updates return to Main. Passwords are transient UI state, not saved state.
- Recovery keeps callback type/token validation and session acceptance. Only a
  successfully accepted recovery returns the explicit reset action. MainActivity
  consumes it and clears the stack before reset. Successful update clears feedback
  and exits to root; signup/email_change retain the generic Settings action.
- updatePassword uses the accepted session bearer for PUT /auth/v1/user, refreshes
  expired sessions, and excludes response credentials from public failures/logging.
- Hero Details preserves its existing route. Hero Read materializes title identity,
  selects the newest real resumable checkpoint using the Continue Reading predicate
  or the first visible canonical outline chapter, and uses the shared Details
  opening/materialization gateway. No chapter means no Reader intent.
- Home renders up to four real highlights, horizontal Continue Reading and real
  folder tiles. Search preserves runtime and uses compact keyed format rails with
  Outros fallback; its public CTA opens SettingsTsuzukiProvidersScreen only.

38 behavioral tests were added: first-install policy (3), Entry model (9),
accepted-session password update (7), callback routing/validation (7), Hero domain
selection (7), shared chapter gateway (2), Home model (2), Providers destination (1).
Existing AccountRepository test fakes received only the required interface method.

## Verification evidence

- RED commit: `ec0b014f8`; CI run 38107883417 failed compilation for missing new
  production contracts as intended. No executed RED assertion is claimed.
- Implementation commit: `f1d39c328`; affected CI run 38108676317 passed Format,
  Domain–Tsuzuki tests, App tests and CI Gate. Logs show the new behavioral tests
  executed and passed, rather than merely compiled.
- Search presentation correction: `d49ba9f13`; final affected CI run
  [38108919635](https://github.com/jssantogit/tsuzuki/actions/runs/38108919635)
  passed Format, App–Tsuzuki tests and CI Gate. Domain code is unchanged from the
  earlier green run. No instrumentation/emulator or live-provider probe executed.
- Local formatter: `java @.gradle/tsuzuki-wave3-ktlint.args`, successful; cached
  ktlint dependency classpath, no Android build. `git diff --check` passed.
- Local focused domain attempt: `./gradlew.bat :domain:testDebugUnitTest --tests
  '*OpenHomeHeroTest' --tests '*OpenCanonicalChapterTest' --offline --console=plain`.
  Failed configuration: SDK location not found. No domain tests executed locally.
- Local focused App attempt: `./gradlew.bat :app:testDebugUnitTest --tests
  '*TsuzukiEntryScreenModelTest' --tests '*EntryFlowPolicyTest' --tests
  '*SupabaseAuthCallbackTest' --tests '*SupabaseAccountRepositoryTest' --tests
  '*TsuzukiHomeScreenModelTest' --tests '*TsuzukiNavigationContractTest'
  --offline --console=plain`.
  Failed configuration with the same missing SDK. No App tests executed locally.

## Impeccable acceptance

Source comparison covered Welcome, all four Auth surfaces, Home and Search against
the approved Default Skin design and boards. Corrected primary CTA color/height/
radius, reset email-field leakage, Hero foreground/fade contrast, Search result
rails, compact ratings, recent-clear placement and approved empty/error/Provider
copy. No fake page totals or fixture catalogue values were introduced.

Android rendering, IME, gesture/Back lifecycle behavior, system bars, dark-theme
rendering and physical-device UX acceptance have not executed. Source review and
passing unit tests do not close that visual gate. The Wave 3 Definition of Done
therefore remains pending local focused tests and actual Android visual/manual
acceptance. No APK was generated, no main merge performed, and no later wave begun.
