begin;
select plan(18);

-- Dedicated synthetic accounts: no private user snapshots.
insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000031'::uuid, 'authenticated', 'authenticated',
  'preview-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000032'::uuid, 'authenticated', 'authenticated',
  'preview-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000031', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}', true
);

select is(
    public.sync_preview_progress_rollout() ->> 'protocolEpoch',
    '1', 'preflight observes legacy epoch, never an activation result'
);
select is(
    public.sync_preview_progress_rollout() ->> 'migrationReady',
    'false', 'no amount of inventory can authorize a migration'
);
select is(
    public.sync_preview_progress_rollout() ->> 'exactProgressEnabled',
    'false', 'preview never enables exact reading progress'
);
select is(
    public.sync_preview_progress_rollout() ->> 'historicalDeviceCoverageProven',
    'false', 'observed clients cannot establish coverage of historical devices'
);
select is(
    public.sync_preview_progress_rollout() ->> 'pendingLocalWritesObservable',
    'false', 'server cannot assert there are no offline queued writes'
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyRecordCount',
    '0', 'new account has no accepted progress records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'registeredDeviceCount',
    '0', 'new account has no observed device registrations'
);
reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-000000000031'::uuid),
    0, 'read-only preflight never enrolls a new account'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000031', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}', true
);
do $$
begin
    perform public.sync_register_progress_device('33000000-0000-0000-0000-000000000001');
    perform public.sync_register_progress_device('33000000-0000-0000-0000-000000000002');
end;
$$;
select is(
    public.sync_preview_progress_rollout() ->> 'registeredDeviceCount',
    '2', 'preflight counts only registered devices for its account'
);
select is(
    public.sync_preview_progress_rollout() ->> 'unexpiredLegacyDeviceCount',
    '2', 'unexpired v1 leases count as observed, not approved'
);
reset role;

insert into tsuzuki_private.tsuzuki_sync_records (
    user_id, domain, record_id, is_deleted
) values
('00000000-0000-0000-0000-000000000031'::uuid, 'READING_PROGRESS', 'chapter-a', false),
('00000000-0000-0000-0000-000000000031'::uuid, 'READING_PROGRESS', 'chapter-b', true),
('00000000-0000-0000-0000-000000000032'::uuid, 'READING_PROGRESS', 'chapter-c', false);

insert into tsuzuki_private.tsuzuki_sync_events (
    user_id, domain, record_id, field_path, operation,
    value, origin_client_id, mutation_id
) values
('00000000-0000-0000-0000-000000000031'::uuid, 'READING_PROGRESS', 'chapter-a',
 'status', 'set', '"READING"'::jsonb, 'synthetic', '44000000-0000-0000-0000-000000000001'),
('00000000-0000-0000-0000-000000000031'::uuid, 'READING_PROGRESS', 'chapter-b',
 null, 'delete', null, 'synthetic', '44000000-0000-0000-0000-000000000002'),
('00000000-0000-0000-0000-000000000031'::uuid, 'LIBRARY', 'library-a',
 'status', 'set', '"reading"'::jsonb, 'synthetic', '44000000-0000-0000-0000-000000000003');

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000031', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyRecordCount',
    '2', 'accepted v1 records are counted without including other accounts'
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyTombstoneCount',
    '1', 'tombstones are surfaced as an explicit migration risk'
);
select is(
    (public.sync_preview_progress_rollout() ->> 'legacyEventCursor')::bigint,
    (select max(event_id) from tsuzuki_private.tsuzuki_sync_events
     where user_id = '00000000-0000-0000-0000-000000000031'::uuid
       and domain = 'READING_PROGRESS'),
    'frontier only includes accepted v1 progress events, not library events'
);
select is(
    (public.sync_preview_progress_rollout() ? 'deviceKeys'),
    false, 'preview cannot enumerate or disclose private device identifiers'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000032', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000032","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyRecordCount',
    '1', 'second account sees only its own progress records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'registeredDeviceCount',
    '0', 'second account cannot enumerate the first account devices'
);
reset role;

update tsuzuki_private.tsuzuki_progress_protocol_accounts
set protocol_epoch = 2
where user_id = '00000000-0000-0000-0000-000000000031'::uuid;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000031', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000031","role":"authenticated"}', true
);
select throws_ok(
    $$select public.sync_preview_progress_rollout()$$,
    '0A000', 'progress protocol epoch is not supported by this client',
    'future epoch cannot be misrepresented as a v1 migration preflight'
);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $$select public.sync_preview_progress_rollout()$$,
    '42501', null, 'anonymous callers cannot access progress inventory'
);
reset role;

select * from finish();
rollback;
