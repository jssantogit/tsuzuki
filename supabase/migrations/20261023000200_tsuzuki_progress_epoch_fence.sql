-- Pre-activation account epoch ledger and downgrade fence.
--
-- Every legacy progress mutation is serialized against the account's epoch
-- row. A future privileged cutover must lock/update the SAME row inside its
-- atomic transaction. This migration intentionally supplies no activation
-- endpoint, device negotiation or v2 writer.
create table tsuzuki_private.tsuzuki_progress_protocol_accounts (
    user_id uuid primary key references auth.users(id) on delete cascade,
    protocol_epoch integer not null default 1 check (protocol_epoch > 0),
    changed_at timestamptz not null default now()
);

alter table tsuzuki_private.tsuzuki_progress_protocol_accounts
    enable row level security;

-- Neither the ledger nor epoch transitions are client-owned. The SECURITY
-- DEFINER RPC is the sole authenticated mutation gate for progress.
revoke all on table tsuzuki_private.tsuzuki_progress_protocol_accounts
    from public, anon, authenticated;

create or replace function public.sync_apply_mutation_batch(
    p_request jsonb
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_domain text;
    v_epoch integer;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    v_domain := nullif(btrim(p_request ->> 'domain'), '');

    -- All v2 writes, even retries of formerly accepted mutations, remain
    -- forbidden without a separately reviewed migration and protocol gate.
    if v_domain = 'READING_PROGRESS_V2' then
        raise exception using
            errcode = '0A000',
            message = 'exact progress requires a negotiated protocol rollout';
    end if;

    if v_domain = 'READING_PROGRESS' then
        -- Existing users are enrolled lazily at epoch 1; no remote progress
        -- or sync frontier is rewritten. ON CONFLICT never resets an epoch.
        insert into tsuzuki_private.tsuzuki_progress_protocol_accounts (user_id)
        values (v_user_id)
        on conflict (user_id) do nothing;

        -- FOR SHARE holds a row lock through the entire legacy mutation.
        -- A future cutover must acquire FOR UPDATE on this same row before
        -- copying state and switching epochs, preventing in-flight v1 writes
        -- from crossing the new authoritative frontier.
        select a.protocol_epoch
        into v_epoch
        from tsuzuki_private.tsuzuki_progress_protocol_accounts as a
        where a.user_id = v_user_id
        for share;

        -- Reject new writes AND idempotency replays before touching the
        -- historical v1 implementation, including unknown future epochs.
        if v_epoch is distinct from 1 then
            raise exception using
                errcode = '0A000',
                message = 'legacy progress writes are disabled at this protocol epoch';
        end if;
    end if;

    -- Other compatible sync domains retain their historical behavior.
    return tsuzuki_private.sync_apply_mutation_batch(p_request);
end;
$$;

-- This scoped reader must be SECURITY DEFINER: authenticated clients may
-- execute the RPC but cannot SELECT the private account ledger themselves.
create or replace function public.sync_get_progress_protocol()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_epoch integer;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    -- Absent rows are legacy epoch 1. Reading does not enroll or mutate.
    select a.protocol_epoch
    into v_epoch
    from tsuzuki_private.tsuzuki_progress_protocol_accounts as a
    where a.user_id = v_user_id;

    if coalesce(v_epoch, 1) <> 1 then
        raise exception using
            errcode = '0A000',
            message = 'progress protocol epoch is not supported by this client';
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
