# GitHub Actions build

This project intentionally does not require a Gradle Wrapper. The workflow in `.github/workflows/android.yml` installs Gradle 8.7 on GitHub Actions and runs:

```bash
gradle assembleDebug --stacktrace
```

APK output:

```text
app/build/outputs/apk/debug/*.apk
```
