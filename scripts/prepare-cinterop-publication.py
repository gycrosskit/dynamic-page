"""沿用 MMKV 的发布方式，将真实 C interop KLIB 作为无 classifier 的传递依赖。"""
import hashlib
import json
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(sys.argv[1]) / "com/github/gycrosskit/dynamic-page"
module, = root.glob("dynamic-page-kuikly-ohosarm64/*/*.module")
metadata = json.loads(module.read_text())
group, version = metadata["component"]["group"], metadata["component"]["version"]
variant, = (v for v in metadata["variants"] if v["name"].endswith("ApiElements-published"))
interop, = (f for f in variant["files"] if "-cinterop-" in f["url"])
source = module.parent / interop["url"]
# 此绑定只依赖 stdlib；未来 header 增加平台依赖时必须明确扩展发布合同。
with zipfile.ZipFile(source) as library:
    manifest = dict(line.split("=", 1) for line in library.read("default/manifest").decode().splitlines() if "=" in line)
assert manifest["depends"] == "stdlib" and manifest["interop"] == "true", manifest
stdlib, = (d for d in variant["dependencies"] if d["group"] == "org.jetbrains.kotlin" and d["module"] == "kotlin-stdlib")
artifact = "dynamic-page-cinterop-ohosarm64"
dependency = {"group": group, "module": artifact, "version": {"requires": version}}
target_dir = root / artifact / version
target_dir.mkdir(parents=True, exist_ok=False)
filename = f"{artifact}-{version}.klib"
shutil.copyfile(source, target_dir / filename)
assert (target_dir / filename).stat().st_size == interop["size"]
assert hashlib.sha256((target_dir / filename).read_bytes()).hexdigest() == interop["sha256"]
variant["files"].remove(interop)
variant["dependencies"].append(dependency)
interop = dict(interop, name=filename, url=filename)

def write(path, content):
    path.write_bytes(content)
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        path.with_name(path.name + "." + algorithm).write_text(hashlib.new(algorithm, content).hexdigest())

def write_module(path, data):
    write(path, (json.dumps(data, indent=2) + "\n").encode())

write_module(target_dir / f"{artifact}-{version}.module", {
    "formatVersion": "1.1",
    "component": {"group": group, "module": artifact, "version": version},
    "variants": [{"name": variant["name"], "attributes": variant["attributes"],
                  "dependencies": [stdlib], "files": [interop]}],
})
write(target_dir / filename, (target_dir / filename).read_bytes())
write_module(module, metadata)
namespace = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", namespace)
pom = module.with_suffix(".pom")
parent = ET.fromstring(pom.read_bytes())
dependencies = parent.find(f"{{{namespace}}}dependencies")
assert dependencies is not None
entry = ET.SubElement(dependencies, f"{{{namespace}}}dependency")
for key, value in (("groupId", group), ("artifactId", artifact), ("version", version), ("scope", "compile")):
    ET.SubElement(entry, f"{{{namespace}}}{key}").text = value
# 保留 Gradle metadata marker，POM 仅用于兼容解析。
marker = b"<!-- do_not_remove: published-with-gradle-metadata -->\n"
write(pom, marker + ET.tostring(parent))
write(target_dir / f"{artifact}-{version}.pom", f'''<project xmlns="{namespace}">
<!-- do_not_remove: published-with-gradle-metadata -->
<modelVersion>4.0.0</modelVersion><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version><packaging>klib</packaging>
<licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses>
</project>
'''.encode())
print("Published C interop bytes as one transitive OHOS artifact; no source module added")
