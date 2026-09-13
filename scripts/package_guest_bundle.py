#!/usr/bin/env python3
"""Create the strict two-entry VirtualDAP guest bundle format."""

import argparse
import hashlib
from pathlib import Path
import sys
import zipfile


MAX_IMAGE_BYTES = 24 * 1024 * 1024 * 1024


def clean_manifest_value(name: str, value: str, limit: int) -> str:
    value = value.strip()
    if not value or len(value) > limit or "\n" in value or "\r" in value or "=" in value:
        raise ValueError(f"invalid {name}")
    return value


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--architecture", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--display-name", required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--services", choices=("aosp", "user-provided-gms"), default="aosp")
    parser.add_argument("--attestation", choices=("not-certified", "oem-certified"), default="not-certified")
    args = parser.parse_args()

    if not args.image.is_file():
        parser.error(f"image does not exist: {args.image}")
    image_bytes = args.image.stat().st_size
    if not 0 < image_bytes <= MAX_IMAGE_BYTES:
        parser.error("image must be between 1 byte and 24 GiB")
    display_name = clean_manifest_value("display name", args.display_name, 80)
    fingerprint = clean_manifest_value("fingerprint", args.fingerprint, 255)
    image_hash = hash_file(args.image)
    manifest = "\n".join(
        (
            "formatVersion=1",
            "androidApi=33",
            f"architecture={args.architecture}",
            "backend=virtualdap-platform-v1",
            f"displayName={display_name}",
            f"buildFingerprint={fingerprint}",
            "imageFile=payload/guest.img",
            f"imageBytes={image_bytes}",
            f"imageSha256={image_hash}",
            f"services={args.services}",
            f"attestation={args.attestation}",
            "",
        )
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".partial")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as bundle:
            bundle.writestr("manifest.properties", manifest)
            bundle.write(args.image, "payload/guest.img")
        temporary.replace(args.output)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    print(f"wrote {args.output} ({image_bytes} image bytes, sha256 {image_hash})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(error, file=sys.stderr)
        sys.exit(2)
