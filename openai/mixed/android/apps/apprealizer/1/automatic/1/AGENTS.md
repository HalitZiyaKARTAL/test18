# Agent operating rules

These rules apply to this directory and all descendants.

## Pre-change gate

- Fresh-read `README.md`, `STATUS.md`, both files in `constitution/current/`, the complete target source, and the original source triplet before any code patch.
- When regression risk exists, compare Original, Test2, and I directly; use H, Test1, and Arena snapshots as references rather than blindly merging them.
- Treat current explicit user instructions as higher authority than every saved file.
- Keep anything not yet mapped to the constitution marked `needs review`; do not silently discard it.

## Preservation and versioning

- Files in `sources/supplied-packet/` and `arena/snapshots/` are immutable evidence.
- Do not overwrite, rename, delete, or call an existing source obsolete.
- Put implementation work under `candidates/<candidate-name>/` and experiments under `experiments/<experiment-name>/`.
- A candidate remains neutral and unapproved until the user explicitly promotes it.
- Preserve exact default HTML and original native controls where the constitution requires them.

## Architecture and code quality

- Prefer architectural compression: widen a shared argument-driven pipe instead of adding parallel heaps, policies, dispatchers, WebViews, or per-feature runtimes.
- Preserve readable role-bearing names; do not use lexical minification as code optimization.
- Keep Java and Kotlin reflection behind one policy/audit gate.
- Keep native controls independent of the WebView and JavaScript runtime.
- Add diagnostic logging at new execution and failure boundaries where relevant; do not remove existing diagnostic coverage merely to reduce size.
- When presenting a surgical diff to the user, use the requested marker form: `inert/*🟢*/new surgical code/*🟡old surgical code🔴*/inert`.

## Validation language

Never write “works” from source presence alone. Use only the strongest proven state:

- `SOURCE-INSPECTED`
- `COMPILED`
- `HOST-TESTED`
- `EMULATOR-VERIFIED`
- `DEVICE-VERIFIED`
- `FAILED`
- `PLATFORM-LIMITED`

Record unresolved test gaps explicitly. API 36 is the priority runtime target.

## Repository hygiene

- This repository is public. Do not commit API keys, passwords, signing keys, private captures, or user data.
- Keep build outputs out of source trees except deliberately preserved release/evaluation artifacts under a candidate's `artifacts/` directory.
- Update `reports/checksums/SHA256SUMS.txt` after durable file changes.
- Append material project decisions to `decisions/DECISION_LOG.md` rather than rewriting history.
