import hashlib
import time
import sqlite3
import pytest
from orchestrator import core
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import Command
from orchestrator.graph import build

@pytest.fixture
def workflow(tmp_path,monkeypatch):
    monkeypatch.setattr(core,'ROOT',tmp_path)
    monkeypatch.setenv('LLM_MODE','mock'); monkeypatch.setenv('MAX_REPLANS','1')
    db=sqlite3.connect(str(tmp_path/'checkpoint.sqlite'),check_same_thread=False)
    graph=build(SqliteSaver(db)); config={'configurable':{'thread_id':'fixture'},'recursion_limit':60}
    def start(scenario='greenfield'):
        return graph.invoke(dict(run_id='fixture',scenario=scenario,requirement='fixture',started=time.time(),version=1,replans=0),config)
    def resume(answer): return graph.invoke(Command(resume=answer),config)
    yield start,resume,config,tmp_path
    db.close()

def gate(result,kind):
    value=result['__interrupt__'][0].value
    assert value['type']==kind
    return value

def approve(result,kind): return {'approve':True,'hash':gate(result,kind)['hash']}

def evidence(result,root):
    h=gate(result,'external_verification')['hash']
    raw=b'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS\n'
    (root/'fixture'/'verification.log').write_bytes(raw)
    return {'passed':True,'hash':h,'log_sha256':hashlib.sha256(raw).hexdigest(),'evidence':'verification.log'}

def test_lifecycle_and_join(workflow):
    start,resume,_,root=workflow
    r=resume(approve(start(),'design_approval'))
    assert r['code']['files'] and r['tests']['files']
    r=resume(evidence(r,root)); r=resume(approve(r,'release_approval'))
    assert r['status']=='release_ready'

def test_invalid_approval(workflow):
    start,resume,_,root=workflow; start()
    assert resume({'approve':True,'hash':'wrong'})['status']=='safe_stop'
    assert not (root/'fixture'/'candidate').exists()

def test_replan_exhaustion(workflow):
    start,resume,_,root=workflow
    r=resume(approve(start(),'design_approval')); gate(r,'external_verification')
    r=resume({'passed':False}); assert r['replans']==1
    r=resume(approve(r,'design_approval')); gate(r,'external_verification')
    assert resume({'passed':False})['status']=='safe_stop'
    assert list((root/'fixture'/'archives').iterdir())

def test_candidate_tamper(workflow):
    start,resume,_,root=workflow
    r=resume(approve(start(),'design_approval')); r=resume(evidence(r,root))
    answer=approve(r,'release_approval'); (root/'fixture'/'candidate'/'tampered.txt').write_text('changed')
    assert resume(answer)['status']=='safe_stop'

def test_checkpoint_reopen(workflow):
    start,_,config,root=workflow
    answer=approve(start(),'design_approval')
    with sqlite3.connect(str(root/'checkpoint.sqlite'),check_same_thread=False) as db:
        graph=build(SqliteSaver(db)); r=graph.invoke(Command(resume=answer),config)
        gate(r,'external_verification')

def test_clarification(workflow):
    start,resume,_,_=workflow
    gate(start('ambiguous'),'clarification'); gate(resume('30 days'),'design_approval')

def test_upstream_revision_invalidates_approval(workflow):
    start,resume,_,root=workflow
    r=start(); old=approve(r,'design_approval')
    r=resume(old); gate(r,'external_verification')
    r=resume({'revise_requirement':'changed requirement'})
    assert r['requirement']=='changed requirement' and r['replans']==1
    assert not r['validation'] and not r['approval']
    assert not (root/'fixture'/'candidate').exists()
    assert gate(r,'design_approval')['hash']!=old['hash']
    assert resume(old)['status']=='safe_stop'

def test_generation_resume_graph(workflow,monkeypatch):
    import orchestrator.graph as workflow_graph
    original=workflow_graph.generate_batches; calls=[]
    def flaky(stage,s):
        if stage=='code':
            calls.append(stage)
            if len(calls)==1: raise RuntimeError('temporary')
        return original(stage,s)
    monkeypatch.setattr(workflow_graph,'generate_batches',flaky)
    start,resume,_,_=workflow
    r=resume(approve(start(),'design_approval'))
    gate(r,'external_verification')
    assert r['generation_retries']==1 and r['replans']==0
