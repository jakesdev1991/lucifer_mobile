# TextNow on a Pixel 8a without root — and without giving Play Services power

Status: **documentation plus one code change.** `privacy/PlayEnvironment.kt` now reports whether Play
Services is a *system* app (the actual measure of "power over the phone"), and Settings → Privacy shows
it. The classifier is host-verified; the panel has not been compiled or run on a device.

The request: *"I can't root this device, I'd like TextNow, but it won't work without Google Play
Services, and I don't want Play Services having power over my phone."*

## Three facts that decide this, in order

**1. You do not need root for any of this.** Root and bootloader unlock are different things. Every
option below works on a stock, unrooted Pixel 8a. GrapheneOS installs through a browser-based installer
that never grants root, and it **re-locks the bootloader** afterwards, which restores verified boot.
"Can't root" rules nothing out.

**2. TextNow is on GrapheneOS's own list of apps that ban it.** The project maintains a public
"apps banning GrapheneOS with the Play Integrity API" list, and TextNow is on it. This is not a
configuration problem and sandboxed Play does not solve it: TextNow asks Google for a *device* verdict,
GrapheneOS cannot produce one, and the app refuses. A GrapheneOS user on r/androidapps reports exactly
this — Play Services installed, and the Play Store still refuses them.

**3. microG is not available to you either, and would not help.** Installing microG needs signature
spoofing, which needs a ROM that allows it (LineageOS-for-microG, CalyxOS). You cannot bolt microG onto
stock Android without root. And even then, microG fails Play Integrity the same way, so TextNow would
most likely refuse there too. On top of that, flashed microG builds land in `/system/priv-app` — **the
same privileged placement you are trying to avoid.** Going microG here buys the downside without the
app working.

So the honest conclusion: **keep TextNow, or keep Play Services out of a position of power. On this
device you cannot cleanly have both.** Pick a strategy, don't fight the app.

## What actually makes Play Services "have power"

This is measurable, so Lucifer now measures it. The question is not whether Play Services is installed
but **how**:

| Placement | Reach | Revocable? |
| --- | --- | --- |
| `/system/priv-app` on stock Android | Platform-level: privileged permissions a normal app cannot hold | No. You cannot uninstall or fully disable it. |
| Ordinary app (GrapheneOS sandboxed Play, or microG installed as a user app) | Nothing beyond a normal app's permissions | Yes: network, sensors, storage, background, per app. |

Settings → Privacy now prints `PRIVILEGE: SYSTEM APP …` or `PRIVILEGE: ordinary app …`, read from the
`FLAG_SYSTEM` bit on the GMS package. That line is the answer to your concern, from the device itself
rather than from a blog post.

## The four real options, ranked for your constraint

### Option A — TextNow's web client, no Play Services at all (best fit for "keep my number")

TextNow runs a full web client at `textnow.com`: sign in, and your number, threads and contacts sync
with the app. It needs **zero** Play Services, zero root, and works in any browser — including
GrapheneOS's Vanadium or stock Chrome.

- **Costs:** no background push. You keep the tab open and allow browser notifications; the mobile web
  UI is not as smooth as the app; calling needs microphone permission and is less reliable; voicemail
  is better in the app.
- **Keeps:** your existing TextNow number and message history.
- **Do this today, on the phone as it is now.** It is the only option that works on a stock,
  unrooted, unflashed Pixel 8a with no Play Services involvement whatsoever.
- **Practical trick:** add `textnow.com` to your home screen so it launches like an app, and exempt the
  browser from battery optimisation, or the tab will be killed in the background.

### Option B — Move the number to a provider that never needed Google (best long-term)

TextNow's dependency is not cosmetic: its push, and in TextNow's case its integrity check, are Google's.
A provider built on XMPP has neither problem.

**JMP.chat** (Soprani.ca) gives you a real US or Canadian number and does SMS, MMS and voice calls
entirely over Jabber/XMPP. It works with **Cheogram** or Conversations from F-Droid, needs no Google
services at all, and **you can port your existing number into it**. Notifications arrive over the app's
own connection or UnifiedPush, not FCM. Multiple numbers per account are supported, one Jabber ID each.

- **Costs:** a subscription (unlike TextNow's free tier), XMPP contact addressing for new numbers
  (`+1416…@cheogram.com`), and MMS/group behaviour that is good but not identical to carrier SMS.
- **Gains:** no Play Services, no Play Integrity, no Google in the push path, works identically on
  GrapheneOS, CalyxOS or stock.
- **Why this is the recommendation if you want GrapheneOS:** it removes the *reason* you needed Play
  Services, instead of trying to contain it.

I have not verified JMP's current pricing or that every TextNow feature survives a port — confirm both
with them before porting, and be aware ports take days and can fail.

### Option C — GrapheneOS + sandboxed Play, with TextNow on a second device

If TextNow's number matters more than its on-device presence: put GrapheneOS on the Pixel 8a (no root;
bootloader relocked), install sandboxed Play *only* in a separate profile if you need it for something
else, and keep TextNow on the old phone or a cheap Wi-Fi handset.

- Sandboxed Play gives Google **no** system privileges — the property you asked for — and you can put it
  in a profile with its network access revoked when idle.
- TextNow lives on hardware where it is happy, and you are not giving Google a privileged foothold on
  the phone that holds your real life.
- This is the option that satisfies *both* constraints, at the cost of carrying two devices.

### Option D — Stay on stock Android and just contain Play Services

If neither of the above is acceptable yet: you cannot revoke a system app's privileges, but you can
narrow it — restrict background data for Play Services, deny it location, keep location off system-wide,
use a separate user profile for anything Google-dependent, and review what Lucifer's ledger shows
leaving the device. This is damage limitation, and it is honest about being that.

**Do not choose:** flashing LineageOS/CalyxOS for microG *in order to make TextNow work.* TextNow will
very likely still refuse, and you will have spent a wipe, an unlocked bootloader and a privileged
FLOSS Play stack to get there.

## What I changed in the code

- `privacy/PlayEnvironment.kt` now reports the privilege line above, from `FLAG_SYSTEM` on the GMS
  package, and the mirror spec covers five cases including "flashed microG is still a system app".
  That last check caught a real branch-order bug while I wrote it: the microG-specific warning was
  unreachable, so a flashed microG build would have been described with the generic Google message.
- Nothing in Lucifer depends on any of this. The app works with no Play Services at all — which is why
  Option A and B are viable at all.

## Verification

```bash
python3 tools/privacy-core/spec_check.py            # 92 checks, incl. 5 privilege cases
python3 tools/privacy-core/audit_egress.py --check  # unguarded sockets, Google dependencies
./scripts/verify_privacy_core.sh
```

Claims about TextNow/GrapheneOS come from GrapheneOS's own attestation-compatibility list
(`grapheneos.org/articles/attestation-compatibility-guide`, "Apps banning GrapheneOS") and from user
reports on r/androidapps and r/degooglе. Claims about JMP.chat come from jmp.chat's FAQ. Re-check both
before you wipe a phone or port a number — the ban list changes, and so does the headcount of what works.
