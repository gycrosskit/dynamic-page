"""回归真实故障：同 variant 两份 KLIB 必须变成互不冲突的发布坐标。"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


class CinteropPublicationTest(unittest.TestCase):
    def test_preserves_binary_and_moves_interop_to_exact_transitive_dependency(self):
        group, version = "com.github.gycrosskit.dynamic-page", "0.1.0-test"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "com/github/gycrosskit/dynamic-page"
            source = root / "dynamic-page-kuikly-ohosarm64" / version
            source.mkdir(parents=True)
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr("default/manifest", "depends=stdlib\ninterop=true\n")
            original = buffer.getvalue()
            filename = f"dynamic-page-kuikly-ohosarm64-{version}-cinterop-kuiklyModule.klib"
            (source / filename).write_bytes(original)
            interop = {"name": filename, "url": filename, "size": len(original)}
            interop.update({a: hashlib.new(a, original).hexdigest() for a in ("md5", "sha1", "sha256", "sha512")})
            stdlib = {"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "2.2.21-1.0.0"}}
            attrs = {"org.jetbrains.kotlin.platform.type": "native", "org.jetbrains.kotlin.native.target": "ohos_arm64"}
            main = {"name": "main.klib", "url": "main.klib"}
            variant = {"name": "ohosArm64ApiElements-published", "attributes": attrs, "files": [main, interop],
                       "dependencies": [stdlib, {"group": "com.tencent.kuikly-open", "module": "core"}]}
            metadata = {"component": {"group": group, "module": "dynamic-page-kuikly-ohosarm64", "version": version}, "variants": [variant]}
            module = source / f"dynamic-page-kuikly-ohosarm64-{version}.module"
            module.write_text(json.dumps(metadata))
            module.with_suffix(".pom").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies/></project>')
            subprocess.run([sys.executable, str(Path(__file__).with_name("prepare-cinterop-publication.py")), directory], check=True, capture_output=True)
            changed = json.loads(module.read_text())["variants"][0]
            self.assertEqual(changed["files"], [main])
            dependency = {"group": group, "module": "dynamic-page-cinterop-ohosarm64", "version": {"requires": version}}
            self.assertIn(dependency, changed["dependencies"])
            target = root / dependency["module"] / version
            published = json.loads(next(target.glob("*.module")).read_text())["variants"][0]
            self.assertEqual(published["attributes"], attrs)
            self.assertEqual(published["dependencies"], [stdlib])
            artifact = target / published["files"][0]["url"]
            self.assertEqual(artifact.read_bytes(), original)
            self.assertNotIn("-cinterop-kuiklyModule", artifact.name)
            ns = {"m": "http://maven.apache.org/POM/4.0.0"}
            entry = ET.parse(module.with_suffix(".pom")).find("m:dependencies/m:dependency", ns)
            self.assertEqual(entry.findtext("m:artifactId", namespaces=ns), dependency["module"])
            self.assertEqual(entry.findtext("m:version", namespaces=ns), version)
            for file in [module, module.with_suffix(".pom"), *target.iterdir()]:
                if file.suffix in (".module", ".pom", ".klib"):
                    for algorithm in ("md5", "sha1", "sha256", "sha512"):
                        self.assertEqual(file.with_name(file.name + "." + algorithm).read_text(), hashlib.new(algorithm, file.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
