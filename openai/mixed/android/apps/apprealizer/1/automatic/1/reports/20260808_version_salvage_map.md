# Version salvage map — 2026-08-08

| Source | Keep | Rewrite/integrate | Reject as a base mechanism |
| --- | --- | --- | --- |
| Original | Permanent native settings/recovery shell, exact editor, `K.*` compatibility, basic accessibility conveniences | Route legacy calls through one generalized core and correct policy semantics | Replacing native controls with WebView-only UI |
| Test1 | Near-original comparison, accessibility configuration, gesture/reference behavior | Fold useful gesture coverage into one structured accessibility portal | Treating it as a new baseline without regression evidence |
| Test2 | Native shell preservation, notification/projection/runtime portal ideas | Make services share the primary core/WebView and one event path | Independent Activity/service WebViews, heaps, and policy state |
| H | Epoch/heap/port/reflection and renderer-recovery direction | Use only deltas that survive I/original comparison | Raw branch as-is; it has compile and product-shell gaps |
| I | Persistent/reparentable WebView, epochs, bounded deliveries, ports, typed reflection direction, renderer recovery | Restore original shell and route all compatibility APIs/portals through its shared concepts | Reduced original UI or parallel security/runtime systems |
| Arena `/0` | Richer gesture path | Feed it through the shared accessibility operation registry | Whole Arena runtime |
| Arena `/2` | Compact TSV batch encoding | Thin decoder into shared dispatch | Its duplicate command executor and gesture regressions |
| Arena `/3` | Overlay modes, capture presentation/lock, self-location, RGB analysis | Implement with a narrowly exposed auxiliary overlay, owned by an epoch | Reflectively constructing an entire native UI from broken HTML |
| Arena `/4` | Manifest/resource/capability/root/permission/stop concepts | Thin adapters over one resource/operation registry | Duplicated dispatchers, unrestricted multi-page bridge, compile-broken frame-pipe routes |
| Version J | Current complete consolidation attempt and reproducible build/test evidence | Device-test and surgically repair observed runtime defects | Calling it approved before API 36 runtime validation |

## Preferred successor topology

The intended topology remains:

1. Original permanent native shell and compatibility surface.
2. One I-style persistent runtime core with epochs, handles, policy, reflection, ports, and bounded events.
3. Test2-style Android lifecycle portals only where Android requires declared components or consent ownership.
4. Arena concepts admitted as thin decoders/adapters or a deliberately isolated overlay—not as additional application runtimes.

This is a working direction, not user approval of Version J or any future candidate.
