begin;

select plan(17);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
    ('00000000-0000-0000-0000-00000000000e'::uuid,
     'authenticated', 'authenticated', 'epoch-account-a@example.com', '', now(), now()),
    ('00000000-0000-0000-0000-00000000000f'::uuid,
     'authenticated', 'authenticated', 'epoch-account-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000e', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000e","role":"authenticated"}',
    true
);

select is(
    public.sync_get_progress_protocol(),
    '{"schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS","exactProgressEnabled":false,"migrationReady":false}'::jsonb,
    'unenrolled existing accounts discover only the legacy protocol'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000001",
          "originClientId":"old-device","domain":"READING_PROGRESS","baseCursor":0,
          "operations":[{"type":"set","recordId":"chapter-a","fieldPath":"lastPageRead","value":7}]}'::jsonb
    )
    $$,
    'pre-cutover v1 progress remains writable'
);

reset role;

select is(
    (select protocol_epoch from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-00000000000e'::uuid),
    1,
    'legacy write registers a private per-account epoch 1'
);

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_accounts),
    1,
    'read-only protocol discovery does not enroll unrelated accounts'
);

-- Simulate a future privileged atomic cutover WITHOUT enabling v2. The
-- shipped migration contains no RPC to perform this transition.
update tsuzuki_private.tsuzuki_progress_protocol_accounts
set protocol_epoch = 2, changed_at = now()
where user_id = '00000000-0000-0000-0000-00000000000e'::uuid;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000e', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000e","role":"authenticated"}',
    true
);

select throws_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000002",
          "originClientId":"old-device","domain":"READING_PROGRESS","baseCursor":0,
          "operations":[{"type":"set","recordId":"chapter-a","fieldPath":"lastPageRead","value":99}]}'::jsonb
    )
    $$,
    '0A000',
    'legacy progress writes are disabled at this protocol epoch',
    'downgraded v1 device cannot publish new progress after epoch change'
);

select throws_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000001",
          "originClientId":"old-device","domain":"READING_PROGRESS","baseCursor":0,
          "operations":[{"type":"set","recordId":"chapter-a","fieldPath":"lastPageRead","value":7}]}'::jsonb
    )
    $$,
    '0A000',
    'legacy progress writes are disabled at this protocol epoch',
    'already accepted v1 idempotent replay cannot bypass the downgrade fence'
);

select throws_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000003",
          "originClientId":"new-device","domain":"READING_PROGRESS_V2","baseCursor":0,
          "operations":[{"type":"set","recordId":"chapter-v2","fieldPath":"lastPageRead","value":3}]}'::jsonb
    )
    $$,
    '0A000',
    'exact progress requires a negotiated protocol rollout',
    'simulated server epoch 2 does not activate v2 mutations'
);

select throws_ok(
    $$select public.sync_get_progress_protocol()$$,
    '0A000',
    'progress protocol epoch is not supported by this client',
    'old protocol discovery fails closed after epoch transition'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000004",
          "originClientId":"old-device","domain":"LIBRARY","baseCursor":0,
          "operations":[{"type":"set","recordId":"library-a","fieldPath":"status","value":"READING"}]}'::jsonb
    )
    $$,
    'downgrade fence does not block unrelated compatible sync domains'
);

select throws_ok(
    $$
    update tsuzuki_private.tsuzuki_progress_protocol_accounts
    set protocol_epoch = 1
    where user_id = '00000000-0000-0000-0000-00000000000e'::uuid
    $$,
    '42501',
    null,
    'authenticated clients cannot directly change the account epoch'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000f', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000f","role":"authenticated"}',
    true
);

select is(
    public.sync_get_progress_protocol(),
    '{"schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS","exactProgressEnabled":false,"migrationReady":false}'::jsonb,
    'another account still discovers legacy protocol'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{"mutationId":"21000000-0000-0000-0000-000000000005",
          "originClientId":"other-device","domain":"READING_PROGRESS","baseCursor":0,
          "operations":[{"type":"set","recordId":"chapter-b","fieldPath":"lastPageRead","value":4}]}'::jsonb
    )
    $$,
    'an epoch change for one account cannot disable another account'
);

reset role;

select is(
    (select value from tsuzuki_private.tsuzuki_sync_fields
     where user_id = '00000000-0000-0000-0000-00000000000e'::uuid
       and domain = 'READING_PROGRESS' and record_id = 'chapter-a'
       and field_path = 'lastPageRead'),
    '7'::jsonb,
    'rejected v1 write leaves original checkpoint unchanged'
);

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_sync_mutations
     where mutation_id in (
         '21000000-0000-0000-0000-000000000002'::uuid,
         '21000000-0000-0000-0000-000000000003'::uuid
     )),
    0,
    'rejected downgraded and v2 writes allocate no mutation receipts'
);

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_sync_fields
     where domain = 'READING_PROGRESS_V2'),
    0,
    'no v2 remote progress materialized during the simulated epoch transition'
);

select is(
    (select protocol_epoch from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-00000000000f'::uuid),
    1,
    'other account remains enrolled at epoch 1'
);

select is(
    (select protocol_epoch from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-00000000000e'::uuid),
    2,
    'rejected clients cannot roll back the authoritative account epoch'
);

select * from finish();
rollback;
