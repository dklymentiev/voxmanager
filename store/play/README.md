# Google Play, submission checklist

App: **Vox Manager** (`com.voxmanager`). This folder holds everything Play Console asks
for. Tick items as they're finalised.

## Text (see `listing.md`)
- [ ] App name (≤30 chars)
- [ ] Short description (≤80 chars)
- [ ] Full description (≤4000 chars)
- [ ] "What's new" / release notes (≤500 chars), keep in sync with `../../CHANGELOG.md`

## Graphics (see `graphics/README.md`)
- [ ] App icon, 512×512 PNG, 32-bit
- [ ] Feature graphic, 1024×500 PNG/JPG (no alpha)
- [ ] Phone screenshots, 2-8, PNG/JPG, 16:9 or 9:16, min side ≥320px

## Compliance
- [ ] **Privacy policy URL** (host `privacy-policy.md` publicly), REQUIRED
- [ ] Data safety form, see `data-safety.md`
- [ ] Content rating questionnaire, expected **Everyone** (utility, no objectionable content)
- [ ] Target audience: adults / general (not designed for children)
- [ ] Permissions justification: `RECORD_AUDIO` (dictation), `INTERNET` (LAN typing),
      `FOREGROUND_SERVICE_MICROPHONE` (keep recognising while in use)

## Build / signing
- [ ] Release **AAB** signed with the upload key (`assembleRelease` / `bundleRelease`)
- [ ] `versionCode` incremented, `versionName` matches `CHANGELOG.md`
- [ ] minSdk 28 / targetSdk 34 (raise targetSdk to meet Play's current requirement
      before submission if needed)

## Account
- [ ] Google Play Developer account ($25 one-time)
- [ ] App created in Play Console, package `com.voxmanager`
