"""Unpack official Android SDK archives into the local SDK after sdkmanager stalls."""
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
packages = [
    (root / ".toolchain/platform-35-full.zip", root / ".toolchain/sdk/platforms/android-35", "android-35/"),
    (root / ".toolchain/build-tools-35-full.zip", root / ".toolchain/sdk/build-tools/35.0.0", "android-15/"),
]
for archive, destination, prefix in packages:
    destination.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive) as zip_file:
        for member in zip_file.infolist():
            if not member.filename.startswith(prefix):
                continue
            relative = Path(member.filename[len(prefix):])
            if not relative.parts or ".." in relative.parts:
                continue
            target = destination / relative
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with zip_file.open(member) as source, target.open("wb") as output:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
    print(destination)
