from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt
from .core import *
def revision_answer(s, answer):
    if not isinstance(answer,dict) or 'revise_requirement' not in answer: return None
    value=answer['revise_requirement']
    if not isinstance(value,str) or not value.strip() or len(value)>20000: return None
    return {'requirement':value.strip()}

def revise(s):
    old=s['requirement']; new=s['revision']['requirement']
    audit(s,'upstream_change',{'old_hash':digest(old),'new_hash':digest(new),'invalidated':['requirements','plan','design','approval','code','tests','validation','review','docs']})
    archive_candidate(s,'upstream-change')
    log=folder(s)/'verification.log'
    if log.exists(): log.replace(log.with_name(f'verification-v{s.get("version",1)}.log'))
    REPLANS.inc()
    return dict(requirement=new,revision={},clarification='',clarification_count=0,version=s.get('version',1)+1,replans=s.get('replans',0)+1,generation_epoch=s.get('generation_epoch',0)+1,generation_retries=0,requirements={},plan={},design={},approval={},code={},tests={},validation={},review={},docs={},status='running')

def can_revise(s): return s.get('replans',0)<int(os.getenv('MAX_REPLANS','3'))
def retry_generation(s):
    audit(s,'generation_resume',{'errors':s['validation'].get('error'),'epoch':s.get('generation_epoch',0)})
    RETRIES.inc()
    return {'generation_retries':s.get('generation_retries',0)+1,'failure_at':s.get('failure_at') or time.time()}
def retryable_generation(s):
    return any(s.get(k,{}).get('error') for k in ('code','tests')) and s.get('generation_retries',0)<int(os.getenv('MAX_GENERATION_RESUMES','1'))
def requirements(s): return {'requirements':generate('requirements',s,Analysis)}
def clarify(s):
    answer=interrupt({'type':'clarification','questions':s['requirements']['ambiguities']})
    revision=revision_answer(s,answer)
    if revision: return {'revision':revision}
    return {'clarification':str(s.get('clarification',''))+'\n'+str(answer),'clarification_count':s.get('clarification_count',0)+1,'version':s.get('version',1)+1}
def plan(s): return {'plan':generate('plan',s,Plan)}
def design(s): return {'design':generate('design',s,Plan)}
def approval(s):
    binding=digest([s['requirements'],s['plan'],s['design'],s.get('version',1)])
    answer=interrupt({'type':'design_approval','hash':binding,'design':s['design']})
    revision=revision_answer(s,answer)
    if revision: return {'revision':revision}
    approved=isinstance(answer,dict) and answer.get('approve') is True and answer.get('hash')==binding
    return {'approval':audit(s,'design_approval',{'approved':approved,'hash':binding})}
def patch_agent(stage,s):
    try: return {stage:generate_batches(stage,s)}
    except (RuntimeError, ValueError, OSError) as e:
        return {stage:{'files':{},'error':str(e)}}
def code(s): return patch_agent('code',s)
def tests(s): return patch_agent('tests',s)
def validation(s): return {'validation':verify(s)}
def external(s):
    result=interrupt({'type':'external_verification','candidate':str(folder(s)/'candidate'),'hash':s['validation']['candidate_hash'],'instruction':'Run tests in isolated environment; provide result and evidence.'})
    revision=revision_answer(s,result)
    if revision: return {'revision':revision}
    passed=isinstance(result,dict) and result.get('passed') is True and result.get('hash')==s['validation']['candidate_hash'] and validate_test_log(s,result) and tree_hash(folder(s)/'candidate')==s['validation']['candidate_hash']
    return {'validation':audit(s,'external_verification',{'passed':passed,'evidence':result,'candidate_hash':s['validation']['candidate_hash']})}
