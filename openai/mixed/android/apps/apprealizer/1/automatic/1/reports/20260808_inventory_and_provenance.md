# Inventory and provenance — 2026-08-08

## Imported sources

- 12 user-supplied Kotlin/XML attachments were copied byte-for-byte into `sources/supplied-packet/`.
- The two project prompt/constitution files were copied byte-for-byte into `constitution/current/`.
- The 949,945-byte Arena attachment was preserved exactly and reconstructed into 45 post-patch files. A usable patch copy differs only by removal of the attachment's two-line wrapper.
- Version J's 146,102-byte source bundle, 29,675-byte engineering report, and 1,144,458-byte APK were recovered by exact filename. The bundle was expanded into 17 files; the extractor rejects any other section count.

## Important primary hashes

| Artifact | SHA-256 |
| --- | --- |
| Original `main.kt` | `8222deb9719651582f0c8ebeb4ee68d40b11e64657361e9dbfe27c797b1d15c3` |
| I `testredo1i.kt` | `31abd4516c30c036a6dc311f02a812d0c2587cabdc9f9be5865bf1edb390f899` |
| Arena attachment | `8ad828d6ac09b4f9c75a2658dbbcf7e15ffa73a17ed1bb7c94d004b3e109832c` |
| Version J source bundle | `389daf7687ccb662111c1d4939f78ab15a3ec8846c7c4b5c1dfde159794ff945` |
| Version J report | `c45fcb556cdc879c081486d993323a7141db43d8b4277eeef3c3bba5cb959c7a` |
| Version J APK | `61e25cbef8e6378092ed99fc10a77ff29837343cdc4819bb2594cd7249516d95` |
| Consolidation constitution | `208582d982abd13bfcfc545cd6512a7070ed85bdb00a29b2f3c54b03519a15cc` |

The complete integrity list is in `checksums/SHA256SUMS.txt`.

## Public-repository check

A credential-pattern scan was run across all supplied text, the recovered Version J files, the Arena attachment, and printable APK strings before staging. It found no OpenAI-style keys, GitHub tokens, Google API keys, AWS access keys, Slack tokens, bearer tokens, or private-key headers. This is a targeted scan, not a guarantee that arbitrary private data cannot exist; future additions require the same public-repository caution.

## Provenance cautions

- Convenience copies under `sources/lineage/` are byte-identical to the named supplied files but use canonical filenames for navigation.
- No separate accessibility XML was supplied for Test2, H, or I, so none was inferred.
- H and Arena `/4` are distinct sources. A shorthand parenthetical in the preserved Version J report must not be interpreted as byte identity.
- Historical reports describe the evidence available when written; they do not promote a candidate or replace current validation.
