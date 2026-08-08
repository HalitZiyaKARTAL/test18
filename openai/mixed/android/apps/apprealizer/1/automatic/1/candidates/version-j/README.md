# Version J candidate

Version J (`J-candidate-20260807`) is a neutral, non-approved candidate created on 2026-08-07. It does not overwrite any supplied source.

## Layout

| Path | Contents |
| --- | --- |
| `project/` | Complete 17-file Gradle/Android project extracted from the preserved source bundle. |
| `bundles/` | Original full-source text with exact BEGIN/END file boundaries. |
| `artifacts/` | Debug-signed evaluation APK. |
| `reports/` | Original engineering, regression, build, size, and test report. |

`tools/extract_version_j_bundle.mjs` at the workspace root reproduces `project/` from the bundle and rejects path traversal or an unexpected section count.

## Preserved validation result

- API 36 release and debug builds succeeded.
- 5/5 JVM tests passed.
- Lint completed with 0 errors and 15 documented warnings.
- All 17 bundled source sections matched the live candidate project byte-for-byte when the original report was produced.
- The APK was zip-aligned and APK Signature Scheme v3 verified at report time.
- `kotlin-reflect` adds a measured 984,611 APK bytes to the minified release.

## Unresolved runtime boundary

The APK was not installed or launched during the previous API 36 emulator attempt. The host lacked KVM; under software emulation, `system_server` repeatedly watchdog-restarted before Package Manager stabilized. Accordingly, UI, bridge, policy, accessibility, overlay, projection, notification, background, alarm, cleanup, and performance behavior remain runtime-unverified.

This candidate must not be promoted until the device/emulator matrix in `STATUS.md` is completed and the user explicitly approves it.

## Known requirement mismatch

Source inspection during repository organization found that J's reflection prompt currently offers `Allow once`, `Always allow`, and `Block`, while the current compact constitution requires exactly `Always allow`, `Allow once`, `Reject once`, and `Always reject`. J also does not remove a persistently moved rule from Graylist. This is a confirmed source-level alignment defect, not merely an untested behavior. The preserved candidate is intentionally not rewritten here.
