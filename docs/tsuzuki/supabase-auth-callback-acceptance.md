# Supabase authentication callback acceptance (PR #141)

**Status:** implementation in draft; NOT approved for release.

## Threat model and invariants

The exported Android `tsuzuki://auth` Activity must not adopt an externally
supplied Supabase session unless the same installation recently initiated the
matching signup or password-recovery email request. A valid Supabase access
token by itself is **not** evidence of local authorization to switch accounts.

- Signup and recovery each send `redirect_to=tsuzuki://auth?tsz_state=<random>`.
- The state is a 24-byte `SecureRandom` URL-safe nonce; only its SHA-256
  fingerprint is persisted under `noBackupFilesDir`.
- Matching type + state is required; it is consumed **before** verification
  or session acceptance, and cannot be reused. Tokens may be in URI fragments
  but `tsz_state` is read strictly from the URI query.
- Requests expire after 7 days, are bounded to 8 outstanding entries, and
  fail closed on damaged or unwritable storage.
- The callback updates the existing app-scoped account repository, rather
  than silently leaving an old in-memory session active.
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

## Acceptance matrix

1. Signup from logged out app: callback carries same query nonce; session is
   accepted, Account/Sync UI shows the new account without process restart.
2. Recovery from app: matching recovery nonce is accepted; signup nonce cannot
   authorize recovery, and vice versa.
3. Unsolicited deep link with valid Supabase tokens but no nonce: rejected
   without session replacement or authenticated backend request.
4. A replay of the same deep link: rejected; expiry after 7 days: rejected.
5. Process death between email initiation and callback: matching stored
   request still succeeds exactly once.
6. A wrong query nonce or nonce only in fragment: rejected; `email_change`
   callback is rejected without an initiated flow.
7. Network error after request initiation: the pending request is removed.
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
