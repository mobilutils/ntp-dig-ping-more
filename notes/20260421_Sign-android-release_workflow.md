# Sign Android Release Workflow

## Keystore Creation

```bash
keytool -genkeypair -v \
  -keystore .keystore/my-release.keystore \
  -alias my-release-key \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Verify:
```bash
keytool -list -keystore .keystore/my-release.keystore \
  -storepass "1PassIs1PassWaitNoMaybe?"
```

## GitHub Secrets

Four secrets configured via `gh secret set`:

| Secret | Content |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `.keystore` file |
| `KEY_ALIAS` | Key alias name (`my-release-key`) |
| `KEY_STORE_PASSWORD` | Password for the keystore file |
| `KEY_PASSWORD` | Password for the key entry (empty in this case) |

```bash
base64 .keystore/my-release.keystore > my-release-keystore.b64
gh secret set KEYSTORE_BASE64 < my-release-keystore.b64

echo -n "1PassIs1PassWaitNoMaybe?" > my-release-keystore-password
gh secret set KEY_STORE_PASSWORD < my-release-keystore-password

echo -n "my-release-key" > my-release-key-alias
gh secret set KEY_ALIAS < my-release-key-alias

touch my-release-key-aliaspassword  # key entry password is empty
gh secret set KEY_PASSWORD < my-release-key-aliaspassword
```

## Workflow Changes

### `android-signed-apk.yml`

- **Restored** the `r0adkll/sign-android-release@v1` signing step (removed earlier in the unsigned fallback).
- **Added** `permissions: contents: write` so `softprops/action-gh-release` can create releases via the API.
- APK output: `app-release-unsigned.apk` → signed → `release-signed.apk` → uploaded as GitHub Release asset.

### Workflow flow

```
Tag push (v*) → assembleRelease → sign-android-release → GitHub Release
```

## Required Repository Setting

**GitHub Actions workflow permissions must be set to "Read and write"**.

By default, tag-triggered workflows get a **read-only** `GITHUB_TOKEN`. Even with `permissions: contents: write` in the YAML, the `softprops/action-gh-release` step fails with:

```
GitHub release failed with status: 403
{"message":"Resource not accessible by integration"}
```

**Fix:** Go to **Settings > Actions > General > Workflow permissions** and select **"Read and write permissions"**.

URL: `https://github.com/mobilutils/ntp-dig-ping-more/settings/actions`

Without this setting, the signing step succeeds but the release upload fails.

## Releasing

```bash
git tag -a v2.5 -m "Release 2.5"
git push origin v2.5
```

The workflow will automatically:
1. Build the release APK
2. Sign it with the stored keystore
3. Publish it as a GitHub Release (publicly downloadable, no login required)
