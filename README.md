# Governed Agentic SDLC — URL Shortener
Engineering assessment submission. Start with [engineering report](docs/ENGINEERING-REPORT.md) and [reviewer quick start](docs/QUICKSTART.md). The report is a dated evidence snapshot; consult included run records for later results. The verified service baseline is separate from the Greenfield scaffold. Live provider credentials are not included. Mock mode is a fixture, not evidence of AI generation.

Final recorded status: Greenfield recovered and verified (179 Java tests); Brownfield release_ready (184); Ambiguous release_ready (213); orchestration tests (28, operator-reported for recovery revision). Candidate test counts overlap. See docs/ENGINEERING-REPORT.md for limitations.

Supplemental audit: [quality audit](docs/FINAL-QUALITY-AUDIT.md). 19 Python tests independently passed in the audit environment; all 25 subsequently passed in the author’s existing Docker container. See verification/python-final-console.txt.

## Recovery extension
See [Recovery and replanning](docs/RECOVERY-AND-REPLANNING.md) for the revised workflow, verification status and API example. Earlier evidence is historical; rerun the Python suite against this revision.

## Final evidence status
The revised Greenfield scenario explicitly requires the full reliability scope. This scenario text has not yet been executed with a live provider. The previous live design-only attempt was not approved. The verified Java baseline and both historical release-ready candidates remain unchanged. See verification/recovery-operator-result.txt.
