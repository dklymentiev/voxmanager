# Google Play, Data safety form answers

Draft answers for the Play Console "Data safety" section. Verify against the final
build before submitting.

**Does your app collect or share any of the required user data types?**
- **No**: Vox Manager has no backend; it does not collect or share data with the
  developer or third parties.

**Data processed on the device / not leaving the user's control:**
- **Audio:** captured transiently for speech recognition by the device's speech
  service; not stored by the app, not sent to the developer. (If the device speech
  service sends audio to its provider, that is governed by that provider, not us, 
  disclose this in the listing/privacy policy, as we do.)
- **Recognised text:** sent only to the user's own paired PC on the local network.

**Is all collected data encrypted in transit?**
- **Yes.** Pairing (the secret key exchange) is encrypted with X25519 + HKDF-SHA256
  + AES-256-GCM. Every working message is likewise encrypted with AES-256-GCM
  (per-link key derived from the paired secret) and signed with HMAC-SHA256 in both
  directions. Note for reviewers: the encryption is at the payload layer rather than
  TLS, because a LAN self-signed certificate offers nothing to pin against at
  pairing time; no plaintext crosses the network, and no data leaves the user's
  local network or reaches us.

**Data deletion:** no accounts and no server data, so there is nothing to delete on
our side. Local pairing data is removed when the user unpairs or uninstalls.

**Account creation:** none.
