# Optional voice-package Binder V1: working prototype for upstream review

The normal sender is another ATAK operator sharing a VX channel plan over GeoTalk.
TAKS is an additional sender. The watch receives a `b-f-t-r` mission-package offer;
the existing authenticated downloader verifies it before the voice handoff. Companion
can forward a selected package received/created by ATAK when the watch is phone-linked.

We have implemented one host-owned memory broker and two receiver adapters in our
WearTAK development fork: a real SDK plugin and a standalone DEV app. Both adapters
use the same importer, review/store, receipt synchronization, Mumble engine and PTT.
This is a prototype contract, not a claim that the upstream SDK already provides it.

## Plugin identity

- Repository: https://github.com/alexanderhavang-ops/weartak-voice
- Plugin release package: `se.takcampus.voice.watch`
- Current plugin DEV package: `se.takcampus.voice.watch.dev`
- SDK service: `se.takcampus.voice.watch.VoicePluginService`
- Discovery action: `com.weartak.plugin.action.PLUGIN_SERVICE`
- SDK API: 3; source pin `86bdc8b04797c2cf6caa30e78e796abac1c2a330`.
- Current DEV APK signing certificate SHA-256:
  `28c02ac13c4f745f736223a9425003d0e4ee72b7fcc3a145cc5ecc30051c1c9d`

That fingerprint is the development certificate, not a production registration.
The production fingerprint must come from the actual signed release APK.
`se.takcampus.voice.standalone` is the separate standalone build and does not
register an SDK plugin service.

For side-by-side testing with the older prototype, our matching DEV host/plugin
use `se.takcampus.voice.permission.BIND_PLUGIN_DEV`, still signature protected.
Only the debug host admits that permission, only for the Voice DEV package.
Release uses the template's standard `com.weartak.plugin.permission.BIND_PLUGIN`.
A stock host will not discover the DEV permission variant without that test patch.

## Optional discovery without changing the frozen SDK

The pinned existing SDK remains unchanged. The host adds two optional hostExtras:

- `com.weartak.voice.package.version`: integer `1`
- `com.weartak.voice.package.control`: `IVoicePackageControlV1` Binder

Missing/unsupported discovery leaves channel delivery unavailable. The plugin is
built against `weartak-plugin-sdk` API 3 and the shared development `voice-package-api`
module. An upstream SDK integration could expose equivalent optional getters following
its server-package/PKCS#12 pattern, with a new immutable source/artifact pin. No frozen
Parcelable layouts, existing transaction numbers or artifact checks are bypassed.

## Implemented signatures

```aidl
interface IVoicePackageControlV1 {
    Bundle getCapabilities();
    Bundle registerReceiver(IVoicePackageReceiverV1 receiver);
    Bundle takePackage(String handle);
    Bundle reportState(String transferId, String payloadSha256,
        String missionSha256, int state, int errorCode);
    Bundle getStatus(String transferId);
    void unregisterReceiver(IVoicePackageReceiverV1 receiver);
}

oneway interface IVoicePackageReceiverV1 {
    void onOffer(in Bundle offer);
    void onRevoked(String handle, int reason);
}
```

The AIDL source is in `voice-package-api/src/main/aidl/com/weartak/voice/api`.
Bundles carry versioned, bounded metadata and a bounded payload byte array, never
credentials, file paths or an unprotected broadcast.

| Message | Fields |
| --- | --- |
| Capabilities | version, maxZipBytes, maxJsonBytes, leaseMillis, payloadKinds |
| Registration result | version, code |
| Offer | version, opaque handle, transferId, payloadKind, payloadSha256, byteCount, opaque originId, expiresAtElapsedRealtime |
| Take result | version, code, payload (bounded byte array; absent on failure) |
| Receipt/status | version, code, transferId, payloadSha256, missionSha256, state, status, errorCode |

Constants and result codes are defined in `VoicePackageContract`. Payload kinds are
JSON=1 and ZIP=2. The receiver accepts ZIPs up to 262,144 bytes and mission JSON up
to 32,700 bytes; its importer also caps expanded data at 524,288 bytes. The host's
8 MB general package cap and ZIP limits remain. Larger voice payloads are refused
before inline Binder delivery. Larger future payloads need a bounded stream contract.

## Binding, handles and state

Every host-control call authenticates the actual Binder UID, package ownership,
current signing certificate and live binding. Callbacks are scoped to the same
binding; the receiver checks the host UID. Handles are random, one-use, origin-bound
and leased for two minutes. Pending bytes live only in host memory and are wiped
on take/revocation. There are at most four live payloads and 64 receipt records.

Only receipt metadata is persisted. A host restart expires untaken payloads; it
does not silently restore payload bytes. Staged/applied receipts remain queryable
by the current authenticated recipient. A failed/expired delivery can be reissued
with a fresh handle; signer changes and foreign origins cannot reuse an old handle.

DOWNLOADED means host verification. STAGED means the receiver validated and durably
saved a pending review. APPLIED means the wearer saved the channels. REJECTED,
FAILED and EXPIRED remain distinct. A receipt never claims Mumble is connected.
Duplicate hashes are deduplicated within their origin and recipient. A direct ZIP
uses its verified ZIP hash; normalized Companion JSON uses its verified payload
hash, without pretending the watch verified ZIP bytes it never received.

The shared importer rejects corrupt/ambiguous packages before changing the active
configuration and never overwrites another pending review. The host cannot expose
its private keys or Mumble credentials through this API. Voice authentication is a
separate, explicit app setup.

## Standalone development adapter

The standalone app needs no WearTAK SDK or TAK plugin signing. It can open/share a
VX ZIP directly. To test the full host/Companion path, the user taps Link to WearTAK
Dev and explicitly enables the adapter in the host's consent screen.

Its explicit service is protected by a separate Android signature permission;
both ordinary DEV APKs must use the same development signing key. It is declared
only for standaloneDebug, and the host entry activity is declared only for debug.
The adapter checks host package/UID/signature and obtains the same broker control.
It is reported as a standalone receiver in capabilities, never as an SDK plugin.

```aidl
interface IVoicePackageDevGatewayV1 {
    void initialize(String hostPackage, int hostUid, IVoicePackageControlV1 control);
    Bundle getState();
    void shutdown();
}
```

## Capabilities and acceptance

Companion receives firmware version/code/commit/build details, Android release/build,
installed plugin versions and enabled/loaded state, supported protocol versions and
a separate standalone receiver inventory. Sending is enabled only when the host path
and exactly one receiver are ready. Old or stale responses cannot enable it.

The prototype has automated checks for one-use/expired/foreign handles, signer/live
binding changes, raw-byte integrity, duplicate hashes, package classification,
shared ZIP/JSON intake, rejected imports preserving active config, operator review
and receipt synchronization. Physical ATAK/VX → watch, microphone/headset and complete
VX interoperability still need acceptance testing. Please review the API names and
whether to adopt this extension into the SDK or expose equivalent typed parcels.
