-- Pre-activation device presence registry, not migration authorization.
-- Device claims cannot enable exact progress; unknown preexisting devices
-- remain a blocker for a future cutover even when this registry looks empty.
create table tsuzuki_private.tsuzuki_progress_protocol_devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_key text not null check (length(device_key) = 64),
    observed_protocol_epoch integer not null default 1 check (observed_protocol_epoch = 1),
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    lease_expires_at timestamptz not null,
    primary key (user_id, device_key),
    check (lease_expires_at > last_seen_at)
);

create index tsuzuki_progress_protocol_devices_active_idx
on tsuzuki_private.tsuzuki_progress_protocol_devices (user_id, lease_expires_at);

alter table tsuzuki_private.tsuzuki_progress_protocol_devices enable row level security;
revoke all on table tsuzuki_private.tsuzuki_progress_protocol_devices
    from public, anon, authenticated;

-- Explicit authenticated v1 presence heartbeat only. A device ID does not
-- prove hardware ownership, cannot claim v2, and must never be used alone to
-- decide that every historical device has been contacted.
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
    v_now timestamptz := clock_timestamp();
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
    for share;

    if v_epoch is distinct from 1 then
        raise exception using
            errcode = '0A000',
            message = 'legacy device registration is disabled at this protocol epoch';
    end if;

    v_key := encode(
        extensions.digest(
            convert_to((p_client_id::uuid)::text, 'UTF8'),
            'sha256'
        ),
        'hex'
    );
    v_expiry := v_now + interval '30 days';

    insert into tsuzuki_private.tsuzuki_progress_protocol_devices (
        user_id, device_key, observed_protocol_epoch,
        first_seen_at, last_seen_at, lease_expires_at
    )
    values (v_user_id, v_key, 1, v_now, v_now, v_expiry)
    on conflict (user_id, device_key) do update
    set last_seen_at = excluded.last_seen_at,
        lease_expires_at = excluded.lease_expires_at;

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

revoke all on function public.sync_register_progress_device(text)
    from public, anon;
grant execute on function public.sync_register_progress_device(text)
    to authenticated;
