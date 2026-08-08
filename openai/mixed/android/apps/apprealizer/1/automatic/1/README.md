# App Realizer — automatic workspace 1

This directory is the durable, expandable root for the App Realizer / Chameleon Android project work assigned to this automation workspace.

It preserves source inputs, constitutions, historical prototypes, candidates, artifacts, reports, experiments, and validation evidence in separate lanes. Historical files are retained as evidence; their presence does not make them approved or production-ready.

## Current state

- The original supplied `main.kt` remains the zero-regression product reference for the permanent native shell and legacy `K.*` behavior.
- Version J is the newest complete buildable candidate stored here. It compiled and passed its host-side checks, but it is **not an approved baseline** and has not completed API 36 emulator/device runtime validation.
- Arena `/4` is the broadest Arena Kotlin milestone; Arena `/3` contains the most useful overlay product model. Neither is a replacement base.
- The repository is public. Never commit credentials, release keys, personal data, or captured private screen/notification content.

## Authority order

When instructions conflict, use this order:

1. Current explicit user instruction.
2. The latest project constitution or prompt in [`constitution/current`](constitution/current/).
3. Explicit accepted decisions in [`decisions`](decisions/).
4. Exact source artifacts and their version documents.
5. Reports, summaries, and historical notes.

Summaries never overrule the exact source or constitution.

## Directory map

| Path | Purpose |
| --- | --- |
| [`constitution/`](constitution/) | Current product requirements and operating rules. |
| [`sources/`](sources/) | Immutable supplied source packet plus convenient lineage views. |
| [`candidates/`](candidates/) | New, non-approved implementation candidates. |
| [`arena/`](arena/) | Reconstructed Arena snapshots, overlays, and original patch evidence. |
| [`reports/`](reports/) | Inventories, comparisons, checksums, and validation reports. |
| [`decisions/`](decisions/) | Append-only project decision log. |
| [`experiments/`](experiments/) | Isolated explorations that are not candidates. |
| [`overlays/`](overlays/) | Overlay-specific designs and future implementations. |
| [`tests/`](tests/) | Reproducible test plans, fixtures, and evidence. |
| [`tools/`](tools/) | Small reproducibility utilities. |

## Start here for future work

1. Read this file, [`STATUS.md`](STATUS.md), [`AGENTS.md`](AGENTS.md), and the relevant constitution files fully.
2. Read the complete target source and the original baseline before changing code.
3. Create a new candidate or experiment; never overwrite a supplied snapshot.
4. Label evidence precisely as source-inspected, compiled, host-tested, emulator-verified, device-verified, failed, or platform-limited.
5. Update the relevant README, decision log, inventory, and checksum manifest when durable material changes.

The one-byte `makefolder` file is retained as the original GitHub bootstrap marker.
