begin;

select plan(10);

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

select throws_ok(
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
    '0A000',
    'exact progress requires a negotiated protocol rollout',
    'v2 progress RPC rejects writes until account migration is coordinated'
);

select throws_ok(
    $
    select public.sync_apply_mutation_batch(
        '{
            "mutationId":"20000000-0000-0000-0000-000000000003",
            "originClientId":"spoofed-client",
            "domain":"  READING_PROGRESS_V2  ",
            "baseCursor":0,
            "operations":[
                {"type":"set","recordId":"chapter-key","fieldPath":"lastPageRead","value":99}
            ]
        }'::jsonb
    );
    $,
    '0A000',
    'exact progress requires a negotiated protocol rollout',
    'whitespace around the domain cannot bypass the v2 rollout guard'
);

select is(
    (
        select count(*)::integer
        from tsuzuki_private.tsuzuki_sync_mutations
        where mutation_id = '20000000-0000-0000-0000-000000000003'::uuid
    ),
    0,
    'whitespace bypass attempt leaves no idempotency record'
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
    'v2 refusal leaves legacy progress untouched'
);

select is(
    (
        select count(*)::integer
        from tsuzuki_private.tsuzuki_sync_fields
        where domain = 'READING_PROGRESS_V2'
    ),
    0,
    'v2 refusal leaves no remote progress fields'
);

select is(
    (
        select count(*)::integer
        from tsuzuki_private.tsuzuki_sync_mutations
        where mutation_id = '20000000-0000-0000-0000-000000000002'::uuid
    ),
    0,
    'v2 refusal creates no pending or idempotency record'
);

select is(
    jsonb_array_length(public.sync_pull_delta('READING_PROGRESS', 0, 100)),
    1,
    'legacy client still pulls legacy-domain events'
);

select is(
    public.sync_get_progress_protocol(),
    '{"schemaVersion":1,"protocolEpoch":1,"activeDomain":"READING_PROGRESS","exactProgressEnabled":false,"migrationReady":false}'::jsonb,
    'server advertises only the safe legacy protocol with no v2 activation'
);

select is(
    public.sync_get_cursor('READING_PROGRESS'),
    1::bigint,
    'v2 refusal does not advance the legacy event cursor'
);

select * from finish();
rollback;
