# Supplemental quality audit

The original live-run artifacts and source candidates are preserved unchanged. AI-assisted supplemental tests were added after the original submission snapshot.

## Independently executed
`python -m pytest -q tests/test_core.py tests/test_validation.py tests/test_governance.py`: 19 passed (13 original, 6 additional). The additional tests exercise provider retry/fallback and exhaustion with injected fake clients, atomic rejection of invalid patches, immutable regression tests, blocked generation failures and baseline selection. No paid provider calls were made.

## Subsequent author-environment verification
`python -m pytest -q tests/test_graph_lifecycle.py`: six new tests covering the real LangGraph lifecycle with deterministic mock generation, join, invalid approval, replan exhaustion, candidate tampering, SQLite checkpoint reopening and clarification. Synthetic Maven evidence in these tests is explicitly a fixture, not Java execution evidence. LangGraph is unavailable in the audit environment and dependency installation failed due to network resolution. The author subsequently copied the supplemental tests into the existing project orchestrator container and ran the entire suite: 25 passed in 1.53s. This is author-reported console evidence, not an independent clean-image rerun.

## Traceability and remaining limitations
| Requirement | Evidence / limitation |
|---|---|
| Requirements and ambiguity | Recorded ambiguous clarification and design gates |
| Decomposition and codebase reasoning | Recorded plans and impacted paths |
| Stateful dependency graph, parallel branches, join | graph.py; live Brownfield and Ambiguous evidence; supplemental graph tests passed in author environment |
| Approval, safe stop, rollback | Recorded approvals and rejected design; supplemental tamper and replan tests passed in author environment; rollback archives candidates, not deployments |
| Retry and fallback | Injected provider tests passed; live secondary provider not verified |
| Dynamic replanning | Failure-driven replan; arbitrary upstream edits not supported |
| Security and compliance | Path and content controls; no full sandbox, authentication or independent policy engine |
| Audit and metrics | JSONL and Prometheus snapshots; not tamper-proof, counters reset on restart; no validated production MTTR |
| Engineering output and validation | Historical Java results: 179 Greenfield, 184 Brownfield, 213 Ambiguous; overlapping suites, independent candidates |
| Greenfield | Human-assisted recovery, not a fully autonomous successful live run |
| Brownfield and Ambiguous | Both recorded release_ready; not merged |
| External verification | Hash and Maven log checks; not trusted runner attestation |
| Reproducibility | CI and setup instructions; clean Docker build and Java rerun not independently performed in audit environment |

Do not interpret release_ready as production deployment approval. API smoke/load tests and production hardening remain outside this supplemental audit.
