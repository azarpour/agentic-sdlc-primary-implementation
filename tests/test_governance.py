import hashlib
import json
import sys
import types
import pytest
from orchestrator import core

@pytest.fixture(autouse=True)
def isolate(tmp_path, monkeypatch):
    monkeypatch.setattr(core, 'ROOT', tmp_path)
    monkeypatch.setenv('LLM_MODE', 'mock')

def test_patch_atomic_on_policy_failure(tmp_path):
    path = tmp_path/'src/main/java/A.java'
    with pytest.raises(ValueError):
        core.apply_patch(tmp_path, {'files': {'src/main/java/A.java':'class A {}', '../escape.java':'class B {}'}}, 'src/main/java')
    assert not path.exists()

def test_existing_test_immutable(tmp_path):
    p=tmp_path/'src/test/java/A.java'; p.parent.mkdir(parents=True); p.write_text('original')
    with pytest.raises(ValueError): core.apply_patch(tmp_path, {'files':{'src/test/java/A.java':'changed'}}, 'src/test/java')
    assert p.read_text()=='original'

def test_generation_failure_blocks_validation():
    s={'run_id':'failure','scenario':'greenfield','code':{'error':'provider exhausted'},'tests':{}}
    assert not core.verify(s)['pending_external_test']
    assert not (core.folder(s)/'candidate').exists()

@pytest.mark.parametrize('fallback',[True,False])
def test_provider_retry_budget(monkeypatch, fallback):
    calls=[]
    class Bad:
        def __init__(self, **kwargs): calls.append('primary')
        def with_structured_output(self,*args,**kwargs): return self
        def invoke(self,prompt): raise TimeoutError()
    class Secondary(Bad):
        def __init__(self,**kwargs): calls.append('secondary')
        def invoke(self,prompt):
            if not fallback: raise TimeoutError()
            return {'parsed':core.Analysis(summary='fallback'), 'parsing_error':None}
    for module,name,cls in [('langchain_anthropic','ChatAnthropic',Bad),('langchain_openai','ChatOpenAI',Secondary)]:
        m=types.ModuleType(module); setattr(m,name,cls); monkeypatch.setitem(sys.modules,module,m)
    for key in ['ANTHROPIC_API_KEY','ANTHROPIC_MODEL','OPENAI_API_KEY','OPENAI_MODEL']: monkeypatch.setenv(key,'fixture')
    monkeypatch.setenv('LLM_MODE','live'); monkeypatch.setattr(core.time,'sleep',lambda _:None)
    s={'run_id':'retry','scenario':'greenfield','requirement':'fixture'}
    if fallback: assert core.generate('requirements',s,core.Analysis)['summary']=='fallback'
    else:
        with pytest.raises(RuntimeError,match='budget exhausted'): core.generate('requirements',s,core.Analysis)
    assert calls==['primary']*3+['secondary']*(1 if fallback else 2)
    events=[json.loads(x) for x in (core.folder(s)/'audit.jsonl').read_text().splitlines()]
    assert sum(e.get('event')=='fallback' for e in events)==1

def test_ambiguous_uses_verified_baseline():
    assert core.baseline({'scenario':'ambiguous'})==core.VERIFIED_BASE
