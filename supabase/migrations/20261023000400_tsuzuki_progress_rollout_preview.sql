-- Non-authorizing, account-scoped snapshot of server-observable v1 state.
-- It cannot see a disconnected client's pending outbox or prove that every
-- historical device has enrolled. It never enrolls an account or changes
-- epoch, remote progress, sync cursor or accepted mutation receipts.
create or replace function public.sync_preview_progress_rollout()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_epoch integer;
    v_now timestamptz := clock_timestamp();
    v_device_count bigint;
    v_unexpired_count bigint;
    v_record_count bigint;
    v_tombstone_count bigint;
    v_cursor bigint;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    -- Reading does not enroll a ledger row. If one exists, share its lock
    -- with legacy writes so a future cutover cannot race this preflight.
    select a.protocol_epoch into v_epoch
    from tsuzuki_private.tsuzuki_progress_protocol_accounts as a
    where a.user_id = v_user_id
    for share;

    if coalesce(v_epoch, 1) <> 1 then
        raise exception using
            errcode = '0A000',
            message = 'progress protocol epoch is not supported by this client';
    end if;

    -- All inventory aggregates are scoped to auth.uid() and sampled in
    -- one SQL statement. The result is only a momentary observation.
    select
        (select count(*)
         from tsuzuki_private.tsuzuki_progress_protocol_devices as d
         where d.user_id = v_user_id),
        (select count(*)
         from tsuzuki_private.tsuzuki_progress_protocol_devices as d
         where d.user_id = v_user_id
           and d.observed_protocol_epoch = 1
           and d.lease_expires_at > v_now),
        (select count(*)
         from tsuzuki_private.tsuzuki_sync_records as r
         where r.user_id = v_user_id
           and r.domain = 'READING_PROGRESS'),
        (select count(*)
         from tsuzuki_private.tsuzuki_sync_records as r
         where r.user_id = v_user_id
           and r.domain = 'READING_PROGRESS'
           and r.is_deleted),
        (select coalesce(max(e.event_id), 0)
         from tsuzuki_private.tsuzuki_sync_events as e
         where e.user_id = v_user_id
           and e.domain = 'READING_PROGRESS')
    into v_device_count, v_unexpired_count, v_record_count,
         v_tombstone_count, v_cursor;

    -- Counts are for offline manual review only. Explicitly flag server
    -- blind spots to prohibit inferring cutover eligibility from empty data.
    return jsonb_build_object(
        'schemaVersion', 1,
        'protocolEpoch', 1,
        'activeDomain', 'READING_PROGRESS',
        'registeredDeviceCount', v_device_count,
        'unexpiredLegacyDeviceCount', v_unexpired_count,
        'legacyRecordCount', v_record_count,
        'legacyTombstoneCount', v_tombstone_count,
        'legacyEventCursor', v_cursor,
        'historicalDeviceCoverageProven', false,
        'pendingLocalWritesObservable', false,
        'exactProgressEnabled', false,
        'migrationReady', false
    );
end;
$$;

revoke all on function public.sync_preview_progress_rollout()
    from public, anon;
grant execute on function public.sync_preview_progress_rollout()
    to authenticated;
