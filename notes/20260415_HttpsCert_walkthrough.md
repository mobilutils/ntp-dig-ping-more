# HTTPS Certificate Inspector 

## HttpsCertRepository.kt
SSLSocket + RecordingTrustManager, certificate parsing, fingerprints, SANs

## HttpsCertViewModel.kt
StateFlow MVVM, cancellable job, input state survives rotation

HttpsCertScreen.kt
Grouped LazyColumn UI, validity chip, warning banner, copy-to-clipboard
Modified files (2)
File	Change

MainActivity.kt
AppScreen.HttpsCert, added to allAppScreens, NavHost, and bottom nav selection logic

MoreToolsScreen.kt
HttpsCert added to extraTools list
Security model
The RecordingTrustManager records the cert chain **before** calling the system PKIX validator — TLS is never bypassed, but the chain is always captured even when validation fails. Expired/untrusted certs throw as normal; those exceptions are caught, the cert data extracted, and surfaced as CertExpired / UntrustedChain result subtypes that show a ⚠️ warning banner while still displaying the parsed fields. The UntrustedChain.info is now always non-null (changed from `CertificateInfo?`), so the ViewModel unconditionally maps it to `PartialSuccess`.

Validity chip
The chip now checks for a trust warning first: when the chain is untrusted it shows a red **"Invalid"** badge instead of a green "Valid", even if the cert is not yet expired. Expiry-based states (Valid / Expiring Soon / Expired) only apply when there's no trust warning.

Suggested manual tests
google.com:443 → 🟢 green chip, all sections filled
expired.badssl.com:443 → 🔴 red chip + amber warning banner
self-signed.badssl.com:443 → warning banner "Untrusted chain"
untrusted-root.badssl.com:443 → red **"Invalid"** chip + warning banner, full cert data displayed
Copy the SHA-256 fingerprint → verify clipboard content, check the ✓ checkmark fades after 2 s
Rotate device mid-load → no crash, state preserved