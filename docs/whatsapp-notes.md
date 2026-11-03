# WhatsApp integration: open notes

Status: **notes only, nothing decided.** These ideas need proper planning (BMAD)
before anyone builds them.

## Owner's direction (2026-11-03)

- **Rovsti Contacts owns the WhatsApp session.** Phone and Messages use it, and
  neither opens its own.
- **WhatsApp calls in Phone.** There is no server today, so the question is how
  an incoming WhatsApp call wakes the app. The owner's idea is a small backend
  that sends an FCM message to wake the app, which then shows the call through
  `whatsapp-rust`.

## What we know so far

- **One session, one process.** A linked device's identity is its key store
  (Signal sessions, pre-keys, device JID). Two processes using the same store
  would corrupt the ratchets, and WhatsApp keeps one socket per device, so a
  second login replaces the first. Linking each app separately costs one of
  the four companion slots per app and doubles battery use. The usual shape is
  one owner process with an IPC API: a bound service, guarded by a
  `signature`-level permission so only apps signed with the Rovsti key can bind.
  Phone and Messages call it through `rovsti_android`.
- **Calls.** The whatsapp-rust 0.7 README advertises 1:1 VoIP audio calls,
  and the crate has `voip` and `handlers/call.rs` modules (offer, reject,
  terminate, relay media). We have not checked the API, its feature flags or
  the audio path (codec, `AudioRecord`/`AudioTrack`, Telecom
  `ConnectionService` so it rings like a real call).
- **Waking the app.** Call offers arrive on the linked device's own socket.
  Only a process holding that socket learns that a call is ringing. The two
  ways to be holding it at the right time:
  1. **On the device:** a foreground service keeps the socket open. It is
     already planned for Messages (`dataSync`). This needs no server, but costs
     battery, and MIUI/HyperOS kills background apps unless autostart and
     battery exemptions are granted (see `docs/xiaomi.md`).
  2. **Server push (the owner's idea):** WhatsApp does not send FCM to
     third-party clients, so our backend would have to run its *own* always-on
     linked-device session to see the offer, then send FCM to the phone. That
     means the account's keys live on a server, and the server uses one of
     the four companion slots. It is also a second client racing the phone's
     client to answer.

## Conflicts to resolve before planning

- `CLAUDE.md` and the README say "everything is local and serverless, do not
  add a backend". A push backend changes that rule. Update those files, and
  decide where the server runs, who operates it and what it may store.
- Unofficial clients may violate WhatsApp's Terms of Service. A server-side
  session makes the account more visible to WhatsApp's enforcement.
- Moving the session from Messages (where `rovsti_whatsapp` and the
  pairing UI live today) to Contacts also moves the foreground service.

## Next step

Run `/bmad-forge-idea` or `/bmad-architecture` on "WhatsApp session owner +
call wake-up". Spike first: does an on-device foreground socket alone ring
reliably on the owner's Xiaomi? If it does, no backend is needed.
