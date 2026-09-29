"""Check the API 100 build contract and, optionally, the packaged APK."""
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
prop_path = "app/src/main/resources/META-INF/xposed/module.prop"
props = dict(line.split("=", 1) for line in (root / prop_path).read_text().splitlines() if "=" in line)
assert props["minApiVersion"] == "100", props
assert props["targetApiVersion"] == "100", props
assert "autoHotReload" not in props
build = (root / "app/build.gradle.kts").read_text()
assert 'compileOnly(project(":xposed-api"))' in build
for path in (root / "app/src/main/java").rglob("*.java"):
    source = path.read_text(encoding="utf-8")
    for forbidden in ("XposedInterface.Chain", "XposedInterface.ExceptionMode", "onPackageReady(", "getModuleApplicationInfo("):
        assert forbidden not in source, (path, forbidden)
entry = (root / "app/src/main/java/com/melody/melodylink/hook/HookModule.java").read_text(encoding="utf-8")
assert "HookModule(XposedInterface base, ModuleLoadedParam param)" in entry
assert "onPackageLoaded(PackageLoadedParam param)" in entry
if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        assert apk.read("META-INF/xposed/module.prop").decode().strip() == (root / prop_path).read_text().strip()
        assert apk.read("META-INF/xposed/java_init.list").decode().strip() == "com.melody.melodylink.hook.HookModule"
        # DEX class definitions, rather than references, must not package framework stubs.
        import struct
        for name in apk.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            dex = apk.read(name)
            string_count, string_offset, type_count, type_offset = struct.unpack_from("<4I", dex, 56)
            strings = []
            for index in range(string_count):
                offset = struct.unpack_from("<I", dex, string_offset + 4 * index)[0]
                while dex[offset] & 128:
                    offset += 1
                offset += 1
                strings.append(dex[offset:dex.index(b"\0", offset)].decode("utf-8", errors="replace"))
            types = [strings[struct.unpack_from("<I", dex, type_offset + 4 * i)[0]] for i in range(type_count)]
            count, offset = struct.unpack_from("<2I", dex, 96)
            for index in range(count):
                descriptor = types[struct.unpack_from("<I", dex, offset + 32 * index)[0]]
                assert not descriptor.startswith("Lio/github/libxposed/api/"), descriptor
print("PASS: API 100 contract" + (" and APK contents" if len(sys.argv) > 1 else ""))
