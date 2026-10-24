-- Pre-activation: inspect malformed v1 progress values without changing
-- synchronization, accepted cursors, protocol epoch or remote mutations.
-- An expanded server-observable risk inventory is NEVER rollout approval.

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
    v_incomplete_count bigint;
    v_invalid_value_count bigint;
    v_unresolved_conflict_count bigint;
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
        (select count(*)
         from tsuzuki_private.tsuzuki_sync_records as r
         where r.user_id = v_user_id
           and r.domain = 'READING_PROGRESS'
           and not r.is_deleted
           and exists (
               select 1
               from (values ('canonicalTitleId'), ('chapterKey'),
                            ('read'), ('lastPageRead')) as required(field_path)
               where not exists (
                   select 1
                   from tsuzuki_private.tsuzuki_sync_fields as f
                   where f.user_id = r.user_id
                     and f.domain = r.domain
                     and f.record_id = r.record_id
                     and f.field_path = required.field_path
                     and not f.is_removed
               )
           )),
        -- Present-but-invalid v1 values are a distinct hazard from absent
        -- fields. Count active records, not raw fields; categories can overlap.
        (select count(*)
         from tsuzuki_private.tsuzuki_sync_records as r
         where r.user_id = v_user_id
           and r.domain = 'READING_PROGRESS'
           and not r.is_deleted
           and exists (
               select 1
               from tsuzuki_private.tsuzuki_sync_fields as f
               where f.user_id = r.user_id
                 and f.domain = r.domain
                 and f.record_id = r.record_id
                 and not f.is_removed
                 and (
                     (f.field_path in ('canonicalTitleId', 'chapterKey')
                      and (jsonb_typeof(f.value) is distinct from 'string'
                           or nullif(btrim(f.value #>> '{}'), '') is null))
                     or (f.field_path = 'read'
                         and jsonb_typeof(f.value) is distinct from 'boolean')
                     -- Android decodes this field as signed Long.
                     or (f.field_path = 'lastPageRead'
                         and case
                             when jsonb_typeof(f.value) = 'number'
                             then (f.value #>> '{}')::numeric < 0
                               or (f.value #>> '{}')::numeric > 9223372036854775807
                               or (f.value #>> '{}')::numeric <>
                                  trunc((f.value #>> '{}')::numeric)
                             else true
                         end)
                     or (f.field_path = 'lastVariantEvidenceKey'
                         and coalesce(jsonb_typeof(f.value), 'sql_null')
                             not in ('string', 'null'))
                 )
           )),
        (select count(*)
         from tsuzuki_private.tsuzuki_sync_conflicts as c
         where c.user_id = v_user_id
           and c.domain = 'READING_PROGRESS'
           and c.resolved_at is null),
        (select coalesce(max(e.event_id), 0)
         from tsuzuki_private.tsuzuki_sync_events as e
         where e.user_id = v_user_id
           and e.domain = 'READING_PROGRESS')
    into v_device_count, v_unexpired_count, v_record_count,
         v_tombstone_count, v_incomplete_count,
         v_invalid_value_count, v_unresolved_conflict_count, v_cursor;

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
        'legacyIncompleteRecordCount', v_incomplete_count,
        'legacyInvalidValueRecordCount', v_invalid_value_count,
        'unresolvedProgressConflictCount', v_unresolved_conflict_count,
        'legacyEventCursor', v_cursor,
        'historicalDeviceCoverageProven', false,
        'pendingLocalWritesObservable', false,
        'exactProgressEnabled', false,
        'migrationReady', false
    );
end;
$$;

revoke all on function public.sync_preview_progress_rollout() from public, anon;
grant execute on function public.sync_preview_progress_rollout() to authenticated;
