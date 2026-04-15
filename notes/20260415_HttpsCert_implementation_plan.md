# HTTPS Certificate Inspector — Implementation Plan

## Overview

Add an **HTTPS Certificate Viewer** tool to the existing app. The user enters a hostname (and optional port), the app performs a TLS handshake using a metadata-only custom `TrustManager`, extracts the full leaf certificate, and displays it in a grouped, scrollable Material 3 UI with copy-to-clipboard buttons and a colour-coded validity badge.

The feature will follow **exactly the same MVVM pattern** used by `GoogleTimeSyncRepository / ViewModel / Screen`, which is the most recent clean reference in this codebase.

---

## Architecture

```
HttpsCertRepository          ← IO layer; performs TLS handshake via SSLSocket
        │                      Uses a recording TrustManager that captures the chain
        │                      without disabling system trust verification
        ▼
HttpsCertViewModel           ← StateFlow-based ViewModel; cancellable job
        │
        ▼
HttpsCertScreen              ← Compose UI; scrollable sections + copy buttons
```

---

## Security Note — TrustManager Strategy

> [!IMPORTANT]
> TLS verification is **NOT** bypassed. The implementation wraps the `PKIX` validation path:
> 1. Create a custom `X509TrustManager` that **first calls the system's default trust manager** (`checkServerTrusted`), then records the chain if it passes.
> 2. For expired/self-signed certs the system will still throw — those errors are caught and mapped to specific `HttpsCertResult.Error` subtypes, giving useful UI feedback without silently accepting bad certs.
> 3. `SSLSocket` / `SSLSocketFactory` constructed from a fresh `SSLContext` initialised with this recording trust manager.
> 4. No `HostnameVerifier` override; default Android hostname verification applies.

---

## Proposed Changes

### 1. Data Layer

#### [NEW] `HttpsCertRepository.kt`

Contains:
- **`CertificateInfo`** — serializable Kotlin `data class`:
  ```kotlin
  data class CertificateInfo(
      val subject: DistinguishedName,
      val issuer:  DistinguishedName,
      val notBefore: String,           // ISO-8601
      val notAfter:  String,           // ISO-8601
      val isExpired: Boolean,
      val daysUntilExpiry: Long,       // negative = already expired
      val serialNumber: String,        // hex
      val sha256Fingerprint: String,   // colon-separated
      val sha1Fingerprint:  String,
      val subjectAltNames: List<SanEntry>,
      val keyAlgorithm: String,        // e.g. "RSA"
      val keySize: Int,                // e.g. 2048
      val version: Int,                // X.509 version
      val signatureAlgorithm: String,  // e.g. "SHA256withRSA"
      val chainDepth: Int,             // 1 = leaf only
  )

  data class DistinguishedName(val cn: String?, val o: String?, val ou: String?, val c: String?)
  data class SanEntry(val type: String, val value: String) // type = "DNS" or "IP"
  ```

- **`HttpsCertResult`** sealed hierarchy:
  ```kotlin
  sealed class HttpsCertResult {
      data class Success(val info: CertificateInfo) : HttpsCertResult()
      data object NoNetwork : HttpsCertResult()
      data class HostnameUnresolved(val host: String) : HttpsCertResult()
      data class HandshakeFailed(val reason: String) : HttpsCertResult()
      data class CertExpired(val info: CertificateInfo) : HttpsCertResult()   // cert parsed but expired
      data class SelfSigned(val info: CertificateInfo) : HttpsCertResult()    // untrusted chain but cert extracted
      data class Timeout(val host: String) : HttpsCertResult()
      data class Error(val message: String) : HttpsCertResult()
  }
  ```

- **`HttpsCertRepository.fetchCertificate(host, port)`**:
  - Runs on `Dispatchers.IO`
  - Opens `SSLSocket` via custom `SSLContext` → performs TLS handshake → reads chain
  - Delegates real validation to system PKIX; catches `CertificateExpiredException` and `SSLHandshakeException` to differentiate result types
  - Parses `X509Certificate` fields into `CertificateInfo`
  - Computes SHA-256 and SHA-1 fingerprints via `MessageDigest`
  - Extracts SAN entries from `subjectAlternativeNames`
  - Extracts key size via `RSAPublicKey.modulus.bitLength()` or `ECPublicKey.params.order.bitLength()`

