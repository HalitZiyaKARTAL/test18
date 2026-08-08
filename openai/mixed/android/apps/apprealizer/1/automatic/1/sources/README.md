# Supplied source packet

`supplied-packet/` contains the 12 attachments exactly as mounted on 2026-08-08. `lineage/` contains byte-identical convenience copies grouped by the version role that was explicit in the filenames and prior project work.

## Kotlin lineage

| Role | Supplied filename | Lines | Bytes | SHA-256 |
| --- | --- | ---: | ---: | --- |
| Original | `main.kt` | 331 | 17,118 | `8222deb9719651582f0c8ebeb4ee68d40b11e64657361e9dbfe27c797b1d15c3` |
| Test2 | `maintest2.kt` | 695 | 31,519 | `8aaa068027ade3ad8b9b7ac2a876b75d3706567bc809f4e7b80dcdf755b936d8` |
| I | `testredo1i.kt` | 985 | 38,835 | `31abd4516c30c036a6dc311f02a812d0c2587cabdc9f9be5865bf1edb390f899` |
| H | `maintestredo1h.kt` | 941 | 36,825 | `a02840e5da3b5c9ff80cc45ad950d7ef2c554bc89843ea801562d602500de59f` |
| Test1 | `maintest1.kt` | 344 | 18,111 | `cde85871afa21de0e1f9a3fce8f1c7f215bec06bd2c3b5a6fd2962d134074590` |

## Companion-file mapping

- Original: `AndroidManifest.xml`, `accessibility_config.xml`.
- Test1: `AndroidManifesttestredo1.xml`, `accessibility_configtest1.xml`.
- Test2: `AndroidManifesttest2.xml`.
- H: `AndroidManifesttestredo1h.xml`.
- I: `AndroidManifesttestredo1i.xml`.

No distinct accessibility XML was supplied for Test2, H, or I. The lineage views intentionally do not manufacture or silently infer one. The raw packet remains authoritative.

H and I manifests are byte-identical, but their Kotlin files are not: I differs from H by 99 inserted and 55 deleted lines. H and Arena `/4` are separate artifacts; neither filename nor checksum establishes them as the same source.

Do not edit these snapshots. Create a candidate under `../candidates/` instead.
