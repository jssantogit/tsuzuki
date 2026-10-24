-- Serialize v1 registrations and bound self-asserted device identities.
-- Amend an unactivated RPC only; no activation or migration of progress.
-- A cap protects the private registry from unbounded account-local UUID churn,
-- while timestamp max() protects leases from concurrent/reordered heartbeats.
create or replace function public.sync_register_progress_device(
    p_client_id text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_epoch integer;
    v_key text;
    v_now timestamptz;
    v_expiry timestamptz;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    -- The Android SyncClientIdentityStore persists a UUID in no-backup
    -- storage. Normalize before hashing and never expose raw IDs as RPC data.
    if p_client_id is null or p_client_id !~
        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    then
        raise exception using
            errcode = '22023',
            message = 'client identity must be a UUID';
    end if;

    insert into tsuzuki_private.tsuzuki_progress_protocol_accounts (user_id)
    values (v_user_id)
    on conflict (user_id) do nothing;

    -- Register through the SAME account epoch lock as legacy writes. A future
    -- atomic cutover must take FOR UPDATE on this row and cannot race a v1
    -- presence announcement into a false v2-ready device.
    select a.protocol_epoch
    into v_epoch
    from tsuzuki_private.tsuzuki_progress_protocol_accounts as a
    where a.user_id = v_user_id
    for update;

    if v_epoch is distinct from 1 then
        raise exception using
            errcode = '0A000',
            message = 'legacy device registration is disabled at this protocol epoch';
    end if;

    v_now := clock_timestamp();
    v_key := encode(
        extensions.digest(
            convert_to((p_client_id::uuid)::text, 'UTF8'),
            'sha256'
        ),
        'hex'
    );
    -- Under the account FOR UPDATE lock, count and insertion are serialized
    -- even when simultaneous callers submit different self-asserted UUIDs.
    -- Do not delete or recycle expired historical observations to bypass the
    -- cap; they remain useful evidence that old installations may exist.
    if not exists (
        select 1
        from tsuzuki_private.tsuzuki_progress_protocol_devices as d
        where d.user_id = v_user_id and d.device_key = v_key
    ) and (
        select count(*)
        from tsuzuki_private.tsuzuki_progress_protocol_devices as d
        where d.user_id = v_user_id
    ) >= 64 then
        raise exception using
            errcode = '54000',
            message = 'progress device registration limit exceeded';
    end if;

    v_expiry := v_now + interval '30 days';

    insert into tsuzuki_private.tsuzuki_progress_protocol_devices as existing (
        user_id, device_key, observed_protocol_epoch,
        first_seen_at, last_seen_at, lease_expires_at
    )
    values (v_user_id, v_key, 1, v_now, v_now, v_expiry)
    on conflict (user_id, device_key) do update
    set last_seen_at = greatest(existing.last_seen_at, excluded.last_seen_at),
        lease_expires_at = greatest(existing.lease_expires_at, excluded.lease_expires_at)
    returning lease_expires_at into v_expiry;

    -- No readiness or epoch transition. This response is a heartbeat
    -- receipt, not a migration authorization token.
    return jsonb_build_object(
        'schemaVersion', 1,
        'protocolEpoch', 1,
        'leaseExpiresAt', v_expiry,
        'exactProgressEnabled', false,
        'migrationReady', false
    );
end;
$$;

-- Preserve the authenticated-only public execution contract.
revoke all on function public.sync_register_progress_device(text)
    from public, anon;
grant execute on function public.sync_register_progress_device(text)
    to authenticated;
