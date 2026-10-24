"""Disposable SQLite experiment over actual DDL. No product database is opened."""
import argparse
import hashlib
import json
import pathlib
import re
import sqlite3


def run(repo):
    root = repo / 'data/src/main/sqldelight/tachiyomi/data'
    names = ['canonical_chapters', 'chapter_variants', 'chapter_progress',
             'chapter_history', 'canonical_downloads', 'chapter_update_state',
             'mihon_projection_queue', 'chapter_evidence', 'chapter_overrides']
    c = sqlite3.connect(':memory:')
    c.execute('PRAGMA foreign_keys=ON')
    c.executescript('CREATE TABLE tsuzuki_titles(id TEXT PRIMARY KEY);'
                    'CREATE TABLE tsuzuki_source_mappings(id TEXT PRIMARY KEY);')
    hashes = {}
    for name in names:
        path = root / ('tsuzuki_' + name + '.sq')
        source = path.read_text(encoding='utf8')
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        ddl = re.search(r'CREATE TABLE .*?\n\);', source, re.S).group()
        ddl = re.sub(r' AS (Boolean|[\w.]+)', '', ddl)
        c.execute(ddl)
    chapter = 'tsuzuki:count:fixture-title:37'
    c.execute("INSERT INTO tsuzuki_titles VALUES ('fixture-title')")
    c.execute("INSERT INTO tsuzuki_source_mappings VALUES ('fixture-map')")
    # Fill required columns from SQLite introspection, then explicit relationship values.
    def seed(name, overrides):
        table = 'tsuzuki_' + name
        values = {}
        for _, col, typ, required, default, primary in c.execute('PRAGMA table_info('+table+')'):
            if required and default is None:
                values[col] = 1 if any(x in typ.upper() for x in ['INTEGER', 'REAL']) else '{}'
        values.update(overrides)
        c.execute('INSERT INTO '+table+' ('+','.join(values)+') VALUES ('+
                  ','.join('?' for _ in values)+')', list(values.values()))
    seed('canonical_chapters', {'id':chapter, 'canonical_title_id':'fixture-title',
         'display_number':'37', 'type':'REGULAR', 'base_number':37,
         'confidence':0, 'confirmation_state':'PROVISIONAL'})
    seed('chapter_variants', {'id':'fixture-variant', 'canonical_chapter_id':chapter,
         'source_mapping_id':'fixture-map', 'source_chapter_id':'source-37'})
    for name in ['chapter_progress', 'chapter_history', 'canonical_downloads',
                 'chapter_update_state', 'mihon_projection_queue']:
        seed(name, {'canonical_chapter_id':chapter,
                    **({'canonical_title_id':'fixture-title'} if name=='chapter_update_state' else {})})
    seed('chapter_evidence', {'id':'fixture-evidence', 'canonical_title_id':'fixture-title',
         'mapped_canonical_chapter_id':chapter})
    seed('chapter_overrides', {'id':'fixture-override', 'canonical_title_id':'fixture-title',
         'canonical_chapter_key':'fixture-portable-key'})
    source = (root/'tsuzuki_canonical_chapters.sq').read_text(encoding='utf8')
    query = re.search(r'countTsuzukiCanonicalChapterProtectedState:\s*(.*?);',source,re.S).group(1)
    protected = c.execute(query, {'canonicalChapterId':chapter}).fetchone()[0]
    def counts():
        return {name:c.execute('SELECT count(*) FROM tsuzuki_'+name).fetchone()[0] for name in names}
    before = counts()
    c.execute("UPDATE tsuzuki_canonical_chapters SET confidence=1, confirmation_state='CONFIRMED' WHERE id=?",(chapter,))
    assert counts()==before
    assert c.execute('SELECT canonical_chapter_id FROM tsuzuki_chapter_progress').fetchone()[0]==chapter
    c.execute('SAVEPOINT disposal')
    c.execute('DELETE FROM tsuzuki_canonical_chapters WHERE id=?',(chapter,))
    after = counts()
    mapped = c.execute('SELECT mapped_canonical_chapter_id FROM tsuzuki_chapter_evidence').fetchone()[0]
    assert all(after[n]==0 for n in names[:7])
    assert after['chapter_evidence']==1 and mapped is None and after['chapter_overrides']==1
    c.execute('ROLLBACK TO disposal')
    assert counts()==before and protected==5
    return {'sqliteVersion':sqlite3.sqlite_version, 'database':':memory:',
        'sourceHashes':hashes, 'stubbedParents':['tsuzuki_titles','tsuzuki_source_mappings'],
        'excluded':'SQLDelight adapters, triggers, indexes, Kotlin, Android, sync and actual backup runtime',
        'protectedState':protected, 'before':before, 'metadataPromotionPreservesReferences':True,
        'afterHypotheticalDelete':after, 'evidenceMappingAfterDelete':mapped,
        'savepointRollbackPreservesAllRows':True, 'assertions':'PASS'}


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--repo',type=pathlib.Path,required=True)
    p.add_argument('--output',type=pathlib.Path)
    args=p.parse_args()
    result=json.dumps(run(args.repo),ensure_ascii=False,indent=2)
    if args.output: args.output.write_text(result,encoding='utf8')
    print(result)
