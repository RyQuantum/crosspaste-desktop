# ry-fixes: personal fork of CrossPaste desktop

Personal build for one Mac (Apple Silicon). Not intended for upstream; nothing here is submitted back.
Design notes and the deploy procedure live in the private repo RyQuantum/omnicraft (`forks/crosspaste-desktop.md`).

## Patches

| File | Change | Why |
|---|---|---|
| `app/src/desktopMain/kotlin/com/crosspaste/paste/plugin/type/MacosBitmapImageWrite.kt` (new), two lines in `DesktopImageTypePlugin.kt` | On macOS, a single image captured from a clipboard bitmap is written back as its stored PNG/JPEG bytes under `public.png` / `public.jpeg` (other formats: `imageFlavor` only), without `javaFileListFlavor` | JBR writes the file list as a second pasteboard item (`public.file-url`) and leaves `NSFilenamesPboardType` / `text/uri-list` declared but empty on the first one, so Alfred records synced screenshots as `File: <uuid>.png`. With `imageFlavor`, JBR spends 50-300 ms encoding PNG/JPEG/TIFF after declaring the types and Alfred often records nothing; the stored bytes take a few ms (28 of 28 recorded in a JBR test harness, against 4 of 8 with `imageFlavor`). Images copied as files keep the file reference. |

Tests: `app/src/desktopTest/kotlin/com/crosspaste/paste/plugin/type/MacosBitmapImageWriteTest.kt`.

## How the macOS app is built

The branch is based on an upstream release tag (`ry/fork.env`). Built from that tag with JDK 21 on Linux, the
`app-desktop` jar is byte-identical to the official one apart from the class file version, so the package is
the official release with only the patched classes swapped in:

1. `.github/workflows/ry-macos.yml` runs ktlint and the fast test tier, then builds the jar.
2. `ry/mac/package.sh` downloads nothing itself; CI hands it the official `mac-aarch64.zip` (checked against the
   release's `checksum.txt`) and the jar. `ry/mac/overlay.py` replaces the entries listed in
   `ry/mac/overlay-classes.txt` and refuses any other difference.
3. Info.plist gets the fork version (`CFBundleShortVersionString`, `RyForkVersion`, `RyForkCommit`) and Sparkle
   is switched off, so the official updater cannot replace the fork.
4. Only the outer bundle is re-signed, with the self-signed certificate in `ry/mac/codesign-cert.pem`
   (private key in repository secrets), without hardened runtime. The designated requirement pins that
   certificate, so macOS keeps Accessibility and keychain grants across fork releases.

Release: bump `FORK_REV` in `ry/fork.env`, push tag `<UPSTREAM_TAG>-ry.<FORK_REV>`.

## Moving to a new upstream release

Rebase `ry-fixes` onto the new tag, set `UPSTREAM_TAG` / `UPSTREAM_VERSION`, reset `FORK_REV=1`. Both ends of a
sync must report the same `SyncApi.VERSION`; check it against the other machines before releasing.
