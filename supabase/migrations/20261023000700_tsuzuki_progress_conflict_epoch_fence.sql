-- Completes the post-cutover legacy read/write fence for progress conflicts.
-- Protects only READING_PROGRESS; compatible library conflicts remain usable.
-- All lookups are scoped by auth.uid(). No epoch transition or v2 activation.


create or replace function public.sync_get_mutation_conflicts(
    p_mutation_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public, tsuzuki_private
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

    if p_mutation_id is null then
        raise exception using
            errcode = '22023',
            message = 'mutation ID is required';
    end if;

    -- The mutation's domain is not a parameter, so inspect its own scoped
    -- conflict rows before serving stale v1 conflict metadata post-cutover.
    if exists (
        select 1 from tsuzuki_private.tsuzuki_sync_conflicts as c
        where c.user_id = v_user_id
          and c.local_mutation_id = p_mutation_id
          and c.domain = 'READING_PROGRESS'
    ) then
        perform public.sync_get_progress_protocol();
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'conflictId', c.conflict_id,
                'recordId', c.record_id,
                'fieldPath', c.field_path,
                'kind', c.conflict_type,
                'localValue', c.local_value,
                'remoteValue', c.remote_value
            )
            order by c.conflict_id
        ),
        '[]'::jsonb
    )
    into v_result
    from tsuzuki_private.tsuzuki_sync_conflicts as c
    where c.user_id = v_user_id
      and c.local_mutation_id = p_mutation_id
      and c.resolved_at is null;

    return v_result;
end;
$$;

create or replace function public.sync_ack_conflict(
    p_conflict_id bigint
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_user_id uuid := auth.uid();
    v_conflict_domain text;
begin
    if v_user_id is null then
        raise exception using
            errcode = '42501',
            message = 'authentication required';
    end if;

    if p_conflict_id is null or p_conflict_id <= 0 then
        raise exception using
            errcode = '22023',
            message = 'conflict ID must be positive';
    end if;

    -- Resolve only conflicts owned by this account. A future epoch
    -- transition must not allow an old client to acknowledge v1 conflicts.
    select c.domain into v_conflict_domain
    from tsuzuki_private.tsuzuki_sync_conflicts as c
    where c.conflict_id = p_conflict_id and c.user_id = v_user_id;

    if v_conflict_domain = 'READING_PROGRESS' then
        perform public.sync_get_progress_protocol();
    end if;

    update tsuzuki_private.tsuzuki_sync_conflicts as c
    set resolved_at = coalesce(c.resolved_at, now())
    where c.conflict_id = p_conflict_id
      and c.user_id = v_user_id;

    return found;
end;
$$;

revoke all on function public.sync_get_mutation_conflicts(uuid)
    from public, anon;
grant execute on function public.sync_get_mutation_conflicts(uuid)
    to authenticated;
revoke all on function public.sync_ack_conflict(bigint)
    from public, anon;
grant execute on function public.sync_ack_conflict(bigint)
    to authenticated;
