begin;
select plan(11);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000071'::uuid, 'authenticated', 'authenticated',
  'v2-read-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000072'::uuid, 'authenticated', 'authenticated',
  'v2-read-b@example.com', '', now(), now());

-- Privileged synthetic v2 data: tests the read boundary independently of
-- the existing unconditional v2 write refusal.
insert into tsuzuki_private.tsuzuki_sync_records (user_id, domain, record_id, is_deleted)
values ('00000000-0000-0000-0000-000000000071'::uuid,
        'READING_PROGRESS_V2', 'synthetic-unauthorized-v2', false);
insert into tsuzuki_private.tsuzuki_sync_events (
    user_id, domain, record_id, field_path, operation, value,
    origin_client_id, mutation_id
) values (
    '00000000-0000-0000-0000-000000000071'::uuid,
    'READING_PROGRESS_V2', 'synthetic-unauthorized-v2',
    'read', 'set', 'true'::jsonb, 'synthetic-test-only',
    '71000000-0000-0000-0000-000000000001'::uuid
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000071', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated"}', true
);

select throws_ok(
    $$select public.sync_pull_delta('READING_PROGRESS_V2', 0, 100)$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'unnegotiated epoch1 client cannot pull v2 events'
);
select throws_ok(
    $$select public.sync_get_cursor('READING_PROGRESS_V2')$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'unnegotiated epoch1 client cannot discover v2 cursor'
);
select throws_ok(
    $$select public.sync_snapshot_domain('READING_PROGRESS_V2')$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'unnegotiated epoch1 client cannot snapshot v2 progress'
);
select throws_ok(
    $$select public.sync_pull_delta(' READING_PROGRESS_V2 ', 0, 100)$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'whitespace around the v2 domain cannot bypass protocol refusal'
);
select is(
    public.sync_pull_delta('READING_PROGRESS', 0, 100),
    '[]'::jsonb, 'same epoch1 account still reads its legacy progress'
);
select is(
    public.sync_get_cursor('LIBRARY'),
    0::bigint, 'unrelated library cursor remains readable'
);
reset role;

select is(
    (select count(*)::integer
     from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-000000000071'::uuid),
    0, 'refused v2 reads do not create an account protocol row'
);

-- An elevated epoch is not itself a v2 read grant.
insert into tsuzuki_private.tsuzuki_progress_protocol_accounts (user_id, protocol_epoch)
values ('00000000-0000-0000-0000-000000000071'::uuid, 2);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000071', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated"}', true
);
select throws_ok(
    $$select public.sync_snapshot_domain('READING_PROGRESS_V2')$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'hypothetical epoch2 cannot bypass the never-activated v2 read fence'
);
select is(
    public.sync_snapshot_domain('LIBRARY') ->> 'cursor',
    '0', 'library snapshots remain readable when progress is fenced'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000072', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000072","role":"authenticated"}', true
);
select throws_ok(
    $$select public.sync_get_cursor('READING_PROGRESS_V2')$$,
    '0A000', 'exact progress requires a negotiated protocol rollout',
    'other epoch1 account cannot read even an empty v2 domain'
);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $$select public.sync_snapshot_domain('READING_PROGRESS_V2')$$,
    '42501', null, 'anonymous caller remains unauthorized rather than negotiated'
);
reset role;

select * from finish();
rollback;
