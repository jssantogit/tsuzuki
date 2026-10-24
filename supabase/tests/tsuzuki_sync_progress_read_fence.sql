begin;
select plan(15);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000051'::uuid, 'authenticated', 'authenticated',
  'read-fence-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000052'::uuid, 'authenticated', 'authenticated',
  'read-fence-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000051', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000051","role":"authenticated"}', true
);

select is(
    public.sync_pull_delta('READING_PROGRESS', 0, 10),
    '[]'::jsonb, 'unmigrated v1 account can still pull progress delta'
);
select is(
    public.sync_get_cursor('READING_PROGRESS'),
    0::bigint, 'unmigrated v1 account can still inspect progress cursor'
);
select is(
    (public.sync_snapshot_domain('READING_PROGRESS') ->> 'cursor')::bigint,
    0::bigint, 'unmigrated v1 account can still snapshot progress'
);
select is(
    public.sync_pull_delta('LIBRARY', 0, 10),
    '[]'::jsonb, 'library delta retains the legacy contract'
);
reset role;

-- Simulated privileged cutover only. No account migration API exists.
insert into tsuzuki_private.tsuzuki_progress_protocol_accounts (user_id, protocol_epoch)
values ('00000000-0000-0000-0000-000000000051'::uuid, 2);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000051', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000051","role":"authenticated"}', true
);

select throws_ok(
    $$select public.sync_pull_delta('READING_PROGRESS', 0, 10)$$,
    '0A000', 'progress protocol epoch is not supported by this client',
    'old client cannot fetch stale progress events after cutover'
);
select throws_ok(
    $$select public.sync_get_cursor('READING_PROGRESS')$$,
    '0A000', 'progress protocol epoch is not supported by this client',
    'old client cannot fetch stale progress cursor after cutover'
);
select throws_ok(
    $$select public.sync_snapshot_domain('READING_PROGRESS')$$,
    '0A000', 'progress protocol epoch is not supported by this client',
    'old client cannot rebuild stale progress snapshot after cutover'
);
select is(
    public.sync_pull_delta('LIBRARY', 0, 10),
    '[]'::jsonb, 'other domain delta remains available after epoch change'
);
select is(
    public.sync_get_cursor('LIBRARY'),
    0::bigint, 'other domain cursor remains available after epoch change'
);
select is(
    (public.sync_snapshot_domain('LIBRARY') ->> 'cursor')::bigint,
    0::bigint, 'other domain snapshot remains available after epoch change'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000052', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000052","role":"authenticated"}', true
);
select is(
    public.sync_pull_delta('READING_PROGRESS', 0, 10),
    '[]'::jsonb, 'second account can still fetch v1 delta'
);
select is(
    public.sync_get_cursor('READING_PROGRESS'),
    0::bigint, 'second account remains at independent v1 frontier'
);
select is(
    (public.sync_snapshot_domain('READING_PROGRESS') ->> 'cursor')::bigint,
    0::bigint, 'second account can still snapshot v1 progress'
);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $$select public.sync_pull_delta('READING_PROGRESS', 0, 10)$$,
    '42501', null, 'anonymous client still cannot query progress delta'
);
select throws_ok(
    $$select public.sync_snapshot_domain('READING_PROGRESS')$$,
    '42501', null, 'anonymous client still cannot query progress snapshot'
);
reset role;

select * from finish();
rollback;
