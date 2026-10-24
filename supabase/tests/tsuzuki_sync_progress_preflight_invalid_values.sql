begin;
select plan(9);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000091'::uuid, 'authenticated', 'authenticated',
  'invalid-value-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000092'::uuid, 'authenticated', 'authenticated',
  'invalid-value-b@example.com', '', now(), now());

insert into tsuzuki_private.tsuzuki_sync_records (user_id, domain, record_id, is_deleted)
select '00000000-0000-0000-0000-000000000091'::uuid, 'READING_PROGRESS',
       record_id, record_id = 'deleted'
from (values ('valid'), ('bad-read'), ('bad-negative'), ('bad-fraction'),
             ('bad-title'), ('bad-variant'), ('bad-overflow'), ('missing'), ('deleted')) as r(record_id);
insert into tsuzuki_private.tsuzuki_sync_records (user_id, domain, record_id, is_deleted)
values ('00000000-0000-0000-0000-000000000092'::uuid,
        'READING_PROGRESS', 'foreign-bad', false);

insert into tsuzuki_private.tsuzuki_sync_fields (
    user_id, domain, record_id, field_path, value, is_removed
)
select '00000000-0000-0000-0000-000000000091'::uuid,
       'READING_PROGRESS', r.record_id, f.field_path, f.value, false
from (values ('valid'), ('bad-read'), ('bad-negative'), ('bad-fraction'),
             ('bad-title'), ('bad-variant'), ('bad-overflow'), ('missing'), ('deleted')) as r(record_id)
cross join (values
    ('canonicalTitleId', '"title"'::jsonb),
    ('chapterKey', '"1"'::jsonb),
    ('read', 'true'::jsonb),
    ('lastPageRead', '3'::jsonb)
) as f(field_path, value)
where not (r.record_id = 'missing' and f.field_path = 'read');

update tsuzuki_private.tsuzuki_sync_fields
set value = case
    when record_id = 'bad-read' then '"true"'::jsonb
    when record_id = 'bad-negative' then '-1'::jsonb
    when record_id = 'bad-fraction' then '2.5'::jsonb
    when record_id = 'bad-overflow' then '9223372036854775808'::jsonb
    when record_id = 'deleted' then '"not boolean"'::jsonb
    when record_id = 'bad-title' then '"   "'::jsonb
    else value
end
where user_id = '00000000-0000-0000-0000-000000000091'::uuid
  and domain = 'READING_PROGRESS'
  and ((record_id in ('bad-read', 'deleted') and field_path = 'read')
    or (record_id in ('bad-negative', 'bad-fraction', 'bad-overflow') and field_path = 'lastPageRead')
    or (record_id = 'bad-title' and field_path = 'canonicalTitleId'));
insert into tsuzuki_private.tsuzuki_sync_fields (
    user_id, domain, record_id, field_path, value, is_removed
) values
 ('00000000-0000-0000-0000-000000000091'::uuid, 'READING_PROGRESS',
  'bad-variant', 'lastVariantEvidenceKey', '{"untrusted":true}'::jsonb, false),
 ('00000000-0000-0000-0000-000000000092'::uuid, 'READING_PROGRESS',
  'foreign-bad', 'read', '"false"'::jsonb, false);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000091', true);
select set_config('request.jwt.claims',
 '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated"}', true);
select is(public.sync_preview_progress_rollout() ->> 'legacyInvalidValueRecordCount',
 '6', 'bad boolean, negative/fractional/overflow page, blank title and variant counted');
select is(public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
 '1', 'missing fields remain separate from malformed values');
select is(public.sync_preview_progress_rollout() ->> 'legacyTombstoneCount',
 '1', 'deleted progress is accounted for separately');
select is(public.sync_preview_progress_rollout() ->> 'migrationReady',
 'false', 'stronger inventory cannot authorize cutover');
reset role;

update tsuzuki_private.tsuzuki_sync_fields
set value = '"title"'::jsonb
where user_id = '00000000-0000-0000-0000-000000000091'::uuid
  and domain = 'READING_PROGRESS' and record_id = 'bad-title'
  and field_path = 'canonicalTitleId';
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000091', true);
select set_config('request.jwt.claims',
 '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated"}', true);
select is(public.sync_preview_progress_rollout() ->> 'legacyInvalidValueRecordCount',
 '5', 'repair decreases invalid-value count without migration');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000092', true);
select set_config('request.jwt.claims',
 '{"sub":"00000000-0000-0000-0000-000000000092","role":"authenticated"}', true);
select is(public.sync_preview_progress_rollout() ->> 'legacyInvalidValueRecordCount',
 '1', 'second account only sees its own malformed records');
select is(public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
 '1', 'second account still sees its own missing fields');
reset role;

select is((select count(*)::integer from tsuzuki_private.tsuzuki_progress_protocol_accounts
 where user_id = '00000000-0000-0000-0000-000000000091'::uuid),
 0, 'read-only inventory does not enroll an account');
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok($$select public.sync_preview_progress_rollout()$$,
 '42501', null, 'anonymous cannot inspect another account');
reset role;

select * from finish();
rollback;
