begin;
select plan(15);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000021'::uuid, 'authenticated', 'authenticated', 'device-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000022'::uuid, 'authenticated', 'authenticated', 'device-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000021', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000000021","role":"authenticated"}', true);

select is(
    public.sync_register_progress_device('22000000-0000-0000-0000-000000000001')
        ->> 'protocolEpoch',
    '1',
    'device heartbeat only acknowledges legacy epoch 1'
);
select is(
    public.sync_register_progress_device('22000000-0000-0000-0000-000000000001')
        ->> 'migrationReady',
    'false',
    'repeating device heartbeat never grants migration authority'
);
select is(
    public.sync_register_progress_device('22000000-0000-0000-0000-000000000002')
        ->> 'exactProgressEnabled',
    'false',
    'a second device cannot enable exact progress'
);
select throws_ok(
    $$select public.sync_register_progress_device('not-a-uuid')$$,
    '22023', 'client identity must be a UUID',
    'malformed device identity is not stored'
);
select is(
    has_table_privilege('authenticated', 'tsuzuki_private.tsuzuki_progress_protocol_devices', 'SELECT'),
    false,
    'authenticated clients cannot enumerate device lease records'
);
select is(
    has_table_privilege('authenticated', 'tsuzuki_private.tsuzuki_progress_protocol_devices', 'UPDATE'),
    false,
    'authenticated clients cannot forge device lease timestamps'
);

reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices),
    2,
    'a repeated device ID is upserted, not duplicated'
);
select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices
     where observed_protocol_epoch = 1
       and device_key = encode(extensions.digest(
           convert_to('22000000-0000-0000-0000-000000000001', 'UTF8'), 'sha256'), 'hex')),
    1,
    'device identity is normalized and stored only as a hash'
);
select is(
    (select bool_and(lease_expires_at > last_seen_at
              and lease_expires_at <= last_seen_at + interval '30 days 1 second')
     from tsuzuki_private.tsuzuki_progress_protocol_devices),
    true,
    'lease lifetimes are server-timestamped and bounded'
);

-- Simulate an independently privileged future account transition. This is
-- test-only manipulation of the private ledger, not an activation API.
update tsuzuki_private.tsuzuki_progress_protocol_accounts
set protocol_epoch = 2
where user_id = '00000000-0000-0000-0000-000000000021'::uuid;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000021', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000000021","role":"authenticated"}', true);

select throws_ok(
    $$select public.sync_register_progress_device('22000000-0000-0000-0000-000000000003')$$,
    '0A000',
    'legacy device registration is disabled at this protocol epoch',
    'old client heartbeat cannot cross an epoch transition'
);
select throws_ok(
    $$select public.sync_register_progress_device('22000000-0000-0000-0000-000000000001')$$,
    '0A000',
    'legacy device registration is disabled at this protocol epoch',
    'existing device replay cannot renew the legacy lease after a transition'
);
reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000021'::uuid),
    2,
    'rejected epoch heartbeat creates no device record'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000022', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000000022","role":"authenticated"}', true);

select is(
    public.sync_register_progress_device('22000000-0000-0000-0000-000000000001')
       ->> 'protocolEpoch',
    '1',
    'same claimed device ID on a second account retains independent v1 state'
);
reset role;
select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000022'::uuid),
    1,
    'device registry is account-scoped and does not collide between accounts'
);

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $select public.sync_register_progress_device('22000000-0000-0000-0000-000000000004')$,
    '42501', null,
    'anonymous clients cannot register a legacy device'
);
reset role;

select * from finish();
rollback;
