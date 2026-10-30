---
name: moveinsync-engineering
description: Implements, tests, reviews and maintains the MoveInSync polygon geofencing backend using its canonical specifications, phase roadmap, architecture constraints and verification requirements. Use whenever implementing a project phase, debugging, reviewing code, changing architecture or validating performance.
---

# MoveInSync Engineering Workflow

Use this procedure for every change. Root `AGENTS.md`, the original case-study PDF, numbered specifications and current phase acceptance criteria are authoritative.

## Explore
- Read `AGENTS.md`, relevant numbered specs and dependencies, and `docs/IMPLEMENTATION-LOG.md`.
- Inspect the real tree, source, tests, Maven modules, dependencies, config, migrations, CI, Git status and recent commits.
- Preserve existing changes and behavior. Never assume the repo is empty or overwrite without understanding it.
- Inspect the PDF when available. Treat alternative blueprints as supplementary; document conflicts and never silently override canonical specs.

## Understand and plan
- Identify the requirement, domain invariants, affected modules, execution path, and concurrency, persistence, security and failure implications.
- Consider an alternative for non-trivial decisions and choose the smallest correct design consistent with binding decisions.
- Before implementation, list objective, requirements, data flow, affected files/modules, tests, verification commands, risks and acceptance criteria.
- Implement only the current incomplete phase; do not skip prerequisites or add unrelated features.
- If a binding spec is impossible or contradictory, document the issue and smallest safe deviation in `docs/DECISIONS-DEVIATIONS.md`.

## Test-first implementation
- Turn requirements into observable tests; write tests first where practical and establish expected failures.
- Implement the smallest correct change, run focused tests, then relevant regression tests.
- Never weaken assertions. Ensure tests validate behavior rather than mirror implementation.
- Keep `zone-core` plain Java without Spring, persistence or JSON compile dependencies. Prefer constructor injection, immutable values, cohesive classes, deliberate errors and injected `Clock`.
- Establish dependency necessity/compatibility. Avoid acceptance-critical placeholders, ignored errors, static mutable app state and unbounded resources.

## Verify and review
- Run exact phase commands, focused checks first and prescribed full verification afterward.
- Record commands, exit codes, test counts/results, failures and causes, machine/runtime details and limitations. Never invent evidence.
- Independently review the full diff for correctness, complexity, races, security, bounds, API/persistence consistency, test quality and maintainability.
- Fix important findings and rerun impacted checks. Include deterministic concurrency and injected-failure tests where required.

## Document and commit
- Update implementation log, phase acceptance criteria and relevant documentation to match actual behavior/results.
- Record deviations and unresolved risks. Keep PII out of logs and errors.
- Commit completed phases in small Conventional Commits. Preserve unrelated changes; never commit build output or secrets.
- Report changes, decisions, files, exact verification evidence, review fixes, limitations, acceptance status and next phase.

## Reference repositories
- Use repositories in `14-reference-repos-borrow-map.md` for ideas only. Respect licenses and write original code; never copy files or substantial fragments.
