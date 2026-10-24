-- Pre-activation: block all v2 reads; add only server-observable legacy risks.
-- This does not approve a migration, change the epoch or rewrite progress.

create or replace function public.sync_pull_delta(
    p_domain text,
    p_since_event_id bigint,
    p_limit integer default 500
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_result jsonb;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null
        or p_since_event_id is null
        or p_since_event_id < 0
        or p_limit is null
        or p_limit < 1
        or p_limit > 1000
    then
        raise exception using
            errcode = '22023',
            message = 'invalid delta request';
    end if;

    -- Refuse legacy progress reads after an account's authoritative epoch
    -- changes. Discovery holds the ledger FOR SHARE until the statement ends.
    -- v2 remains unnegotiated for every account epoch.
    if btrim(p_domain) = 'READING_PROGRESS_V2' then
        raise exception using
            errcode = '0A000',
            message = 'exact progress requires a negotiated protocol rollout';
    end if;

    if btrim(p_domain) = 'READING_PROGRESS' then
        perform public.sync_get_progress_protocol();
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'eventId', delta.event_id,
                'domain', delta.domain,
                'recordId', delta.record_id,
                'fieldPath', delta.field_path,
                'operation', delta.operation,
                'value', delta.value,
                'originClientId', delta.origin_client_id,
                'mutationId', delta.mutation_id,
                'createdAt', delta.created_at
            )
            order by delta.event_id
        ),
        '[]'::jsonb
    )
    into v_result
    from (
        select
            e.event_id,
            e.domain,
            e.record_id,
            e.field_path,
            e.operation,
            e.value,
            e.origin_client_id,
            e.mutation_id,
            e.created_at
        from tsuzuki_private.tsuzuki_sync_events as e
        where e.user_id = v_user_id
          and e.domain = p_domain
          and e.event_id > p_since_event_id
        order by e.event_id
        limit p_limit
    ) as delta;

    return v_result;
end;
$$;

create or replace function public.sync_get_cursor(
    p_domain text
)
returns bigint
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_cursor bigint;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null then
        raise exception using
            errcode = '22023',
            message = 'domain is required';
    end if;

    -- Refuse legacy progress reads after an account's authoritative epoch
    -- changes. Discovery holds the ledger FOR SHARE until the statement ends.
    -- v2 remains unnegotiated for every account epoch.
    if btrim(p_domain) = 'READING_PROGRESS_V2' then
        raise exception using
            errcode = '0A000',
            message = 'exact progress requires a negotiated protocol rollout';
    end if;

    if btrim(p_domain) = 'READING_PROGRESS' then
        perform public.sync_get_progress_protocol();
    end if;

    select coalesce(max(e.event_id), 0)
    into v_cursor
    from tsuzuki_private.tsuzuki_sync_events as e
    where e.user_id = v_user_id
      and e.domain = p_domain;

    return v_cursor;
end;
$$;

create or replace function public.sync_snapshot_domain(
    p_domain text
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_snapshot jsonb;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if nullif(btrim(p_domain), '') is null then
        raise exception using
            errcode = '22023',
            message = 'domain is required';
    end if;

    -- Refuse legacy progress reads after an account's authoritative epoch
    -- changes. Discovery holds the ledger FOR SHARE until the statement ends.
    -- v2 remains unnegotiated for every account epoch.
    if btrim(p_domain) = 'READING_PROGRESS_V2' then
        raise exception using
            errcode = '0A000',
            message = 'exact progress requires a negotiated protocol rollout';
    end if;

    if btrim(p_domain) = 'READING_PROGRESS' then
        perform public.sync_get_progress_protocol();
    end if;

    select jsonb_build_object(
        'cursor',
        snapshot_cursor.event_cursor,
        'records',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'recordId',
                        r.record_id,
                        'isDeleted',
                        r.is_deleted,
                        'fields',
                        coalesce(
                            (
                                select jsonb_object_agg(
                                    f.field_path,
                                    f.value
                                    order by f.field_path
                                )
                                from tsuzuki_private.tsuzuki_sync_fields as f
                                where f.user_id = v_user_id
                                  and f.domain = p_domain
                                  and f.record_id = r.record_id
                                  and not f.is_removed
                                  and f.last_event_id <= snapshot_cursor.event_cursor
                            ),
                            '{}'::jsonb
                        )
                    )
                    order by r.record_id
                )
                from tsuzuki_private.tsuzuki_sync_records as r
                where r.user_id = v_user_id
                  and r.domain = p_domain
                  and r.delete_last_event_id <= snapshot_cursor.event_cursor
            ),
            '[]'::jsonb
        )
    )
    into v_snapshot
    from (
        select coalesce(max(e.event_id), 0) as event_cursor
        from tsuzuki_private.tsuzuki_sync_events as e
        where e.user_id = v_user_id
          and e.domain = p_domain
    ) as snapshot_cursor;

    return v_snapshot;
end;
$$;

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
         v_unresolved_conflict_count, v_cursor;

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
        'unresolvedProgressConflictCount', v_unresolved_conflict_count,
        'legacyEventCursor', v_cursor,
        'historicalDeviceCoverageProven', false,
        'pendingLocalWritesObservable', false,
        'exactProgressEnabled', false,
        'migrationReady', false
    );
end;
$$;

revoke all on function public.sync_pull_delta(text, bigint, integer) from public, anon;
grant execute on function public.sync_pull_delta(text, bigint, integer) to authenticated;
revoke all on function public.sync_get_cursor(text) from public, anon;
grant execute on function public.sync_get_cursor(text) to authenticated;
revoke all on function public.sync_snapshot_domain(text) from public, anon;
grant execute on function public.sync_snapshot_domain(text) to authenticated;
revoke all on function public.sync_preview_progress_rollout() from public, anon;
grant execute on function public.sync_preview_progress_rollout() to authenticated;