---

### 2. ViewModel

#### [NEW] `HttpsCertViewModel.kt`

- `UiState` sealed: `Idle | Loading | Success(CertificateInfo) | PartialSuccess(result, CertificateInfo) | Error(String)`
  - `PartialSuccess` is used when the cert was extracted but the chain is expired/self-signed — shows the cert data with a prominent warning badge
- Input fields: `host: String`, `port: String` (default `"443"`)
- `fun fetchCert(host, port)` — cancellable job, validates inputs first
- `fun reset()`

---

### 3. UI

#### [NEW] `HttpsCertScreen.kt`

Layout (vertical scroll via `LazyColumn`):

```
┌──────────────────────────────┐
│  URL/host input + port input │
│  [Fetch Certificate] button  │
├──────────────────────────────┤
│  ● Validity badge            │ ← Green/Amber/Red chip
│    VALID · expires in 47d    │
├──────────────────────────────┤
│  📋 Subject                  │
│    CN / O / OU / C           │
│  📋 Issuer                   │
│    CN / O / OU / C           │
├──────────────────────────────┤
│  🗓 Validity Period           │
│    Not Before / Not After    │
├──────────────────────────────┤
│  🔑 Public Key               │
│    Algorithm / Key Size      │
├──────────────────────────────┤
│  📄 Certificate Meta         │
│    Version / Serial / SigAlg │
├──────────────────────────────┤
│  🌐 Subject Alt Names        │
│    DNS: … [Copy]             │
│    IP:  … [Copy]             │
├──────────────────────────────┤
│  🔏 Fingerprints             │
│    SHA-256 … [Copy]          │
│    SHA-1   … [Copy]          │
└──────────────────────────────┘
```

UX details:
- Each section is a Material 3 `Card` with an expandable header
- Copy-to-clipboard icon buttons on fingerprints and SANs (2-second "Copied!" feedback via `LaunchedEffect`)
- Validity badge uses `SuggestionChip` or a custom `Badge` — colour:
  - 🟢 Green: valid, > 30 days remaining
  - 🟡 Amber: expiring within 30 days
  - 🔴 Red: expired
- Animated `AnimatedVisibility` entrance for result sections (same pattern as `GoogleTimeSyncScreen`)
- Error card with retry button (same pattern as `GoogleTimeSyncScreen`)
- Warning banner if `PartialSuccess` (expired/self-signed) — shows yellow card above results

---

### 4. Navigation

#### [MODIFY] [MainActivity.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/MainActivity.kt)

- Add `AppScreen.HttpsCert : AppScreen("https_cert", "HTTPS Cert", Icons.Filled.Lock)` to sealed class
- Add it to `allAppScreens`
- Add `composable(AppScreen.HttpsCert.route) { HttpsCertScreen() }` to `NavHost`
- Update `isMoreToolsSelected` logic in `bottomNavItems` to include `AppScreen.HttpsCert.route`

#### [MODIFY] [MoreToolsScreen.kt](file:///Users/enola/Workspace-gitmobilutils/android-ntp_dig_ping_more/app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/MoreToolsScreen.kt)

- Add `AppScreen.HttpsCert` to `extraTools` list

---

## No New Dependencies Required

The implementation uses only **standard Android / JDK APIs**:
- `javax.net.ssl.SSLContext`, `SSLSocket`, `X509TrustManager` — already part of Android SDK
- `java.security.cert.X509Certificate` — JDK standard
- `java.security.MessageDigest` — JDK standard (SHA-256, SHA-1)
- No OkHttp or Retrofit needed; `SSLSocket` gives direct TLS handshake control

---

## Verification Plan

### Automated
1. Build: `./gradlew assembleDebug` — must succeed with 0 errors.

### Manual
1. Deploy on device/emulator
2. Navigate: More → HTTPS Cert
3. Test valid cert: `google.com:443` — green badge, all sections populated
4. Test expiring cert (staging env or known expiring domain)
5. Test expired cert — red badge + warning banner, cert data still visible
6. Test self-signed: `self-signed.badssl.com:443` — warning banner
7. Test bad host: intentionally wrong host — error card
8. Test copy buttons: copy SHA-256 fingerprint → verify clipboard content
9. Test rotation: rotate device mid-load — no crash, state preserved
