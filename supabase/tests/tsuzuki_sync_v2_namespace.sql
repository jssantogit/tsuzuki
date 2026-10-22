begin;

select plan(5);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values (
    '00000000-0000-0000-0000-00000000000d'::uuid,
    'authenticated', 'authenticated', 'v2-namespace@example.com', '', now(), now()
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000d', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-00000000000d","role":"authenticated"}',
    true
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{
            "mutationId":"20000000-0000-0000-0000-000000000001",
            "originClientId":"legacy-device",
            "domain":"READING_PROGRESS",
            "baseCursor":0,
            "operations":[
                {"type":"set","recordId":"chapter-key","fieldPath":"lastPageRead","value":7}
            ]
        }'::jsonb
    );
    $$,
    'legacy progress remains writable under the original domain'
);

select lives_ok(
    $$
    select public.sync_apply_mutation_batch(
        '{
            "mutationId":"20000000-0000-0000-0000-000000000002",
            "originClientId":"exact-device",
            "domain":"READING_PROGRESS_V2",
            "baseCursor":0,
            "operations":[
                {"type":"set","recordId":"chapter-key","fieldPath":"lastPageRead","value":3}
            ]
        }'::jsonb
    );
    $$,
    'exact progress uses an independent remote domain'
);

select is(
    (
        select value
        from tsuzuki_private.tsuzuki_sync_fields
        where domain = 'READING_PROGRESS'
          and record_id = 'chapter-key'
          and field_path = 'lastPageRead'
    ),
    '7'::jsonb,
    'v2 update cannot overwrite v1 progress even with an identical record ID'
);

select is(
    (
        select value
        from tsuzuki_private.tsuzuki_sync_fields
        where domain = 'READING_PROGRESS_V2'
          and record_id = 'chapter-key'
          and field_path = 'lastPageRead'
    ),
    '3'::jsonb,
    'v1 update cannot overwrite the independently stored v2 progress'
);

select is(
    jsonb_array_length(public.sync_pull_delta('READING_PROGRESS', 0, 100)),
    1,
    'legacy client pulls only legacy-domain events, never exact-v2 events'
);

select * from finish();
rollback;
