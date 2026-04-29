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
The RecordingTrustManager always calls the system PKIX validator first — TLS is never bypassed. Expired/untrusted certs throw as normal; those exceptions are caught, the cert data extracted where obtainable, and surfaced as CertExpired / UntrustedChain result subtypes that show a ⚠️ warning banner while still displaying the parsed fields.

Suggested manual tests
google.com:443 → 🟢 green chip, all sections filled
expired.badssl.com:443 → 🔴 red chip + amber warning banner
self-signed.badssl.com:443 → warning banner "Untrusted chain"
Copy the SHA-256 fingerprint → verify clipboard content, check the ✓ checkmark fades after 2 s
Rotate device mid-load → no crash, state preserved