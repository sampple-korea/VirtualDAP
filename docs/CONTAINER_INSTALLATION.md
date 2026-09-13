# Music-space installation

Imports run as the normal Android application UID. Only APKs selected by the user are imported;
the host does not request unrestricted storage or install-package privileges. Hosted app data uses
separate directories, but the shared-UID container is **not a security sandbox**. Install trusted apps.

The installed-app picker queries only declared catalog package names, not the complete device app
inventory. It copies base/split APK code from the selected installed app into a bounded private ZIP
and uses the same manifest/signature/atomic-publication path. Private app data and accounts are not
read or migrated. A concurrent store update that produces mismatched versions/signatures is rejected.

## APK sets

The ZIP importer bounds APK count and expanded package bytes and rejects duplicate flattened
filenames. Names inside the archive are not trusted to identify the base or a split.

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

This importer accepts flattened device APK sets. General bundletool archives containing several
standalone variants/duplicate split identities are rejected rather than selecting an arbitrary base.

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
- Instrumentation: base APK installation, PCM playback, update from an APKS ZIP with arbitrary
  filenames, execution of a class that exists only in a separate signed dynamic-feature APK,
  rejection of a tampered update, atomic directory swap, failed-publication preservation and
  symlink-safe staging cleanup.
- Prepared-source regression checks ensure the old split heuristics, signature skip flag and
  pre-install directory deletion are not compiled into the consumer runtime.

The dynamic-feature test APK is included only in instrumentation assets, not the consumer APK.
These tests establish the specific paths exercised, not universal compatibility with all app
stores, split delivery formats or streaming-service DRM.
