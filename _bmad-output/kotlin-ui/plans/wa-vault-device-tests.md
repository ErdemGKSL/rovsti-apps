# WhatsApp vault: device test plan

What the JVM tests cannot prove (plan `wa-vault.md` §15, rounds 4 to 6). Run on a real phone with a Class 3
biometric (fingerprint or face) and a screen lock; repeat the marked items on a HyperOS phone. Use a test WhatsApp
account and test numbers only (`+1000000000xx`). Build release APKs (`scripts/kotlin-ui-check.sh`), plain or firebase
flavor as the item says. Write the result next to each box (date, phone, build, pass or what happened).

Shell helpers (the app's data is private; use `run-as` only on a debuggable build of the same commit, otherwise
`adb root` on an engineering phone, or a file manager with root):

```
pkg=dev.rosty.messages   # dev.rosty.phone, dev.rosty.contacts, dev.rosty.stops
adb shell run-as $pkg ls -la files no_backup cache shared_prefs
adb shell run-as $pkg grep -rl "<known message text>" . || echo clean
```

## 0. Before you start

- [ ] Install the previous release (before the vault) of all four apps, link WhatsApp in Messages, hand the link to
      Phone and Contacts (the old automatic hand-over), paste the key into Stops. Send and receive a few messages
      with a known text (for example `vault-probe-17`), open a photo, set a draft, mute one chat, set a "Remind me"
      on a WhatsApp message, receive a WhatsApp call in Phone, open a WhatsApp picture full size in Contacts.
- [ ] Record what is on disk per app (`ls` above): `whatsapp-link.json`, `wa-sync-*.db`, `wa-media/`,
      `wa-status/`, `wa-avatars/`, `wa-live-names.json`, `wa-identity.json` (Phone), `cache/wa-full`,
      `shared_prefs/wa-link-share.xml` (`declined`), `shared_prefs/rosty_message_reminders.xml` (the message text).

## 1. Migration from an old install

