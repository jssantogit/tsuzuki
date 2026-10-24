begin;
select plan(13);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000061'::uuid, 'authenticated', 'authenticated',
  'conflict-epoch-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000062'::uuid, 'authenticated', 'authenticated',
  'conflict-epoch-b@example.com', '', now(), now());

insert into tsuzuki_private.tsuzuki_sync_conflicts (
    user_id, domain, record_id, field_path, conflict_type,
    local_mutation_id
) values
 ('00000000-0000-0000-0000-000000000061'::uuid, 'READING_PROGRESS',
  'chapter-progress-a', 'read', 'FIELD_DIVERGENCE',
  '66000000-0000-0000-0000-000000000001'::uuid),
 ('00000000-0000-0000-0000-000000000061'::uuid, 'LIBRARY',
  'library-progress-a', 'status', 'FIELD_DIVERGENCE',
  '66000000-0000-0000-0000-000000000002'::uuid),
 ('00000000-0000-0000-0000-000000000062'::uuid, 'READING_PROGRESS',
  'chapter-progress-b', 'read', 'FIELD_DIVERGENCE',
  '66000000-0000-0000-0000-000000000003'::uuid);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000061', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000061","role":"authenticated"}', true
);
select is(
    jsonb_array_length(public.sync_get_mutation_conflicts(
        '66000000-0000-0000-0000-000000000001'::uuid)),
    1, 'legacy epoch1 can inspect its own progress conflict'
);
select is(
    public.sync_get_mutation_conflicts(
        '66000000-0000-0000-0000-000000000003'::uuid),
    '[]'::jsonb, 'an account cannot inspect foreign progress conflicts'
);
reset role;

-- This is test-only privileged epoch manipulation, never an activation path.
insert into tsuzuki_private.tsuzuki_progress_protocol_accounts (user_id, protocol_epoch)
values ('00000000-0000-0000-0000-000000000061'::uuid, 2);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000061', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000061","role":"authenticated"}', true
);
select throws_ok(
    $$select public.sync_get_mutation_conflicts('66000000-0000-0000-0000-000000000001'::uuid)$$,
    '0A000', 'progress protocol epoch is not supported by this client',
    'old client cannot inspect stale v1 conflicts after cutover'
);
select throws_ok(
    format('select public.sync_ack_conflict(%s)', (
        select conflict_id from tsuzuki_private.tsuzuki_sync_conflicts
        where user_id = '00000000-0000-0000-0000-000000000061'::uuid
          and domain = 'READING_PROGRESS'
    )),
    '0A000', 'progress protocol epoch is not supported by this client',
    'old client cannot acknowledge v1 conflict after cutover'
);
reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_sync_conflicts
     where user_id = '00000000-0000-0000-0000-000000000061'::uuid
       and domain = 'READING_PROGRESS' and resolved_at is null),
    1, 'refused acknowledgment preserves the unresolved legacy conflict'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000061', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000061","role":"authenticated"}', true
);
select is(
    jsonb_array_length(public.sync_get_mutation_conflicts(
        '66000000-0000-0000-0000-000000000002'::uuid)),
    1, 'library conflict reads remain available after progress cutover'
);
select is(
    public.sync_ack_conflict((
        select conflict_id from tsuzuki_private.tsuzuki_sync_conflicts
        where user_id = '00000000-0000-0000-0000-000000000061'::uuid
          and domain = 'LIBRARY'
    )),
    true, 'library conflict acknowledgment is still permitted'
);
reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_sync_conflicts
     where user_id = '00000000-0000-0000-0000-000000000061'::uuid
       and domain = 'LIBRARY' and resolved_at is not null),
    1, 'allowed library acknowledgment writes only its own conflict'
);

select set_config(
    'tsuzuki_test.foreign_conflict_id',
    (select conflict_id::text from tsuzuki_private.tsuzuki_sync_conflicts
     where user_id = '00000000-0000-0000-0000-000000000062'::uuid),
    true
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000061', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000061","role":"authenticated"}', true
);
select is(
    public.sync_ack_conflict(
        current_setting('tsuzuki_test.foreign_conflict_id')::bigint
    ),
    false, 'foreign-account conflict ID cannot be acknowledged'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000062', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000062","role":"authenticated"}', true
);
select is(
    jsonb_array_length(public.sync_get_mutation_conflicts(
        '66000000-0000-0000-0000-000000000003'::uuid)),
    1, 'independent epoch1 account can read its own progress conflict'
);
select is(
    public.sync_ack_conflict((
        select conflict_id from tsuzuki_private.tsuzuki_sync_conflicts
        where user_id = '00000000-0000-0000-0000-000000000062'::uuid
    )),
    true, 'independent epoch1 account can resolve its own progress conflict'
);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $$select public.sync_get_mutation_conflicts('66000000-0000-0000-0000-000000000001'::uuid)$$,
    '42501', null, 'anonymous cannot inspect progress conflicts'
);
select throws_ok(
    $$select public.sync_ack_conflict(1)$$,
    '42501', null, 'anonymous cannot resolve conflicts'
);
reset role;

select * from finish();
rollback;
