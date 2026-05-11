# Free BODS API key setup

The Bus Open Data Service (BODS) data is free to access, but BODS API access is tied to a registered data-consumer account. Do not commit a real API key to this repository because APKs can be decompiled and public Git history cannot reliably keep secrets private.

## Get a free key

1. Go to the official BODS service: <https://data.bus-data.dft.gov.uk/>
2. Register or sign in as a data consumer.
3. Create or copy your API key from your account.
4. Use the key at build time with one of the options below.

## Local build

```bash
BODS_API_KEY=your-free-bods-key gradle :app:assembleDebug
```

Optional filters and endpoint overrides:

```bash
BODS_API_KEY=your-free-bods-key \
BODS_BOUNDING_BOX="-0.5103,51.2868,0.3340,51.6919" \
BODS_API_BASE_URL="https://data.bus-data.dft.gov.uk/api/v1/datafeed/" \
gradle :app:assembleDebug
```

`BODS_BOUNDING_BOX` is recommended because it limits the SIRI-VM response to the area you care about instead of downloading the nationwide feed.

## GitHub Actions build

1. Open your GitHub repository.
2. Go to **Settings → Secrets and variables → Actions**.
3. Add a repository secret named `BODS_API_KEY` containing your free BODS API key.
4. Optionally add repository variables:
   - `BODS_BOUNDING_BOX` to limit the feed to one area.
   - `BODS_API_BASE_URL` if the official BODS endpoint changes.
5. Run **Actions → Build Android APK → Run workflow**.

The workflow compiles the key into the debug APK's `BuildConfig` for testing. For a public production app, route BODS requests through your own backend so you can rotate, protect, and rate-limit the key.
