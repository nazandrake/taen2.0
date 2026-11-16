"""Builds the audit helper APK (taen.audit.helper: a test field, a clipboard writer and reader, an overlay and a content-reading accessibility service)
with the SDK tools, and signs it with a throw-away key made in the scratch directory (never the Taen key). The helper has no network permission."""
import os
import secrets
import shutil
import zipfile

import apk as apklib

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = os.path.join(HERE, "helper")
PACKAGE = "taen.audit.helper"


def build(root, work):
    sdk = apklib.sdk_dir(root)
    android_jar = os.path.join(sdk, "platforms", "android-37.0", "android.jar")
    aapt2, d8, apksigner = (apklib.build_tool(root, n) for n in ("aapt2", "d8", "apksigner"))
    d = os.path.join(work, "helper")
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, "classes"))
    sources = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(HELPER, "taen")) for f in fs if f.endswith(".java")]
    apklib.run(["javac", "-nowarn", "--release", "17", "-cp", android_jar, "-d", os.path.join(d, "classes")] + sources)
    classfiles = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(d, "classes")) for f in fs if f.endswith(".class")]
    os.makedirs(os.path.join(d, "dex"))
    apklib.run([d8, "--output", os.path.join(d, "dex"), "--lib", android_jar, "--min-api", "30"] + classfiles)
    apklib.run([aapt2, "compile", "--dir", os.path.join(HELPER, "res"), "-o", os.path.join(d, "res.zip")])
    apklib.run([aapt2, "link", "-o", os.path.join(d, "base.apk"), "--manifest", os.path.join(HELPER, "AndroidManifest.xml"), "-I", android_jar,
                "--min-sdk-version", "30", "--target-sdk-version", "34", os.path.join(d, "res.zip")])
    unsigned = os.path.join(d, "unsigned.apk")
    with zipfile.ZipFile(os.path.join(d, "base.apk")) as zin, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(os.path.join(d, "dex", "classes.dex"), "classes.dex")
    ks, pw = os.path.join(d, "helper.p12"), os.path.join(d, "helper.pass")
    with open(pw, "w") as f:
        f.write(secrets.token_hex(16))
    os.chmod(pw, 0o600)
    apklib.run(["keytool", "-genkeypair", "-alias", "helper", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12", "-keystore", ks,
                "-storepass:file", pw, "-keypass:file", pw, "-dname", "CN=taen audit helper"])
    signed = os.path.join(d, "taen-audit-helper.apk")
    apklib.run([apksigner, "sign", "--ks", ks, "--ks-pass", "file:" + pw, "--out", signed, unsigned])
    os.remove(pw)
    os.remove(ks)
    return signed
