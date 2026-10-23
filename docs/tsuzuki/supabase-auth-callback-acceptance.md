# Supabase authentication callback acceptance (PR #141)

**Status:** implementation in draft; NOT approved for release.

## Threat model and invariants

The exported Android `tsuzuki://auth` Activity must not adopt an externally
supplied Supabase session unless the same installation recently initiated the
matching signup or password-recovery email request. A valid Supabase access
token by itself is **not** evidence of local authorization to switch accounts.

- Signup and recovery each send `redirect_to=tsuzuki://auth?tsz_state=<random>`.
- The state is a 24-byte `SecureRandom` URL-safe nonce; only its SHA-256
  fingerprint is stored with the device-private 32-byte PKCE verifier under
  `noBackupFilesDir`. The code challenge is BASE64URL(SHA-256(verifier)).
- Matching state (and type, if present) is required; it is consumed **before**
  code exchange or session acceptance, and cannot be reused. The callback
  accepts ONLY `code` in the URI query. Bearer credentials and URI fragments
  are rejected; the verifier is sent only in a POST to `grant_type=pkce`.
- Requests expire after 7 days, are bounded to 8 outstanding entries, and
  fail closed on damaged or unwritable storage.
- The callback exchanges the code with its locally held verifier, then updates
  the existing app-scoped account repository rather than leaving an old
  in-memory session active.
- `email_change` is deliberately not an accepted callback type: there is no
  corresponding initiated flow in Tsuzuki yet.
- No session or sync state is deleted to repair mismatched cloud ownership.

## Required server configuration BEFORE deploying this change

Supabase Auth **URL Configuration → Redirect URLs** must allow the dynamic
query-bearing mobile redirect, for example `tsuzuki://auth?tsz_state=*`,
in addition to any original static callback. Ensure the configured wildcard
matches the actual Supabase Auth URL matcher. Review email templates if they
override `ConfirmationURL` or `RedirectTo`; they must carry the full URL.
Do not assume Supabase preserves the request state merely because its API
returned HTTP 200.

This document does not claim that the live project configuration has been
changed. Verify both registration and password recovery using real emails
before release.

## PKCE implementation and residual release risks

The code now uses PKCE-S256 for signup and password recovery. An app that
intercepts `tsuzuki://auth?...&code=...` does not have the device-private
verifier required to exchange the code, and Tsuzuki rejects old implicit
bearer-token links instead of adopting their sessions. Supabase's GoTrue
`/signup` and `/recover` endpoints accept `code_challenge` and
`code_challenge_method=s256`; `/token?grant_type=pkce` accepts
`auth_code` and `code_verifier`.

This **does not** mean the whole system is release-accepted: verify hosted
Supabase redirect allowlist + custom email templates, confirm PKCE is enabled
for email confirmation (signup with autoconfirm is incompatible), prove
code-only redirects on device, and consider verified HTTPS Android App Links
rather than unverified custom URI handling for defense in depth.
- https://supabase.com/docs/guides/auth/sessions/pkce-flow
- https://supabase.com/docs/guides/auth/server-side/advanced-guide
- https://github.com/supabase/auth/blob/master/openapi.yaml

## Acceptance matrix

1. Signup from logged out app: callback carries code and matching query nonce; PKCE exchanges it and session is
   accepted, Account/Sync UI shows the new account without process restart.
2. Recovery from app: matching recovery nonce is accepted; signup nonce cannot
   authorize recovery, and vice versa.
3. Unsolicited deep link with valid Supabase tokens (even with a matching state):
   rejected without token exchange or session replacement.
4. A replay of the same deep link: rejected; expiry after 7 days: rejected.
5. Process death between email initiation and callback: matching stored
   request still succeeds exactly once.
6. Wrong query nonce or fragment-only nonce is rejected. Duplicated query keys,
   bearer tokens, and `email_change` are rejected.
7. Network error while initiating signup/recovery: the pending request is removed.
   Exchange errors also permanently consume that code's verifier; restart flow.
8. Unexpected account switching A→B with prior pending sync state: no RPC
   authenticated for B carries A's batch/cursor; local data is retained.
9. Legacy install with pre-existing unowned cloud metadata: syncing remains
   paused; **account-safe recovery/migration is still pending**. This is a
   release blocker, not a silently accepted UX.
10. Physical Android deep-link handling, backend redirect allowlist and
    cloud/session state transitions verified on device.

## Branch coordination

The resolver PR #140 owns canonical offline artifact validation (including
temporarily inaccessible storage and archive readability). PR #141 explicitly
removed its overlapping download changes, since deleting local metadata
when the storage volume is detached would be unsafe. PR #141 now owns
auth, sync isolation and backup retention.
