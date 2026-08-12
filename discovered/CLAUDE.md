# discovered/

Versioned, evidence-based notes about resource use observed with Harmon on this
machine. Keep one tool per kebab-case file and one explicit verdict per measured
axis.

## Isolation from product work

This directory is a standalone blog, not product documentation or implementation
context. Sessions changing Harmon code must not read files under `discovered/`
unless the task explicitly concerns this blog. Nothing here defines product
behavior, architecture, requirements, or coding conventions.

## Required structure

```markdown
# <Tool> <version>

**Verdict: shame|honour [on <axis>].** <specific decision>

Measured <date> on <OS, architecture, RAM>, against <workload>.

## What it is
## The charge (or: The case)
## Numbers
## Mitigation (shame only)
## How this was measured

Compare <related notes>.
```

Use a separate section for each axis when verdicts differ. Add an `Open
question` section for a reproducible result whose cause is unknown; report the
observation without guessing or using it to justify a verdict.

## Evidence rules

- Base every number on measurements from the stated host and date. Record the
  commands needed to reproduce it.
- Judge the shipped artifact and its decisions, never its authors. Distinguish
  a defect from a deliberate or default configuration choice.
- Pin each note to the version in its H1. Write a new file when a later major
  version changes the verdict.
- Treat verdicts as per-axis. Do not let a good result soften an unrelated bad
  one.
- Add newly measurable axes prospectively; do not retrofit old notes with data
  they did not collect.
- When a manual investigation exposes something Harmon should have detected,
  state that product gap in the note.
