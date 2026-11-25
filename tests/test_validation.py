import hashlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from orchestrator import core

@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    monkeypatch.setattr(core, 'ROOT', tmp_path)
    monkeypatch.setenv('LLM_MODE','mock')

@pytest.mark.parametrize('scenario',['greenfield','brownfield','ambiguous'])
def test_candidate_lifecycle(scenario):
    s={'run_id':scenario,'scenario':scenario,'requirement':'fixture','version':1}
    s.update({stage:core.generate_batches(stage,s) for stage in ('code','tests')})
    result=core.verify(s)
    assert result['pending_external_test'] and not result['passed']
    candidate=core.folder(s)/'candidate'
    assert core.tree_hash(candidate)==result['candidate_hash']
    archive=Path(core.archive_candidate(s,'test'))
    assert not candidate.exists() and core.tree_hash(archive)==result['candidate_hash']

@pytest.mark.parametrize('log,valid', [('Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS',True),('Tests run: 2, Failures: 1, Errors: 0, Skipped: 0\nBUILD SUCCESS',False),('BUILD SUCCESS',False),('Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD FAILURE',False)])
def test_log_gate(log,valid):
    s={'run_id':'evidence'}; p=core.folder(s); p.mkdir(); raw=log.encode(); (p/'verification.log').write_bytes(raw)
    assert core.validate_test_log(s,{'log_sha256':hashlib.sha256(raw).hexdigest()}) is valid
    assert not core.validate_test_log(s,{'log_sha256':'wrong'})

def test_parallel_audit():
    import json
    s={'run_id':'parallel'}
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda i:core.audit(s,'test',{'i':i}),range(40)))
    assert len([json.loads(x) for x in (core.folder(s)/'audit.jsonl').read_text().splitlines()])==40

def test_hash_ignores_build_output(tmp_path):
    (tmp_path/'a').write_text('source'); before=core.tree_hash(tmp_path)
    (tmp_path/'target').mkdir(); (tmp_path/'target'/'a').write_text('build')
    assert core.tree_hash(tmp_path)==before
    (tmp_path/'a').write_text('changed'); assert core.tree_hash(tmp_path)!=before
