-- Fail-closed bridge for canonical progress v2.
--
-- The existing sync RPC takes an opaque domain string. Keeping v1 and v2
-- records in different domains prevents key collisions, but does not prevent
-- old clients from continuing to write v1, so v2 must not become live yet.
--
-- Move the existing, unchanged v1 implementation to a non-exposed schema.
-- Public callers retain the *same* RPC signature through a small gate.
-- There is intentionally no activation API, feature toggle or data rewrite.
-- A future coordinated rollout requires a new reviewed migration.

alter function public.sync_apply_mutation_batch(jsonb)
    set schema tsuzuki_private;

-- Even though the old implementation is SECURITY DEFINER, it must never be
-- reachable directly from an authenticated or anonymous client.
revoke all on function tsuzuki_private.sync_apply_mutation_batch(jsonb)
    from public, anon, authenticated;

create or replace function public.sync_apply_mutation_batch(
    p_request jsonb
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    -- Refuse both new writes and idempotent replays for the unnegotiated
    -- namespace before the legacy implementation allocates any state.
    if jsonb_typeof(p_request) = 'object'
        and btrim(p_request ->> 'domain') = 'READING_PROGRESS_V2'
    then
        raise exception using
            errcode = '0A000',
            message = 'exact progress requires a negotiated protocol rollout';
    end if;

    return tsuzuki_private.sync_apply_mutation_batch(p_request);
end;
$$;

-- Authenticated, read-only server capability discovery. This is NOT a rollout
-- handshake: no device can switch epochs until an audited server migration
-- and v1 remote-frontier conversion/downgrade policy exist.
create or replace function public.sync_get_progress_protocol()
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    return jsonb_build_object(
        'schemaVersion', 1,
        'protocolEpoch', 1,
        'activeDomain', 'READING_PROGRESS',
        'exactProgressEnabled', false,
        'migrationReady', false
    );
end;
$$;

revoke all on function public.sync_apply_mutation_batch(jsonb)
    from public, anon;
grant execute on function public.sync_apply_mutation_batch(jsonb)
    to authenticated;

revoke all on function public.sync_get_progress_protocol()
    from public, anon;
grant execute on function public.sync_get_progress_protocol()
    to authenticated;
