# discovered/

Notes on resource problems found with Harmon on this machine. One file per
tool, one verdict per file. Half wall of shame, half wall of honour.

These are not neutral write-ups. Every note takes a side: a tool is here
because its engineering deserves either the pillory or the applause, and the
note says which in its second line. A file that reaches the end without having
committed to a verdict does not belong in this directory.

## The two verdicts

**Shame** — the tool wastes a resource it had every opportunity to bound.
Typical charges: unbounded defaults left unset, a runtime chosen for the
vendor's convenience and paid for by the user, a fat binary that loads an
entire platform to run a CLI, work that scales with the machine rather than
the task.

**Honour** — the tool is measurably frugal, and the note shows why. Typical
citations: a flat memory profile under real load, a startup cost that does not
move with host size, a dependency set small enough to name, a design that
gives resources back when it stops needing them.

Both verdicts carry the same evidence burden. Praise without numbers is as
useless here as a complaint without numbers.

**A verdict is per axis, not per tool.** Memory and disk are separate
findings, and a tool can earn opposite verdicts on them — Codex CLI is the
case that forced this rule: a 34 MB median process that writes 28 GB a day.
When the axes disagree, say so in the verdict line ("honour on memory, shame
on disk"), give each axis its own section, and never let the flattering axis
soften the wording of the other. A tool that is excellent at one thing and
ruinous at another is more useful to record than one that is merely bad.

The axes measured so far are **memory** (footprint, its floor, whether it
scales with the host) and **disk** (write volume and its share of the
machine's total, plus retained data and old versions). Add an axis when
something measurable turns up; do not retrofit old notes for it, since their
numbers were not taken with it in mind.

## Structure of a note

```markdown
# <Tool> <version>

**Verdict: shame|honour.** One sentence naming the specific decision.

Measured <date> on <OS version, arch, RAM>, against <what was running>.

## What it is
## The charge  (or: The case)
## Numbers
## Mitigation   (shame only; omit for honour)
## How this was measured
```

`What it is` establishes language, runtime, and packaging — usually the root
of the verdict. `The charge` states the decision being judged and quotes the
evidence for it, typically a config file or a runtime flag dump. `Numbers`
carries the measurements in a table and, where it helps, an ordered list of
contributions largest first. `How this was measured` lists the commands, plus
anything about the measurement that would otherwise look like a mistake.

A verdict may be narrowed — "honour, narrowly" — when a tool is exemplary on
the axis the note is about and unremarkable or unexplained elsewhere. Narrow
it in the verdict line itself, name the axis there, and give the remainder its
own `Open question` section rather than letting it colour the verdict.

`Open question` is for something measured but not explained: a number that is
reproducible, large, and whose cause the note does not know. Say what was
observed, say what would and would not account for it, and stop. Claude Code's
172–316 MB of `IOAccelerator` is the model — a figure too large to omit and
too unexplained to convict on. Guessing there would be worse than the silence.

End a note with a `Compare` line linking the entries it should be read
against. The wall is more useful as a set of contrasts than as a list.

## Rules

Every number is measured on this machine, at a stated date, on a stated host.
Nothing is quoted from a vendor's documentation, a changelog, or memory. If a
figure could not be measured, it does not appear.

Record the commands that produced the numbers. A note whose measurements
cannot be reproduced six months later is an opinion.

Judge the artifact, never the people who shipped it. "Ships without `-Xmx`" is
the finding; the team that shipped it is not the subject. Naming a product,
version, and vendor runtime is fine and necessary.

Distinguish a defect from a decision. Junie's 1.4 GB committed heap against
485 MB live is not a leak, and the note says so — it is a collector that was
never given a ceiling. Calling a tuning default a bug costs the whole
directory its credibility.

State the version in the H1 and keep the note pinned to it. A new major
version that changes the verdict gets its own file rather than an edit, so the
old finding stays legible.

Filenames are the tool's slug in kebab-case, no version: `junie.md`,
`ripgrep.md`. `ls` is the index; there is no table of contents to maintain
until this outgrows a screen.

## Why this lives in Harmon

Harmon exists to explain memory and storage pressure on this machine, and this
directory is where its findings turn into prose. A note that shames a tool for
unbounded heap growth is a specification in disguise: it describes something
Harmon's alert rules should have surfaced on their own. When a note is written
from a manual investigation that Harmon could have flagged and did not, say so
in the note — that gap is the most useful thing it contains.
