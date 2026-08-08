# Tools

`extract_version_j_bundle.mjs` expands the preserved Version J full-source bundle into its 17-file Gradle project.

Usage:

```bash
node tools/extract_version_j_bundle.mjs \
  candidates/version-j/bundles/app_realizer_version_j_full_source_20260807_224836.txt \
  /tmp/app-realizer-version-j
```

The extractor validates the expected section count and prevents paths from escaping the chosen destination.
