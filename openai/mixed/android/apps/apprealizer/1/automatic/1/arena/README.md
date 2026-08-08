# Arena historical workspace

This directory reconstructs the 2026-08-03 Arena patch as normal files while preserving both the exact uploaded text and a directly applicable clean patch.

## Contents

- `archive/Pasted text(2).txt`: exact 949,945-byte uploaded attachment, including its two-line wrapper.
- `archive/app_realizer_latest_unpushed_83f0676.patch`: the same Git patch with the wrapper removed so `git apply` can consume it.
- `snapshots/`: reconstructed `android-apps/app_realizer` tree, including `/0`, `/2`, `/3`, `/4`, and `VERSIONING.txt`.
- `external-overlays/`: the two overlay files that the patch stored outside the App Realizer tree.

The original patch represents local commit `83f0676f1d6b93024549b1e4c921d362230adcc6`, contains 45 newly added files, and was labelled unpublished/unpushed.

## Snapshot roles and verified relationships

| Snapshot | Role | Verified relationship |
| --- | --- | --- |
| `/0` | Arena continuously updated working state at patch time | 1,630-line Kotlin snapshot. |
| `/2` | Frozen earlier proposal | Compared with `/0`: 44 insertions and 29 deletions. |
| `/3` | Overlay milestone | Kotlin is byte-identical to `/2`; unique value is its overlay HTML and milestone note. |
| `/4` | Manifest/resource/fractal-kernel milestone | Strict Kotlin superset of `/0`: 182 insertions, 0 deletions. |

`/1` is described by `VERSIONING.txt` as the original/live snapshot but is not included because the patch contains only newly added files.

## Known blockers

- Every Arena Kotlin snapshot retains the object-form `framePipe`, `framePipeStop`, and `framePipeInfo` branches that reference undefined `a` inside `cmd(o)`, preventing a clean compile.
- The mirror overlay saved under `/3/test_html` and `external-overlays` contains corrupted `CALLBACmirror` references and throws before normal initialization.
- Arena adds breadth but does not supply completed Gradle projects or emulator/device evidence.
- Multiple routing, WebView, callback, policy, and resource mechanisms should not be imported wholesale.

## Salvage guidance

- `/0`: preserve its richer gesture behavior.
- `/2`: preserve only the compact TSV transport idea, decoded into a shared dispatcher.
- `/3`: preserve the `PLACE` / `VISUAL` / `HIDDEN` model, capture presentation/locking, self-localization, and RGB analysis concepts.
- `/4`: preserve resource/manifest/capability/root/permission/stop ideas as thin adapters into one core.

Treat the complete Arena tree as immutable historical evidence.
