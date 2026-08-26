#!/usr/bin/env bash
# Greffe le module Health Connect sur le projet Android que Capacitor vient de
# generer. Tout se fait ici plutot que dans le depot : le dossier android/ est
# recree a chaque compilation et n'est donc jamais versionne.
set -euo pipefail

PKG_DIR=android/app/src/main/java/io/github/squysh/systemeeveil
MANIFEST=android/app/src/main/AndroidManifest.xml

echo "--- module Kotlin ---"
mkdir -p "$PKG_DIR"
cp native/SanteConnect.kt "$PKG_DIR/SanteConnect.kt"

echo "--- support Kotlin dans Gradle ---"
# Le plugin Kotlin n'est pas dans le gabarit Capacitor : on l'ajoute au projet.
python3 - <<'PY'
import io,re
p="android/build.gradle"
s=io.open(p,encoding="utf-8").read()
if "kotlin-gradle-plugin" not in s:
    s=re.sub(r"(dependencies\s*\{)",
             r"\1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24'",
             s,count=1)
    io.open(p,"w",encoding="utf-8").write(s)
    print("classpath Kotlin ajoute")
else:
    print("classpath Kotlin deja present")

p="android/app/build.gradle"
s=io.open(p,encoding="utf-8").read()
if "kotlin-android" not in s:
    s=s.replace("apply plugin: 'com.android.application'",
                "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'",1)
if "health.connect" not in s:
    s=re.sub(r"(dependencies\s*\{)",
             r"""\1
    implementation 'androidx.health.connect:connect-client:1.1.0-alpha07'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'""",
             s,count=1)
# Health Connect exige un niveau d'API recent
s=re.sub(r"minSdkVersion\s+\w+", "minSdkVersion 26", s)
io.open(p,"w",encoding="utf-8").write(s)
print("dependances ajoutees")
PY

echo "--- enregistrement du module ---"
MAIN=$(find android/app/src/main/java -name "MainActivity.java" | head -1)
python3 - "$MAIN" <<'PY'
import io,sys
p=sys.argv[1]
s=io.open(p,encoding="utf-8").read()
if "SanteConnect" not in s:
    s=s.replace("import com.getcapacitor.BridgeActivity;",
                "import android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;",1)
    s=s.replace("public class MainActivity extends BridgeActivity {",
"""public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SanteConnect.class);
        super.onCreate(savedInstanceState);
    }""",1)
    io.open(p,"w",encoding="utf-8").write(s)
    print("module enregistre dans", p)
else:
    print("deja enregistre")
PY

echo "--- autorisations ---"
python3 - "$MANIFEST" <<'PY'
import io,sys
p=sys.argv[1]
s=io.open(p,encoding="utf-8").read()
perms="""
    <uses-permission android:name="android.permission.health.READ_STEPS" />
    <uses-permission android:name="android.permission.health.READ_SLEEP" />
    <uses-permission android:name="android.permission.health.READ_HEART_RATE" />
    <uses-permission android:name="android.permission.health.READ_EXERCISE" />
    <uses-permission android:name="android.permission.health.READ_DISTANCE" />
    <uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.READ_WEIGHT" />
    <uses-permission android:name="android.permission.health.READ_BODY_FAT" />
"""
if "READ_STEPS" not in s:
    s=s.replace("</manifest>", perms+"\n    <queries>\n        <package android:name=\"com.google.android.apps.healthdata\" />\n    </queries>\n</manifest>",1)
# Health Connect exige un ecran expliquant l'usage des donnees
alias = """
        <activity-alias
            android:name="ViewPermissionUsageActivity"
            android:exported="true"
            android:targetActivity=".MainActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>
"""
if "ViewPermissionUsageActivity" not in s:
    s=s.replace("</application>", alias+"    </application>",1)
# Sur Android 13 et anterieur, l'ecran d'autorisation passe par un intent dedie
intent = """
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
"""
if "ACTION_SHOW_PERMISSIONS_RATIONALE" not in s:
    s=s.replace('<activity\n            android:configChanges',
                '<activity\n            android:configChanges',1)
    i=s.find('android:name=".MainActivity"')
    if i>0:
        j=s.find("</activity>", i)
        s=s[:j]+intent+"        "+s[j:]
io.open(p,"w",encoding="utf-8").write(s)
print("manifeste complete")
PY

echo "--- verification ---"
grep -c "READ_STEPS" "$MANIFEST"
grep -c "SanteConnect" "$MAIN"
ls -la "$PKG_DIR"
