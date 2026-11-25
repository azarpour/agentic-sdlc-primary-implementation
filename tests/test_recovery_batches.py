import pytest
from orchestrator import core

def test_resume_completed_batch(tmp_path,monkeypatch):
    monkeypatch.setattr(core,'ROOT',tmp_path)
    monkeypatch.setenv('LLM_MODE','live')
    paths=['src/main/java/demo/A.java','src/main/java/demo/B.java']
    s=dict(run_id='resume',scenario='greenfield',requirement='new',design={'impacted':paths},plan={},approval={'approved':True})
    calls=[]
    def generate(stage,state,schema):
        path=state['batch_paths'][0]; calls.append(path)
        if path==paths[1] and calls.count(path)==1: raise RuntimeError('temporary')
        return {'files':{path:'class '+path.split('/')[-1][:-5]+' {}'},'summary':'fixture'}
    monkeypatch.setattr(core,'generate',generate)
    with pytest.raises(RuntimeError): core.generate_batches('code',s)
    assert set(core.generate_batches('code',s)['files'])==set(paths)
    assert calls==[paths[0],paths[1],paths[1]]
    s['generation_epoch']=1
    core.generate_batches('code',s)
    assert calls[-2:]==paths
