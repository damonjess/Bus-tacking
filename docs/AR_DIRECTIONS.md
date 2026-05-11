# AR walking directions setup

The AR Live View ribbon can follow real walking paths instead of a straight line when the app is built with a Google Directions API key.

## Local build

```bash
GOOGLE_DIRECTIONS_API_KEY=your-google-directions-key gradle :app:assembleDebug
```

If `GOOGLE_DIRECTIONS_API_KEY` is not supplied, the AR view falls back to a local direct-route hint so development builds still work without Google billing enabled.

## GitHub Actions build

1. Open **Settings → Secrets and variables → Actions** in your repository.
2. Add a repository secret named `GOOGLE_DIRECTIONS_API_KEY`.
3. Run **Actions → Build Android APK → Run workflow**.

The key is compiled into `BuildConfig` for debug builds. For production distribution, proxy Directions requests through your own backend if you need stronger key protection, rotation, or quota controls.
