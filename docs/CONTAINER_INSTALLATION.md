# Music-space installation

Imports run as the normal Android application UID. Only APKs selected by the user are imported;
the host does not request unrestricted storage or install-package privileges. Hosted app data uses
separate directories, but the shared-UID container is **not a security sandbox**. Install trusted apps.

The installed-app picker queries only declared catalog package names, not the complete device app
inventory. It copies base/split APK code from the selected installed app into a bounded private ZIP
and uses the same manifest/signature/atomic-publication path. Private app data and accounts are not
read or migrated. A concurrent store update that produces mismatched versions/signatures is rejected.

## APK sets

The ZIP importer bounds total archive entries, APK count, the binary table-of-contents size and
expanded package bytes. It rejects unsafe or duplicate paths before copying any selected APK.
Names inside the archive are not trusted to identify the base or a split.

For a standard bundletool APKS, the importer reads the bounded binary `toc.pb` directly and selects
exactly one non-instant high-level variant using the current Android SDK, process ABI preference,
multi-ABI set, display density and available texture-compression signals. The matcher follows the
public bundletool targeting wire format and selection rules and rejects an unknown future
variant-targeting dimension instead of guessing. All regular split or standalone APK descriptions
from the selected variant are retained, including install-time and separately delivered feature
modules; the lower manifest planner then removes incompatible ABI configurations. Keeping resource,
language and density splits allows Android's resource resolver to choose from the full supplied set.

For an archive without `toc.pb`, every plain APK entry is normalized. This covers compatible,
unencrypted XAPK/APKM-style archives and already device-targeted split sets. The importer deliberately
does not interpret vendor JSON as an authority for package identity.

The framework binary manifest parser supplies package name, version and split identity. Selection:

- requires exactly one base, consistent package/version, and unique split identities;
- chooses one ABI supported by the current process across base and feature native libraries;
- selects that ABI's configuration for every feature, including x86/x86_64 where supported;
- retains feature modules and resource/language/density configurations for Android to resolve.

The selected files are parsed as an Android package cluster, including feature components and
dependency metadata. Signature verification covers every selected APK. Current signing certificates
must match an existing installation; signing-key rotation migration is conservatively unsupported.
The installed ApplicationInfo uses the parsed split paths/names and chosen ABI, rather than the
upstream filename heuristic or an unconditional host CPU ABI.

JSON-only bundletool tables, encrypted/proprietary wrappers, APK archives requiring an unavailable
process ABI, OBB expansion installation and Play Asset Delivery asset packs are rejected or ignored
as appropriate. Asset slices, instant APKs, system APKs, APEX and archived APK metadata are never
installed as ordinary music-space applications. These boundaries are surfaced as import errors; no
fallback variant is silently chosen.

## Failure-safe publication

The installer copies every selected APK and native library, writes synchronized package metadata
and protects code files in a private staging directory. Only after all staging succeeds does a
native `renameat2(RENAME_EXCHANGE)` atomically replace an existing package directory. A failed copy
or rejected exchange leaves the old installation intact. Fresh installs use an atomic rename.
The old directory is then removed from the staging location without following symbolic links.
User data is not deleted during updates.

## Tests

- Pure JVM tests: common ABI across feature groups, x86 selection, preservation of feature and
  resource splits, mixed packages/versions, duplicate identities and contradictory native ABIs.
- Instrumentation: base APK installation, PCM playback, update from a binary-`toc.pb` APKS containing
  incompatible ARM64 and compatible x86_64 variants, execution of a class that exists only in the
  selected signed dynamic-feature APK,
  rejection of a tampered update, atomic directory swap, failed-publication preservation and
  symlink-safe staging cleanup.
- JVM archive tests cover ABI, SDK and density variant choice, generic XAPK layout, malformed
  protobuf, unsafe/missing paths and expanded-size enforcement.
- Prepared-source regression checks ensure the old split heuristics, signature skip flag,
  pre-install directory deletion and filename-only outer-archive validator are not compiled into the
  consumer runtime.

The dynamic-feature test APK is included only in instrumentation assets, not the consumer APK.
An additional local integration probe built the fixture AAB with official bundletool 1.18.3 and
successfully imported its generated APKS on the API 36 ordinary-UID emulator. The implementation was
checked against bundletool's public
[`commands.proto`](https://github.com/google/bundletool/blob/1.18.3/src/main/proto/commands.proto),
[`targeting.proto`](https://github.com/google/bundletool/blob/1.18.3/src/main/proto/targeting.proto)
and device matchers. These tests establish the specific paths exercised, not universal compatibility
with all app stores, proprietary wrappers or streaming-service DRM.
