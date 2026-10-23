begin;
select plan(9);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000041'::uuid, 'authenticated', 'authenticated',
  'bounded-device-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000042'::uuid, 'authenticated', 'authenticated',
  'bounded-device-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000041', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000041","role":"authenticated"}', true
);

-- The loop only observes a synthetic user. No real device IDs or history.
do $$
declare v_i integer;
begin
    for v_i in 1..64 loop
        perform public.sync_register_progress_device(
            '46000000-0000-0000-0000-' || lpad(v_i::text, 12, '0')
        );
    end loop;
end;
$;

reset role;
select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000041'::uuid),
    64, 'one synthetic account can register at most 64 distinct device IDs'
);
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000041', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000041","role":"authenticated"}', true
);
select throws_ok(
    $$select public.sync_register_progress_device('46000000-0000-0000-0000-000000000065')$$,
    '54000', 'progress device registration limit exceeded',
    'new identity above cap is refused before an insert'
);
select is(
    public.sync_register_progress_device('46000000-0000-0000-0000-000000000001')
      ->> 'migrationReady',
    'false', 'existing registered devices can renew at the cap without activating v2'
);
reset role;

select is(
    (select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000041'::uuid),
    64, 'denied heartbeat leaves the device registry unchanged'
);

-- Emulate a delayed heartbeat with an older server timestamp by advancing
-- one existing lease in the test. Renewing it must never move time backwards.
update tsuzuki_private.tsuzuki_progress_protocol_devices
set last_seen_at = now() + interval '20 days',
    lease_expires_at = now() + interval '50 days'
where user_id = '00000000-0000-0000-0000-000000000041'::uuid
  and device_key = encode(
      extensions.digest(convert_to('46000000-0000-0000-0000-000000000001', 'UTF8'), 'sha256'),
      'hex'
  );

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000041', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000041","role":"authenticated"}', true
);
select is(
    public.sync_register_progress_device('46000000-0000-0000-0000-000000000001')
      ->> 'protocolEpoch',
    '1', 'out-of-order renewal retains the existing v1 protocol'
);
select ok(
    (public.sync_register_progress_device('46000000-0000-0000-0000-000000000001')
        ->> 'leaseExpiresAt')::timestamptz > now() + interval '49 days',
    'heartbeat response acknowledges the persisted monotonic lease'
);
reset role;

select ok(
    (select last_seen_at > now() + interval '19 days'
     from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000041'::uuid
       and device_key = encode(
           extensions.digest(convert_to('46000000-0000-0000-0000-000000000001', 'UTF8'), 'sha256'),
           'hex'
       )),
    'older heartbeat cannot regress last-seen timestamp'
);
select ok(
    (select lease_expires_at > now() + interval '49 days'
     from tsuzuki_private.tsuzuki_progress_protocol_devices
     where user_id = '00000000-0000-0000-0000-000000000041'::uuid
       and device_key = encode(
           extensions.digest(convert_to('46000000-0000-0000-0000-000000000001', 'UTF8'), 'sha256'),
           'hex'
       )),
    'older heartbeat cannot shorten an already accepted lease'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000042', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000042","role":"authenticated"}', true
);
select is(
    public.sync_register_progress_device('46000000-0000-0000-0000-000000000065')
      ->> 'protocolEpoch',
    '1', 'another account can register the same UUID despite first-account cap'
);
reset role;

select * from finish();
rollback;
