-- An older client must not read stale v1 progress after an epoch cutover.
-- Preserve authenticated reader contracts, other domains, and all v1 data.
-- This migration exposes no activation API and never rewrites progress.

-- Existing account readers hold FOR SHARE on the authoritative epoch row.
-- A future cutover must hold FOR UPDATE on the same row while it commits
-- verified frontier CAS, approved mapping and a durable receipt.
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
    where a.user_id = v_user_id
    for share;

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

revoke all on function public.sync_pull_delta(text, bigint, integer)
    from public, anon;
grant execute on function public.sync_pull_delta(text, bigint, integer)
    to authenticated;
revoke all on function public.sync_get_cursor(text) from public, anon;
grant execute on function public.sync_get_cursor(text) to authenticated;
revoke all on function public.sync_snapshot_domain(text) from public, anon;
grant execute on function public.sync_snapshot_domain(text) to authenticated;
