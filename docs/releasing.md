# Releasing Harmon

Harmon is released as one prebuilt Apple Silicon archive. The archive is the
unit of compatibility: `harmon` and `harmon-collector` are built from the same
clean commit, report the same application version, and cannot be downloaded as
separate release assets.

## Prepare a release

Update `BuildInfo.VERSION`, commit the change, and create a matching `vX.Y.Z`
tag. Before tagging, run the normal verification:

```shell
./kotlin build
./kotlin test
scripts/test-native.sh
```

The tag workflow invokes:

```shell
scripts/package-release.sh --output-dir dist --tag vX.Y.Z
```

The packaging script always runs `./kotlin build --variant release`; it has no
skip-build mode. It rejects a dirty checkout, a tag/version mismatch, a
non-arm64 or mixed-version pair, invalid signatures, an unexpected archive
layout, and non-deterministic output. It ad-hoc signs both standalone
executables with timestamps disabled, renders and lints the application
`Info.plist`, creates the archive twice and compares the bytes, then extracts
the result for a complete smoke check.

The output is:

```text
dist/
  harmon-X.Y.Z-macos-arm64.tar.gz
  harmon-X.Y.Z-macos-arm64.tar.gz.sha256
  harmon-X.Y.Z-macos-arm64.tar.gz.provenance.json
  Formula/harmon.rb
```

The workflow publishes all four files and adds a GitHub artifact attestation
for the tarball.

## Homebrew tap handoff

The tap is intentionally a separate repository. After the GitHub Release is
published, copy the generated formula, not the checked-in template, into that
repository:

```shell
cp dist/Formula/harmon.rb /path/to/homebrew-tap/Formula/harmon.rb
cd /path/to/homebrew-tap
brew style Formula/harmon.rb
brew audit --strict --formula Formula/harmon.rb
brew install --build-from-source ./Formula/harmon.rb
brew test harmon
```

Commit and publish that tap change only after these checks pass. The checked-in
`packaging/homebrew/Formula/harmon.rb.in` is the version-independent source;
`scripts/render-homebrew-formula.sh` replaces its version and SHA-256 tokens
after the tarball exists.

The formula installs only:

```text
bin/harmon
libexec/harmon-collector
share/harmon/Harmon.Info.plist
share/harmon/Harmon.icns
share/harmon/harmon.conf.example
```

It does not build Kotlin/Native, run sudo, mutate launchd, or define a Homebrew
service. Its caveat directs the user to run `harmon setup` after install and
after every upgrade, then `harmon status`.

## Verify published provenance

After publishing, verify the checksum and GitHub attestation:

```shell
shasum -a 256 -c harmon-X.Y.Z-macos-arm64.tar.gz.sha256
gh attestation verify harmon-X.Y.Z-macos-arm64.tar.gz -R Heapy/harmon
```
