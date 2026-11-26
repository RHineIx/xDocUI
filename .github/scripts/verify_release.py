"""Validate the actual release APK before uploading or publishing it."""
from pathlib import Path, PurePosixPath
from zipfile import ZipFile
import sys


def verify(apk: Path) -> None:
    with ZipFile(apk) as archive:
        entries = set(archive.namelist())
        native = {
            PurePosixPath(name).parts[1]
            for name in entries
            if name.startswith("lib/") and name.endswith(".so")
        }
        if native != {"arm64-v8a"}:
            raise SystemExit(f"Invalid release ABI set: {sorted(native)}")
        required = {
            "lib/arm64-v8a/libdexkit.so",
            "META-INF/xposed/java_init.list",
            "META-INF/xposed/module.prop",
            "META-INF/xposed/scope.list",
        }
        missing = required - entries
        if missing:
            raise SystemExit(f"Missing required APK entries: {sorted(missing)}")
        for name in required:
            if archive.getinfo(name).file_size == 0:
                raise SystemExit(f"Empty required APK entry: {name}")
    print(f"Verified {apk.name}: arm64-v8a only, DexKit and Xposed metadata present")


if __name__ == "__main__":
    root = Path(sys.argv[1])
    candidates = sorted(root.glob("*.apk")) if root.is_dir() else [root]
    if len(candidates) != 1 or not candidates[0].is_file():
        raise SystemExit("Expected exactly one release APK")
    verify(candidates[0])
