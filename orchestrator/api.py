import sqlite3, threading
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import Command
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from .graph import *
ROOT.mkdir(parents=True,exist_ok=True)
graph=build(SqliteSaver(sqlite3.connect(str(ROOT/'state.sqlite'),check_same_thread=False)))
lock=threading.Lock(); app=FastAPI(title='Governed SDLC prototype')
class Start(BaseModel):
    requirement:str=Field(min_length=1,max_length=20000); scenario:Literal['greenfield','brownfield','ambiguous']
class Resume(BaseModel):
    answer:dict|str

def execute(run_id,value):
    config={'configurable':{'thread_id':run_id},'recursion_limit':60}
    with lock:
        if isinstance(value,Command):
            snapshot=graph.get_state(config)
            if not snapshot.values: raise HTTPException(404,'Run not found')
            if not snapshot.next or (ROOT/run_id/'stopped').exists(): raise HTTPException(409,'Run is not resumable')
        try: result=graph.invoke(value,config)
        except Exception as e:
            audit({'run_id':run_id},'safe_stop',{},error=type(e).__name__); (ROOT/run_id/'stopped').touch(); archive_candidate({'run_id':run_id},'safe-stop'); OUTCOMES.labels('safe_stop').inc(); raise HTTPException(500,'Execution stopped; inspect audit')
    return {'run_id':run_id,'state':{k:v for k,v in result.items() if k!='__interrupt__'},'interrupts':[i.value for i in result.get('__interrupt__',[])]}
@app.post('/runs')
def start(req:Start):
    run_id=str(uuid.uuid4()); return execute(run_id,dict(run_id=run_id,requirement=req.requirement,scenario=req.scenario,started=time.time(),version=1,replans=0))
@app.post('/runs/{run_id}/resume')
def resume(run_id:uuid.UUID,req:Resume): return execute(str(run_id),Command(resume=req.answer))
@app.get('/runs/{run_id}/audit')
def events(run_id:uuid.UUID):
    p=ROOT/str(run_id)/'audit.jsonl'
    if not p.exists(): raise HTTPException(404)
    return [json.loads(line) for line in p.read_text().splitlines()]
@app.get('/metrics')
def metrics(): return Response(generate_latest(),media_type=CONTENT_TYPE_LATEST)

@app.get('/runs/{run_id}')
def status(run_id:uuid.UUID):
    with lock:
        snap=graph.get_state({'configurable':{'thread_id':str(run_id)}})
        if not snap.values: raise HTTPException(404,'Run not found')
        return {'state':snap.values,'next':snap.next,'interrupts':[i.value for t in snap.tasks for i in t.interrupts]}