def recover(s):
    failure={'version':s.get('version',1),'validation':s.get('validation',{}),'review':s.get('review',{})}
    audit(s,'replan',failure)
    archive_candidate(s,'rollback'); ROLLBACKS.inc(); REPLANS.inc()
    log=folder(s)/'verification.log'
    if log.exists(): log.replace(log.with_name(f'verification-v{s.get("version",1)}.log'))
    return {'failure_history':s.get('failure_history',[])+[failure],'review':{},'replans':s.get('replans',0)+1,'version':s.get('version',1)+1,'failure_at':s.get('failure_at') or time.time(),'approval':{},'code':{},'tests':{},'validation':{},'docs':{},'generation_epoch':s.get('generation_epoch',0)+1,'generation_retries':0}
def review(s): return {'review':generate('review',s,Review)}
def docs(s):
    result=generate('docs',s,Documentation)
    out=folder(s)/'reports'/str(s.get('version',1)); out.mkdir(parents=True,exist_ok=True)
    for key,value in result.items(): (out/(key+'.md')).write_text(value,encoding='utf8')
    return {'docs':result}
def release(s):
    binding=digest([s['code'],s['tests'],s['validation'],s['review'],s['docs']]); answer=interrupt({'type':'release_approval','hash':binding,'review':s['review']})
    revision=revision_answer(s,answer)
    if revision: return {'revision':revision}
    ok=isinstance(answer,dict) and answer.get('approve') is True and answer.get('hash')==binding and tree_hash(folder(s)/'candidate')==s['validation']['candidate_hash']
    audit(s,'release_approval',{'approved':ok,'hash':binding})
    if ok and s.get('failure_at'): RECOVERY.observe(time.time()-s['failure_at'])
    return {'status':'release_ready' if ok else 'safe_stop'}
def finish(s):
    status='release_ready' if s.get('status')=='release_ready' and not s.get('revision') else 'safe_stop'; OUTCOMES.labels(status).inc(); LATENCY.observe(time.time()-s['started'])
    if status!='release_ready': archive_candidate(s,'rollback'); ROLLBACKS.inc()
    audit(s,'terminal',{'status':status}); return {'status':status}
def build(checkpointer):
    g=StateGraph(State)
    for fn in [requirements,clarify,plan,design,approval,code,tests,validation,external,recover,review,docs,release,finish,revise,retry_generation]: g.add_node(fn.__name__,fn)
    g.add_edge(START,'requirements'); g.add_conditional_edges('requirements',lambda s:('clarify' if s.get('clarification_count',0)<3 else 'finish') if s['requirements']['ambiguities'] else 'plan', ['clarify','plan','finish']); g.add_conditional_edges('clarify',lambda s: ('revise' if can_revise(s) else 'finish') if s.get('revision') else 'requirements',['revise','finish','requirements']); g.add_edge('revise','requirements'); g.add_edge('plan','design'); g.add_edge('design','approval')
    g.add_conditional_edges('approval',lambda s: ['revise' if can_revise(s) else 'finish'] if s.get('revision') else ['code','tests'] if s['approval']['approved'] else ['finish'],['code','tests','finish','revise']); g.add_edge(['code','tests'],'validation')
    g.add_conditional_edges('validation',lambda s:'external' if s['validation'].get('pending_external_test') else 'retry_generation' if retryable_generation(s) else 'recover' if s.get('replans',0)<int(os.getenv('MAX_REPLANS','3')) else 'finish',['external','recover','finish','retry_generation']); g.add_edge('retry_generation','code'); g.add_edge('retry_generation','tests'); g.add_conditional_edges('external',lambda s: ('revise' if can_revise(s) else 'finish') if s.get('revision') else 'review' if s['validation']['passed'] else 'recover' if s.get('replans',0)<int(os.getenv('MAX_REPLANS','3')) else 'finish',['review','recover','finish','revise']); g.add_edge('recover','plan'); g.add_conditional_edges('review',lambda s: ('recover' if s.get('replans',0)<int(os.getenv('MAX_REPLANS','3')) else 'finish') if s['review'].get('blocking') else 'docs',['recover','finish','docs']); g.add_edge('docs','release'); g.add_conditional_edges('release',lambda s: ('revise' if can_revise(s) else 'finish') if s.get('revision') else 'finish',['revise','finish']); g.add_edge('finish',END); return g.compile(checkpointer=checkpointer)
