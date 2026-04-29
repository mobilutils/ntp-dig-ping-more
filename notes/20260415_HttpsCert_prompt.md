**Feature Objective**

Add HTTPS Certificate view feature, user enters an HTTPS URL/port and the app will display the certificate details following the existing MVVM architecture and Material 3 design patterns.

**Technical Requirements**
1. Interception Method: Use the chosen HTTP client's interceptor/event listener or custom `TrustManager` to access the `X509Certificate` without disabling standard TLS verification.
2. Data Extraction: Parse and expose at minimum:
   - Subject & Issuer (CN, O, OU, C)
   - Validity period (Not Before / Not After)
   - Serial number
   - SHA-256 & SHA-1 fingerprints
   - Subject Alternative Names (DNS/IP)
   - Key algorithm & size
   - Certificate version & signature algorithm
3. Data Model: Create a clean, serializable Kotlin data class to hold the parsed fields. Handle optional/missing fields gracefully.
4. Thread Safety: Certificate parsing must run off the main thread. UI updates must use appropriate coroutine flow/state management.
5. Error Handling: Gracefully handle expired certificates, self-signed chains, handshake failures, and cases where certificate extraction is blocked by the HTTP client configuration.
6. Security: Do not bypass TLS validation. If a custom `TrustManager` is required, ensure it delegates to the system default and only extracts metadata.

🔹 UI/UX Expectations
- Display certificate info in a scrollable, readable layout (e.g., grouped sections, copy-to-clipboard buttons for fingerprints/SANs).
- Include visual indicators for validity status (valid/expiring/expired).
- Provide a clear way to dismiss/close the view.
- If using Compose: use lazy lists, remember state, and handle configuration changes.