- [ ] Update all four apps to the vault build without uninstalling.
- [ ] Messages opens; the WhatsApp tab shows the vault setup (not the chats). SMS works as before.
- [ ] Before the setup: `whatsapp-link.json` is still there (it is the setup's source); the old store, media,
      Status, avatars, names folders are already gone if the app reached "no link" or an unlock, otherwise still
      there: note which.
- [ ] Finish the setup (passphrase shown once, two groups typed back, the biometric prompt "Protect", the second
      prompt "Turn on quick unlock"). The chats come back from the gateway.
- [ ] Within a few seconds of the unlock: none of the files of section 0 is left in Messages; `drafts.json` keeps
      the SMS drafts only; `messages-prefs.json` keeps SMS mutes only (the WhatsApp chat is still muted in the
      inbox, from WhatsApp itself); the WhatsApp reminder is still armed, its notification names the chat and says
      "Open the message" (no text); `grep` for `vault-probe-17` finds nothing.
- [ ] Phone: set up its vault (or take the link from unlocked Messages, section 8). After the first unlock
      `wa-identity.json` is gone and a LID caller is still named (the table moved into the vault cache);
      `wa-live-names.json`, `cache/wa-full`, `whatsapp-link.json` are gone.
- [ ] Contacts and Stops: the same; `people.json`, `people-sync.json` and `files/people/` stay in Contacts
      (and Phone without Contacts).
- [ ] An app whose WhatsApp was never set up after the update (for example Stops left alone): note that its
      `whatsapp-link.json` stays until its setup or "Forgot passphrase" (known limit, plan §15 round 6).
- [ ] Kill and restart each app: nothing reappears; the migration does not run again (`rosty_wa_migration.xml`
      has `plaintext_wiped=true`).
- [ ] Optional: rotate the connect key in the WhatsApp settings ("Show connect key" mints a new one; revoke the old
      ones in the key list), since flash deletion is not provable erasure.

## 2. BiometricPrompt in every app

For Messages, Phone, Contacts and Stops each:

- [ ] Setup: the "Protect" prompt shows over the app (no black window, no crash after rotation during the prompt).
- [ ] Daily unlock: the WhatsApp page asks once automatically, the biometric opens it; Cancel leaves the unlock page
      with the biometric button.
- [ ] Passphrase unlock: a typo is caught before any prompt; a wrong passphrase costs one prompt per try.
- [ ] A phone with only a screen lock (remove all fingerprints and face): the daily unlock uses the screen lock; enrol
      a fingerprint again: the next unlock asks for the passphrase once ("A fingerprint or face was added").
- [ ] Five wrong fingers: the daily unlock ends, the passphrase is asked, with the reason shown.

## 3. Enrolment change

- [ ] With the vault set up and the daily unlock on, add a new fingerprint in system settings. Each app: the next
      unlock asks for the passphrase ("A fingerprint or face was added"), then offers quick unlock again; the vault
      itself is not lost (K_hw survives the enrolment).
- [ ] Remove the screen lock entirely: each app shows the broken vault page ("Forgot passphrase" path); a reset and a
      new link work.

## 4. Reboot

- [ ] Reboot with all four apps set up. Without opening an app: `wa-vault-daily.json` is gone from each (the boot
      receivers of Messages, Phone and Stops; Contacts at its next refresh).
- [ ] Each app asks for the passphrase after the reboot ("The phone restarted"), then quick unlock works again.
- [ ] A WhatsApp message pushed before the first unlock after the reboot shows the content-free notice (section 6).

## 5. Lock lifecycle

- [ ] Screen off locks at once in every app (WhatsApp pages show the unlock page when the screen comes back).
- [ ] Background delay: set 30 s, leave the app 20 s, come back: still open; leave it 40 s: locked. Repeat with
      "At once" and 5 min. A cached (frozen) process locks when it comes back after the delay.
- [ ] "Lock WhatsApp now" in the WhatsApp settings locks and takes away every notification that shows WhatsApp text.
- [ ] The system prompt itself (biometric, screen lock) does not start the background timer.
- [ ] After a lock: no request reaches the gateway (gateway log shows no requests from the device until the unlock).
- [ ] Kill the process while unlocked: the next start is locked; the memfd media files are gone with the process
      (`ls /proc/<pid>/fd` of the new process shows none).

## 6. Notifications while locked

- [ ] Messages locked (firebase build): a WhatsApp message shows one notice "WhatsApp", "N new messages", no
      reply action, visible on the lock screen; the count grows with more messages and resets at the unlock.
- [ ] Unlocked: the normal per-chat notifications with text; the inline reply works; "Remind me in 1 hour" on a
      WhatsApp notification arms a reminder whose notification shows no message text.

## 7. Locked call notice on HyperOS (repeat on stock Android)

- [ ] Phone locked, a WhatsApp call rings: the CallStyle notice "WhatsApp call" (no name, no number), no
      full-screen ring, visible on the lock screen, on channel `wa_calls`.
- [ ] Answer from the lock screen: the screen unlock, then the WhatsApp unlock page, then the call is answered if
      the gateway still rings; otherwise the missed notice (content-free) follows.
- [ ] Decline: the ring stops here; after the gateway's 60 s the call shows as missed.
- [ ] HyperOS: the notice shows at all (no autostart or battery restriction hides it); note the required settings.

## 8. Hand-over between two installed apps

- [ ] Messages unlocked, Phone without a link (never linked before): opening Phone's WhatsApp link screen goes
      straight to Phone's setup with Messages' link, no tap (round 7a), and links without a QR.
- [ ] Unlink in Phone, open its link screen again: it now offers "Use the link from Messages" and takes it only on the
      tap.
- [ ] Messages locked: Phone offers nothing; Messages killed: Phone offers nothing.
- [ ] A third-party app (different signature) querying `content://dev.rosty.messages.walink/link` (and
      `dev.rosty.stops.walink`) gets a SecurityException.
- [ ] Stops takes part since round 7a: Stops without a link takes Messages' link the same way.

## 8a. One prompt for the four apps (round 7a)

- [ ] All four apps set up, all locked. Unlock Phone with the fingerprint, then open Messages' WhatsApp tab within the
      auto-lock delay: it opens with no prompt. Same for Contacts and Stops. No two prompts ever follow each other.
- [ ] The same right after a reboot: type Phone's passphrase, then open Messages: no passphrase and no prompt in
      Messages. Later, with Phone locked, Messages alone asks for its own passphrase once (its daily unlock was dropped
      by the reboot).
- [ ] Phone unlocked but left in the background longer than its auto-lock delay (or the screen turned off): Messages
      prompts as before (the window is the source's own lock rules).
- [ ] "Lock WhatsApp now" in Phone: Messages, Contacts and Stops lock too (their WhatsApp pages show the unlock page),
      and none of them reopens another one silently.
- [ ] A vault set up before round 7a: the first hand-over needs one normal unlock of that app (it then writes its link
      wrap); after that the hand-over works.
- [ ] A sibling that is cold (killed) answers nothing and is not kept alive; note the delay before the prompt when
      three siblings are cold (each provider starts its process).
- [ ] `wa-vault.json` holds a `link_sealed` field; no file of any app holds the connect key or a message in plaintext
      (grep the data dirs for `rwk_` and a known message text after an unlock by hand-over).

## 8b. Passphrase only every 30 days with daily use (round 7a)

- [ ] Unlock with the fingerprint, set the clock forward 70 h (or wait), unlock again with the fingerprint, then another
      70 h: no passphrase is asked (the 72 h count from the last biometric unlock).
- [ ] Leave the phone 73 h without any unlock: the passphrase is asked ("WhatsApp wasn't unlocked for three days").
- [ ] Keep unlocking daily with the fingerprint: 30 days after the last passphrase the passphrase is asked ("The
      passphrase was last typed 30 days ago").
- [ ] A phone with only a screen lock: the screen-lock unlock does not move the 72 h on (they count from the
      passphrase).
- [ ] Reboot, a new fingerprint and five wrong fingers still ask for the passphrase at once.

## 8c. History after unlock (Messages, round 7a)

- [ ] Use WhatsApp in Messages, lock (screen off), unlock: the chat list and the recent messages of the last chats show
      at once (no empty screen), then the list and the open chat catch up; the gateway log shows no more requests than
      before (at most 4 at the cold start, 1 page per opened chat).
- [ ] Kill Messages after sending it to the background (swipe away): the next unlock still shows the history.
- [ ] `wa-vault-history.bin` exists and holds no readable text (`strings` shows no message text); full media is not
      in it (tapping a photo downloads it again); thumbnails show where they fit.
- [ ] "Forgot passphrase" and Unlink delete `wa-vault-history.bin`; a passphrase change deletes it too.

## 8d. Muted conversations (Messages, round 7a)

- [ ] Mute a WhatsApp chat in WhatsApp (or from the inbox menu). With the vault open, Settings, Notifications, "Muted
      conversations" lists it next to the muted SMS conversations; Unmute there unmutes it in WhatsApp (the inbox bell
      goes).
- [ ] With the vault locked: one row "Unlock to see WhatsApp chats"; the tap shows the biometric prompt (or opens
      the WhatsApp page for the passphrase) and the list follows once it is open.

## 9. Trip message after a lock (Stops)

- [ ] Start a trip with an arriving-soon message while unlocked, lock WhatsApp (screen off), wait for the trigger:
      the message is sent.
- [ ] Start a trip while locked: the message row under Start is hidden, no message is sent.
- [ ] Kill Stops during a trip that holds the link: the resumed trip sends no message.
- [ ] End the trip: a later lock and unlock do not send anything.

## 10. People sync deferral (Contacts, Phone without Contacts)

- [ ] Lock WhatsApp and wait past a 12-hour period (or run the work with `adb shell cmd jobscheduler run`): the run
      ends without a gateway request (log `worker_skipped reason=vault_locked`).
- [ ] Unlock: one run starts only when the last one is older than the period; no extra run otherwise.

## 11. memfd and SELinux

- [ ] Messages unlocked, tap a photo, a video, a voice note, a document: each opens and plays from
      `/proc/self/fd/<n>` (no "file not found", no SELinux denial in `adb logcat | grep avc`).
- [ ] Send a photo and a document from the gallery: they send; the document bubble name is right after the echo.
- [ ] Share or save a received photo: the explicit export works (it is the only plaintext copy, in the chosen place).
- [ ] HyperOS: the same; note any `memfd_create` denial.

## 12. FLAG_SECURE

- [ ] Messages: with the vault open, a screenshot and the recents thumbnail of the whole window are blocked; with it
      locked, SMS screens can be screenshotted, the WhatsApp unlock page cannot.
- [ ] Phone: the whole window is secure while WhatsApp is unlocked (Recents shows WhatsApp calls); plain while locked.
- [ ] Contacts and Stops: the WhatsApp pages and every vault page (setup with the passphrase, unlock, change) are
      secure.
- [ ] Screen recording and casting show black for the secure windows.

## 13. Gateway request budget

- [ ] With the gateway log at debug, a cold start and unlock of Messages makes at most 4 requests (socket, status,
      one identity page, chat list); opening a chat 1 page; scrolling up 1 page per scroll; no media without a tap.
- [ ] Turn flight mode on and off a few times: at most one cold sync per 30 s; back-off on errors.
