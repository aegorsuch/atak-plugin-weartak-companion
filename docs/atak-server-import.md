# Import a server from ATAK

Connect the WearTAK watch using **Configure single watch** and wait for its settings.
Open **Configure Watch → Import from ATAK**, select the ATAK connection, and choose what to transfer.
Connected ATAK servers are listed first. TCP/QUIC connections cannot be represented
by this watch profile and cannot be imported.

| Transfer | What the watch receives | When information is missing |
| --- | --- | --- |
| Auto-enroll | Server plus enrollment username/password | Enter the missing login in the guided dialog |
| Soft certificates | Server plus client key/certificate and its CA chain, with a working file password | Disabled without a stored client certificate; request a missing file password in the same dialog |
| Server details only | Server name/address/port, saved disabled | Finish authentication on the watch later |

The enabled authentication mode comes from ATAK's per-connection enrollment flag,
then the presence of a stored client certificate. Login presence is not used to
classify onboarding. An enrollment connection enables Auto-enroll and disables Soft
certificates, including when ATAK has already issued an enrollment certificate. A
non-enrollment connection with a stored client certificate enables Soft certificates
and disables Auto-enroll. Disabled rows explain why they are unavailable. Missing
login/file passwords are still requested within the supported mode.

Server details only is always available. If ATAK exposes neither enrollment state
nor a stored client certificate, it is the only enabled choice; use the separate
Custom editor to configure authentication from scratch.

All three choices end at **Review and send → Send to Watch**. They do not open the
Custom editor. A soft-certificate import with usable saved files and passwords goes
straight to review; no file picker is required.

**Manual setup** opens the original Custom editor as a separate
option. Its unsent fields are never used to build a guided import. Custom retains its
existing authentication controls and behavior.

## Auto-enroll

This branch reads the selected ATAK connection's live login, with its host's saved
CoT-service login as fallback. Different usernames are not combined. ATAK may retain
both credentials, only the username, or neither, depending on caching and expiration.
Review the retrieved login and enter anything missing. Exact password whitespace is
preserved.

The watch enrolls itself using its existing HTTPS 8446 enrollment implementation.
This branch sends no P12, certificate-file password, or CA-file password. It never
searches for or copies ATAK's issued enrollment certificate. A consumed or expired
login still needs replacement; retrieving a saved login does not prove the server
will accept it. The watch must already trust the enrollment server through its
existing trust/approval mechanisms. This protocol does not carry a separate bootstrap
CA or custom enrollment port.

## Soft certificates

This branch reads certificate bytes directly from ATAK's certificate database; it
does not rely on finding the original files in Android storage.

Client material is looked up by host and streaming port, with ATAK's default client
certificate as fallback for a non-enrollment connection. CA material independently
uses host/port then ATAK's default CA store. Each uses the password saved for that
material's own scope. ATAK's password database is host-scoped, so a missing or
mismatched password can still require user input, including with multiple ports.

A client file must contain a private key and certificate chain. A CA-only file cannot
serve as the client identity. Available CA certificates complete a missing issuer
chain. The client identity is repacked with a fresh transport file password, so blank
or whitespace-containing original passwords work with the watch's existing password
normalization. ATAK's own files and passwords are unchanged. Files and the resulting
bundle are limited to 256 KiB each. Missing file passwords are requested only within
this branch. Account usernames/passwords are neither read nor sent here.

The watch can use the client chain's root for server TLS trust. This covers the
usual arrangement where client and server certificates share the same CA. The
current Companion wire format has no separate approved-server-CA field. If the
server uses a CA outside that chain, it must already be trusted/approved on the watch.
The review shows a notice when separate CA material or an incomplete chain makes
this relevant. Including unrelated CA entries inside a P12 is not a reliable way
to install approved server trust on the current watch implementation.

Copying a client identity shares that certificate with ATAK. It does not transfer
ATAK's renewal process.

## Identity, merging and save confirmation

Before sending, optionally select **Copy or edit ATAK callsign, team/color and role**.
The fields start with ATAK's identity and can be changed for the watch. Leaving the
option off omits these fields. This is a one-time setup, without a phone management
lock; the watch retains its own device UID.

