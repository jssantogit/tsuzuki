begin;
select plan(12);

insert into auth.users (id, aud, role, email, encrypted_password, created_at, updated_at)
values
 ('00000000-0000-0000-0000-000000000081'::uuid, 'authenticated', 'authenticated',
  'preflight-risk-a@example.com', '', now(), now()),
 ('00000000-0000-0000-0000-000000000082'::uuid, 'authenticated', 'authenticated',
  'preflight-risk-b@example.com', '', now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
    '0', 'new account has no incomplete remote progress records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'unresolvedProgressConflictCount',
    '0', 'new account has no unresolved progress conflicts'
);
reset role;

select is(
    (select count(*)::integer
     from tsuzuki_private.tsuzuki_progress_protocol_accounts
     where user_id = '00000000-0000-0000-0000-000000000081'::uuid),
    0, 'preflight risk inspection never enrolls the account'
);

insert into tsuzuki_private.tsuzuki_sync_records (user_id, domain, record_id, is_deleted)
values
 ('00000000-0000-0000-0000-000000000081'::uuid, 'READING_PROGRESS', 'complete', false),
 ('00000000-0000-0000-0000-000000000081'::uuid, 'READING_PROGRESS', 'missing-page', false),
 ('00000000-0000-0000-0000-000000000081'::uuid, 'READING_PROGRESS', 'tombstone', true),
 ('00000000-0000-0000-0000-000000000082'::uuid, 'READING_PROGRESS', 'foreign-missing', false),
 ('00000000-0000-0000-0000-000000000081'::uuid, 'LIBRARY', 'unrelated', false);
insert into tsuzuki_private.tsuzuki_sync_fields (
    user_id, domain, record_id, field_path, value, is_removed
)
select
    '00000000-0000-0000-0000-000000000081'::uuid,
    'READING_PROGRESS', 'complete', f.path, 'true'::jsonb, false
from (values ('canonicalTitleId'), ('chapterKey'), ('read'), ('lastPageRead')) as f(path);
insert into tsuzuki_private.tsuzuki_sync_fields (
    user_id, domain, record_id, field_path, value, is_removed
)
select
    '00000000-0000-0000-0000-000000000081'::uuid,
    'READING_PROGRESS', 'missing-page', f.path, 'true'::jsonb, false
from (values ('canonicalTitleId'), ('chapterKey'), ('read')) as f(path);

insert into tsuzuki_private.tsuzuki_sync_conflicts (
    user_id, domain, record_id, field_path, conflict_type,
    local_mutation_id, resolved_at
) values
 ('00000000-0000-0000-0000-000000000081'::uuid, 'READING_PROGRESS',
  'complete', 'read', 'FIELD_DIVERGENCE',
  '81000000-0000-0000-0000-000000000001'::uuid, null),
 ('00000000-0000-0000-0000-000000000081'::uuid, 'READING_PROGRESS',
  'complete', 'lastPageRead', 'FIELD_DIVERGENCE',
  '81000000-0000-0000-0000-000000000002'::uuid, now()),
 ('00000000-0000-0000-0000-000000000081'::uuid, 'LIBRARY',
  'unrelated', 'status', 'FIELD_DIVERGENCE',
  '81000000-0000-0000-0000-000000000003'::uuid, null),
 ('00000000-0000-0000-0000-000000000082'::uuid, 'READING_PROGRESS',
  'foreign-missing', 'read', 'FIELD_DIVERGENCE',
  '81000000-0000-0000-0000-000000000004'::uuid, null);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyRecordCount',
    '3', 'risk count excludes unrelated library and foreign records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyTombstoneCount',
    '1', 'deleted remote record is separate from incomplete active records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
    '1', 'active record missing a required field is reported for review'
);
select is(
    public.sync_preview_progress_rollout() ->> 'unresolvedProgressConflictCount',
    '1', 'only unresolved own progress conflicts are counted'
);
select is(
    public.sync_preview_progress_rollout() ->> 'migrationReady',
    'false', 'favorable or unfavorable counts never authorize cutover'
);
reset role;

update tsuzuki_private.tsuzuki_sync_fields
set is_removed = true
where user_id = '00000000-0000-0000-0000-000000000081'::uuid
  and domain = 'READING_PROGRESS' and record_id = 'complete'
  and field_path = 'read';

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
    '2', 'removed required field is counted as incomplete rather than present'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', true);
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000082","role":"authenticated"}', true
);
select is(
    public.sync_preview_progress_rollout() ->> 'legacyIncompleteRecordCount',
    '1', 'second account sees only its own incomplete remote records'
);
select is(
    public.sync_preview_progress_rollout() ->> 'unresolvedProgressConflictCount',
    '1', 'second account sees only its own unresolved progress conflicts'
);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
    $$select public.sync_preview_progress_rollout()$$,
    '42501', null, 'anonymous cannot enumerate private preflight risk counts'
);
reset role;

select * from finish();
rollback;
