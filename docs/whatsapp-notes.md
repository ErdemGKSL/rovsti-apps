# WhatsApp integration: notes

Status: **resolved (decided 2026-11-05, built in `_bmad-output/plans/wa-gateway.md`).**
WhatsApp is fully backend-based. A gateway service owns the whatsapp-rust sessions,
and every Rovsti app offers two choices: *link WhatsApp for the first time* (QR or
pairing code through the gateway), or *connect to an existing instance* by pasting a
connect key that another app shows. Everything else stays local. The design is in
[architecture.md](architecture.md#whatsapp-gateway); running it is in
[whatsapp-gateway.md](whatsapp-gateway.md). The questions below were the open
ones; each now has its answer. The original analysis follows for context.

| Question | Resolution |
| --- | --- |
| Who owns the session? | The gateway, not an app and not Contacts. An *instance* is shared by every app through a connect key (AD-WA1, AD-WA2). |
| One session, one process? | Yes: one whatsapp-rust `Bot` per instance on the gateway, so the phone uses no companion slot per app and no battery for a socket. |
| Calls? | Server-side through whatsapp-rust's `voip-mlow`. The phone only supplies microphone and speaker over a WebSocket (AD-WA5). |
| Waking the app? | FCM data-only push from the gateway, optional and config-gated, with no message text (AD-WA6). No foreground service. |
| `CLAUDE.md` says no backend | Updated: local except WhatsApp. The gateway is the user's own server. |
| Terms of service | Unchanged risk, now documented: [whatsapp-gateway.md](whatsapp-gateway.md#terms-of-service). |
| Moving the session out of Messages | Done: `rovsti_whatsapp` is removed; the session code lives in `services/wa_gateway`. |

Not verified on a device: linking a real account, a real call's audio quality and
FCM delivery on HyperOS all need hardware. Only checks and unit tests ran here.

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