The import merges into the authoritative watch snapshot, not the Custom draft.
Matching host/port updates the existing entry and preserves its watch-owned server
ID and other watch state. Other servers and unrecognized protocol fields are kept.
The transfer leaves reporting interval and units out of its payload. Server-details
only clears authentication and disables that entry so it cannot attempt an empty
login.

No upload occurs until **Send to Watch**. Wait for the watch's save acknowledgment;
that confirms settings storage, not a successful TAK connection. Import/send are
blocked until the updated server snapshot arrives. **Refresh from Watch** remains
available. Changing BLE peer/session, closing the flow, or receiving a changed server
snapshot prevents stale work from sending. Late file/certificate results are discarded
and owned byte buffers are wiped.

## Implementation evidence and validation

The ATAK adapter was compiled against the supplied **ATAK-CIV 5.8.0.5 SDK**. It uses
`TAKServer.enrollForCert()`, `TAKServerListener`, `AtakAuthenticationDatabase`, and
`AtakCertificateDatabase`. The supplied SDK's Quick Connect implementation saves
CoT-service credentials and an enrollment flag; credential caching still determines
what remains available.

Watch payload and trust behavior were inspected in
[`aegorsuch/weartak-wearos` at bda6619](https://github.com/aegorsuch/weartak-wearos/tree/bda661928d883c3a45bf115901f35835da884601):
`TakServerItem`, `SetSettingsPayload`, `CompanionAppService.applyProvisionedSettings`,
`TAKConnectService.findRootInChain`, and `TakServerCaTrust`. Use the paired-device checklist below to qualify each authentication mode against
the target watch firmware and TAK server.

`AtakServerTransferTest` covers strict mode separation, login validation, snapshot
merging, identity opt-in, certificate/password scopes, rewrapping, CA completion,
separate-CA notices, and discarded buffer ownership. The focused GitLab regression
job includes it. Validation-only Robolectric checks cover actual dialog navigation,
missing passwords, direct sending, disabled-mode selection and Back navigation, stale async work/file selections,
and short/narrow/large-text layouts with native action buttons outside the scrolling
body. These checks do not exercise ATAK's native credential database, BLE, or a real
watch TLS connection.

## Physical phone/watch acceptance

1. Build/sign the supplied source ZIP through TAK.gov, install on the ATAK 5.8.0.5
   phone, and connect the physical WearTAK watch in Configure Single Watch.
2. Verify Import and Manual setup are parallel choices. Put an
   unsent change in Custom, then import; confirm that change never enters the sent
   guided payload.
3. Test Quick Connect and ZIP auto-enrollment connections. Confirm Auto-enroll is
   suggested even when an issued certificate exists. Test a retained login and a
   missing password. No certificate-file controls should appear. Send and verify
   the watch's own enrollment and standalone TAK connection.
4. Test a soft-certificate connection. Saved bytes/passwords should go directly to
   review, without Choose P12. A missing file password should be requested in the
   guided flow. No account-login fields should appear. Verify standalone TAK
   connectivity on the watch. Without a stored client certificate, Soft certificates
   must be disabled. If neither enrollment nor a client certificate is available,
   both authentication choices must be disabled and Server details only selected.
5. Test a blank/custom file password and CA chain completion. For a separate server
   CA, verify existing watch trust/approval explicitly. A settings ACK alone does
   not verify TLS trust.
6. Test Server details only: authentication is empty and the watch entry is disabled.
7. Leave identity off, then repeat with edited ATAK callsign/team/role. Verify the
   opt-in behavior and unchanged watch UID. Reimport: the matching entry should be
   updated without duplicates, retaining serverId and other saved servers.
8. Check portrait/landscape, enlarged fonts, keyboard visibility and scrolling. The
   Continue/Send/Back buttons must remain accessible.
9. Disconnect or change watch while a dialog/file operation is pending. Confirm no
   stale send reaches another watch. Wait for the saved snapshot before reimporting.
