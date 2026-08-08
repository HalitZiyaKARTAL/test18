# Project status

Last organized: 2026-08-08, Europe/Istanbul.

## Baseline and candidate status

| Item | Status | Strongest evidence | Main limitation |
| --- | --- | --- | --- |
| Original supplied source | Zero-regression reference | Source-inspected; previously compiled in an API 36 scaffold | Limited capability set and legacy policy defects remain. |
| Test1 | Comparison reference | Source-inspected | Not selected as a consolidation base. |
| Test2 | Portal/reference branch | Source-inspected; normalized staging build was measured historically | Raw source has compile issues and duplicates runtime ownership. |
| H | Earlier generalized-runtime reference | Source-inspected | Superseded for design comparison by I; raw source has compile issues. |
| I | Main generalized-runtime reference | Source-inspected; normalized staging build was measured historically | Raw source has compile issues and reduced original native shell. |
| Arena `/0`–`/4` | Historical prototypes and milestones | Reconstructed byte-for-byte from the supplied patch | Shared compile blocker; no completed emulator/device validation. |
| Version J | Neutral complete candidate | API 36 compiled; 5/5 host tests; lint 0 errors; APK signature/alignment previously verified | API 36 runtime is unverified, and its current reflection dialog does not yet meet the constitution's exact four-choice Graylist contract. |

No candidate is currently approved as a new baseline.

## Version J identity

- Package: `a.htmlapprealizer`
- Version code/name: `10` / `J-candidate-20260807`
- compile/target SDK: 36; min SDK: 30
- Toolchain: AGP 8.13.2, Kotlin 2.2.21, Java 17
- APK SHA-256: `61e25cbef8e6378092ed99fc10a77ff29837343cdc4819bb2594cd7249516d95`
- APK size: 1,144,458 bytes
- Kotlin-reflect measured release cost: 984,611 APK bytes

See [`candidates/version-j`](candidates/version-j/) and its preserved engineering report for the full evidence matrix.

## Next decisive gate

The next baseline decision should follow a real API 36 device or stable accelerated-emulator run covering at least:

1. Fresh install, native gear, exact editor, Realize, and recovery.
2. Bridge OFF, Sandbox, password, policy decisions, and epoch revocation.
3. WebView Internet cut versus approved reflected networking.
4. Accessibility tree/actions/events and overlay behavior.
5. MediaProjection consent/lifecycle, notifications, background runtime, vibration, and alarm paths.
6. Renderer death, Activity recreation, service reconnect, cleanup, and leak/resource checks.

Until then, Version J remains a compile- and host-tested candidate only.

## Known constitutional gap in J

The preserved J source uses three Android dialog buttons: `Allow once`, `Always allow`, and `Block`. It does not expose distinct `Reject once` and `Always reject` actions, and its persistent allow/block paths add to Whitelist/Blacklist without removing the selected rule from Graylist. The current compact constitution requires exactly four choices and requires moving persistent decisions out of Graylist. This must be repaired and tested in a successor or surgical J revision; the historical J files remain unchanged.
