# Independent Hook fixture

Build with `gradlew.bat -PhookProbe=true :hook-probe:assembleDebug`.
This optional app is never packaged into the production APK. It samples Android last-known,
current and listener APIs in a separate UID. Its small Baidu API test doubles verify callback
dispatch, coordinate units and SDK stop behavior; they do not establish compatibility with every real SDK version.

Select `com.root.fakeloc.hookprobe` in both the module's app picker and LSPosed scope for testing,
then remove it from both lists afterwards. `files/snapshot.json` is available through this debug app's `run-as`.
