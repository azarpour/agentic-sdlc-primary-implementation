# Reviewer quick start
Requirements: Docker Desktop (Linux containers), Java 21, Maven, PowerShell. Use an isolated local environment.

```powershell
Copy-Item .env.example .env
docker compose up -d --build
Invoke-RestMethod http://localhost:8080/actuator/health
$r = Invoke-RestMethod http://localhost:8080/api/v1/urls -Method Post -ContentType application/json -Body '{"url":"https://example.com"}'
$r
Invoke-RestMethod "http://localhost:8080/api/v1/urls/$($r.code)/analytics"
```
Swagger: http://localhost:8000/docs. Mock mode is default; set LLM_MODE=live and a supported provider key/model in your local .env for actual generation. Never share the key. Live calls incur charges.

Submit a scenario via POST /runs with the JSON in scenarios/. Resume with a clarification string or an approval object containing approve and the exact returned hash. For external verification, inspect candidate code first, then run Maven in a disposable environment:

```powershell
$id = '<run id>'
Push-Location "runs/$id/candidate"
mvn -B clean verify 2>&1 | Tee-Object ../verification.log
$exitCode = $LASTEXITCODE
Pop-Location
Get-FileHash "runs/$id/verification.log" -Algorithm SHA256
```
Resume with answer containing passed (true only for exit 0), hash (candidate hash), log_sha256 (lowercase log hash), evidence (verification.log). Inspect the review and approve the release hash only if risks are accepted. Do not approve blocking reviews. On Windows Docker API compatibility issues, the recorded environment used DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine, DOCKER_API_VERSION=1.44 and JAVA_TOOL_OPTIONS=-Dapi.version=1.44; these are environment-specific workarounds, not universal requirements.

For Python tests: `docker compose cp tests orchestrator:/app/tests` then `docker compose exec orchestrator python -m pytest /app/tests -q`. For baseline Java tests run `mvn -B clean verify` from baselines/greenfield-verified. For Brownfield use the recorded candidate under runs/0dae8850-00ce-4106-8908-9ddfb7a30741/candidate. See the engineering report and actual run evidence for status.

For the Ambiguous candidate, run Maven from `runs/94e9c2a1-7ac5-44f9-b1e5-46e10a0debd4/candidate`. The default Compose service runs the verified Greenfield baseline; the two release-ready candidates are independent and are not deployed or merged by this package.
