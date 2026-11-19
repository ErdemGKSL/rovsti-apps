# WhatsApp gateway: running it and linking an account

The gateway (`services/wa_gateway`) owns every whatsapp-rust session. The apps only
talk to it through `rosty_wa_client`. Design and API: `_bmad-output/plans/wa-gateway.md`
and `docs/whatsapp-notes.md`. This page covers what you do by hand.

## Run it locally

```
cargo run -p wa_gateway
```

It serves plain HTTP on `127.0.0.1:8787` and keeps its data in `./wa-gateway-data`:

- `gateway.db`: instances, hashed connect keys, push tokens, chats and messages.
- `instances/<instance id>/wa.db`: one whatsapp-rust store per linked account (the
  Signal keys). Losing it unlinks that account. Back it up with the rest of the
  directory.

Settings come from an optional TOML file (`--config gateway.toml`) and from
`WA_GATEWAY_*` environment variables, which win. `cargo run -p wa_gateway -- --help`
lists them. `RUST_LOG=debug` turns on more logging.

Put a TLS reverse proxy in front of it for anything beyond your own machine (see
below). An app accepts `http://` only for loopback, `10.0.2.2` (the Android
emulator), private ranges and `.local` hosts.

## Deploy

Ready-made files live in [`services/wa_gateway/deploy/`](../services/wa_gateway/deploy):

- `Dockerfile`: multi-stage build (`rust:1.94-bookworm`, then `debian:bookworm-slim` with
  `ca-certificates`), runs as a non-root user, keeps its data in the `/data` volume and
  exposes 8787. From the repository root:
  `docker build -f services/wa_gateway/deploy/Dockerfile -t wa-gateway .`, then
  `docker run -d -p 127.0.0.1:8787:8787 -v wa-gateway-data:/data wa-gateway`. Mount a
  config file and add `--config` to use one (the image sets `WA_GATEWAY_BIND` and
  `WA_GATEWAY_DATA`, which win over the file).
- `wa-gateway.service`: a hardened systemd unit (dedicated user, `StateDirectory=`,
  `NoNewPrivileges=yes`, `ProtectSystem=strict`). Copy the binary to
  `/usr/local/bin/wa_gateway` and `config.example.toml` to `/etc/wa-gateway/config.toml`.
- `Caddyfile.example`: TLS and WebSocket proxying, with a note on `trust_proxy`.
- `config.example.toml`: every key with its default. A unit test parses it with the real
  loader, so it cannot drift from `config.rs`.
- `remote-install.sh`: the half of the script below that runs on the server.

### One command to the Raspberry Pi (no Docker)

`scripts/deploy-wa-gateway.sh` builds the gateway on the server and runs it as the
`wa-gateway` systemd service. It connects as `erdem` to `ssh.erdemdev.tr` through
`cloudflared access ssh`, sends a 300 KB bundle (the gateway and the three workspace crates
it needs, not the apps), builds it there with cargo (it installs rustup for that user the
first time), installs `/usr/local/bin/wa_gateway` and the unit from this folder, writes
`/etc/wa-gateway/config.toml`, and waits for `GET /healthz`. The gateway listens on
`127.0.0.1:7218`; a Cloudflare tunnel public hostname (`https://rosty.erdemdev.tr`, the apps'
default) points at `http://localhost:7218`. The old name `rovsti.erdemdev.tr` routes to the same
gateway and stays until every linked app has moved over.

```
scripts/deploy-wa-gateway.sh            # build and (re)install
scripts/deploy-wa-gateway.sh status     # service, listener, health, config
scripts/deploy-wa-gateway.sh logs       # journal (LINES=300 for more)
scripts/deploy-wa-gateway.sh rollback   # previous binary
scripts/deploy-wa-gateway.sh --dry-run  # connection and bundle check only
```

- The SSH key is `~/.ssh/erdem_rpi_deploy.pem` (`DEPLOY_KEY`); on Windows only your own
  account may be able to read it (`icacls key /inheritance:r /grant:r "%USERNAME%:(R)"`).
  The script uses the Windows OpenSSH client, which accepts that ACL.
- The existing `/etc/wa-gateway/config.toml` is kept on later deploys (`FORCE_CONFIG=1`
  rewrites it). `trust_proxy = true` is right behind the tunnel: the gateway reads the last
  `X-Forwarded-For` entry, which Cloudflare appends, and the port is loopback-only.
- FCM push: the service-account key is uploaded to `/etc/wa-gateway/service-account.json`
  (root only, mode 0600) and handed to the service with `LoadCredential=` in a drop-in
  (`/etc/systemd/system/wa-gateway.service.d/10-rosty.conf`), so the unprivileged service
  user never owns the file. The project id goes into `config.toml`. The installer removes
  the drop-in's pre-rename name (`10-rovsti.conf`) so only one is applied.
- The workspace's release profile is tuned for Android size (fat LTO). The server build
  overrides it (no LTO, `opt-level` 2) so a Raspberry Pi finishes in a reasonable time.
- Data lives in `/var/lib/wa-gateway`. Back it up: it holds the linked accounts' keys.

## Configuration

| TOML key | Environment | Default |
| --- | --- | --- |
| `bind` | `WA_GATEWAY_BIND` | `127.0.0.1:8787` |
| `data_dir` | `WA_GATEWAY_DATA` | `./wa-gateway-data` |
| `max_instances` | `WA_GATEWAY_MAX_INSTANCES` | `4` |
| `fcm_project_id` | `WA_GATEWAY_FCM_PROJECT` | unset (push off) |
| `fcm_service_account` | `WA_GATEWAY_FCM_SA` | unset (push off) |
| `trust_proxy` | `WA_GATEWAY_TRUST_PROXY` | `false` |
| `full_history` | `WA_GATEWAY_FULL_HISTORY` | `true` |

Creating an instance needs no key, so anyone who can reach the gateway can create one
up to `max_instances`. Keep the address private or behind the proxy's own access
rules. Set `trust_proxy = true` only behind a proxy that sets `X-Forwarded-For`;
failed-key rate limiting is per client IP. `full_history` is explained under
[History sync](#history-sync-recent-or-full).

## TLS reverse proxy

The gateway speaks plain HTTP. Terminate TLS in front of it and forward WebSocket
upgrades (`/v1/events` and `/v1/calls/{id}/audio`). With Caddy, which handles both:

```
wa.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

With nginx, add `proxy_http_version 1.1`, `proxy_set_header Upgrade $http_upgrade`,
`proxy_set_header Connection "upgrade"` and a long `proxy_read_timeout` (an hour).
Then enter `https://wa.example.com` as the gateway address in an app. The connect key
is a bearer secret: anyone with it can read and send as the account, so keep the
gateway on HTTPS and revoke keys you no longer use.

### How the apps keep the key

The Rosty Kotlin apps never store the connect key in plaintext. Each app seals it in its own
WhatsApp vault (`wa-vault.json`): encrypted with a data key that opens only with the app's
passphrase and this phone's Keystore key, kept in memory only while WhatsApp is unlocked, and
dropped on lock ("Lock WhatsApp now", or 90 days without a use; since round 7c the vault stays
unlocked and reopens with no prompt once the phone is unlocked, also after a reboot). Messages keeps no
message, thumbnail or media on disk; it refetches from the gateway after each unlock, within a
fixed budget (at most 4 requests per unlock, 1 page per opened chat, media only on a tap, no
request while locked). The vault itself needs no gateway change; the one server-side crypto is
the sealed push below (round 7b), which encrypts *to* the phone. The gateway itself still stores chats and messages in `gateway.db` (it is the user's own
server). Details: [architecture.md](architecture.md#whatsapp-vault-ad-wa12).

## Push (FCM)

Push wakes the phone for incoming calls and messages. It is off unless
`fcm_project_id` and `fcm_service_account` (the path of a Firebase service-account
JSON) are both set. The gateway then sends FCM HTTP v1 data-only, high-priority
messages with no message text:
`{"kind":"call","call_id","sender","name"}`, `{"kind":"message","chat","count"}` or, when
a ring that was pushed ends unanswered here,
`{"kind":"call_end","call_id","missed","sender","name"}` (`missed` is `"1"` or `"0"`).
The caller is under `sender`, not `from`: FCM reserves `from` and refused those pushes
with 400 "Invalid data payload key: from". The apps still read `from` from older gateways.
The apps must be built with Firebase for this; see
[android.md](android.md#whatsapp-push-and-call-audio). An app registers its token
with the gateway on connect (`POST /v1/push`); apps built without Firebase register
nothing and rely on their open event socket. A push makes no request by itself while an
app's WhatsApp vault is locked; the apps fetch only after the unlock.

### Sealed push (round 7b)

An app install that has a push key registers its public half with the token, on the same
request (`PUT /v1/push`, `PushKeyedRegistration`):

```json
{"app":"messages","token":"<fcm token>",
 "push_key":{"key_id":"<[A-Za-z0-9_-]{1,64}>","version":1,"public_key":"<base64 X.509 SPKI, P-256>"}}
```

The private half is an EC P-256 Keystore key on the phone (non-exportable, usable while the
phone is locked); the app deletes it on a vault reset or unlink and registers a new one at its
next link. The gateway refuses a malformed key or another version with 400 `invalid push key`;
a registration without `push_key` forgets any stored key. Keys are stored per instance and app
in `push_tokens` (`key_id`, `key_version`, `public_key` = the 65-byte point; schema v10).

To a token with a key, every push is sealed (scheme 1): a fresh P-256 key pair per push,
z = ECDH(ephemeral, device), k = HKDF-SHA256(salt = ephemeral point, ikm = z,
info = `rosty-wa-push/1` | device point), AES-256-GCM with a random 12-byte nonce and AAD
`rosty-wa-push/1|<kid>`. The FCM data map is:

| key | value |
| --- | --- |
| `kind` | `message`, `call` or `call_end` (clear: routes the push) |
| `call_id` | the opaque call id (calls only, clear: a push that cannot be opened still rings) |
| `missed` | `1`/`0` (call ends only, clear) |
| `v` | `1` |
| `kid` | the key id it was sealed to |
| `epk` | the ephemeral public point, base64 (65 bytes) |
| `ct` | base64 of nonce (12) \| ciphertext \| tag (16) |

The sealed JSON of a message is `{chat, chat_alt?, chat_name?, group?, sender?, sender_alt?,
name?, text?, id, ts, count}` (`text`: the whole text, else the caption, else a media label, cut
to 1024 bytes and further until the map is at most 3800 bytes; `chat_name` is the stored chat
name, one local read per push and only when a keyed token exists); a call or call end seals
`{sender, name?}`. Google sees no chat id, number, name or text. Tokens without a key get the
content-free payload above. The apps open it in memory: Messages shows the sender and the
preview while its vault is locked (as the "WhatsApp previews" setting allows; the lock screen
shows only "WhatsApp, N new messages"), Phone names a locked ring and opens a locked-call page
over the lock screen. A push that does not open (an old key, tampering) gives the content-free
notice. The gateway never logs the content or a key.

Upgrade: start the new gateway on a backed-up `gateway.db`; migration v10 only adds nullable
columns. Existing tokens keep the content-free push until each app links up again and
registers its key. An older gateway binary refuses a v10 database, so roll back with the backup.

## Calls

Incoming WhatsApp calls arrive as `call` events (`ringing`, `connecting`, `active`,
`ended`). Phone shows a card with Answer and Decline. Answer calls
`POST /v1/calls/{id}/accept`, then opens the audio WebSocket
`/v1/calls/{id}/audio` (binary frames of 960 mono 16-bit samples at 16 kHz, 60 ms)
and starts the microphone; the gateway bridges the frames to whatsapp-rust's voip
module. Mute is `POST /v1/calls/{id}/mute`, End is `POST /v1/calls/{id}/end`. The
phone needs the `RECORD_AUDIO` permission. Audio only; video calls are not handled.

End declines a ringing incoming call and otherwise sends WhatsApp a `<terminate>`,
which also cancels an outgoing call that still rings (whatsapp-rust's
`CallHandle::hangup` alone only stops the local engine). An unanswered outgoing call
is terminated the same way after 60 seconds. An outgoing call stays `ringing` until
the callee accepts: its relay comes up from the offer's ack, before anyone answers. A
`busy` reject from one of the callee's devices does not end it, since the others ring
on. Ending a call twice is a 409 `conflict`, an unknown call a 404.

`GET /v1/calls` (bearer key) returns a JSON array of the instance's calls, newest first:
each item is the `call` event's fields (`call_id`, `peer`, `peer_name`, `direction`,
`phase`, `is_video`) plus `started_ms` and, once over, `ended_ms` (Unix milliseconds).
Phone reads it after a cold start or a push to learn about calls it did not see live.
The table is in memory: ended calls are kept 24 hours, at most 64 calls are held, and a
full table drops its oldest ended call (live calls are never dropped). A gateway restart
empties it.

## Terms of service

WhatsApp does not endorse third-party clients, and running one on your account may
break its Terms of Service. WhatsApp can restrict or ban accounts that use unofficial
clients, and a server-side session that is always online is more visible than a phone
that sleeps. Use a number you can afford to lose, do not use it for bulk or automated
messaging, and keep the gateway private. The gateway holds the account's keys and
stores message bodies: protect its data directory, back it up, and treat the host
like a phone that is always unlocked.

At boot the gateway restarts every instance that is not `logged_out` or `banned`. An
existing `wa.db` signs back in silently, so there is no QR the second time.

## Link by QR

1. Create an instance. The response holds the instance id and the first connect key
   (`rwk_...`). Keep the key: the server stores only its hash.

   ```
   curl -s -X POST http://127.0.0.1:8787/v1/instances \
     -H 'content-type: application/json' \
     -d '{"link":{"type":"qr"},"app":"messages"}'
   ```

2. Read the events. The first frames carry `{"type":"qr","payload":"..."}`; the payload
   rotates about every 20 seconds, so keep listening. Any WebSocket client works
   (`websocat -H "Authorization: Bearer rwk_..." ws://127.0.0.1:8787/v1/events`).
   The apps do this for you and draw the code.
3. Render the payload as a QR code (the apps use `rosty_wa_ui`) and scan it from
   WhatsApp on the phone: Settings, Linked devices, Link a device.
4. When the phone approves, the stream sends `{"type":"state","state":"connected",...}`
   with the account's JID and push name. A history sync follows in chunks, and one
   `history_synced` event per chunk reports what arrived (see
   [History sync](#history-sync-recent-or-full)).

## Link by pairing code

Create the instance with `{"link":{"type":"phone_number","number":"+90 532 123 45 67"},"app":"messages"}`
(a country code is required). The stream sends `{"type":"pair_code","code":"..."}`.
On the phone choose Linked devices, Link a device, then "Link with phone number
instead", and type the code.

## Day to day

- `POST /v1/messages/text`, `POST /v1/read`, `POST /v1/presence`, `POST /v1/chatstate`
  and `POST /v1/presence/subscribe` act on the instance behind the bearer key. A
  malformed JID is a `400 bad_request`.
- `POST /v1/chatstate` takes `{"chat": "<jid>", "state": "typing" | "recording" | "paused"}`.
  `recording` is what a voice-note recorder sends (the library's
  `chatstate().send_recording`); `paused` ends either. The `typing` event names the
  same three states for the other side (`{"type":"typing","chat","jid","state"}`), so
  an app shows "recording…" from it.
- If WhatsApp unlinks the device (from the phone, or a server-side logout) the
  instance goes to `logged_out` and its `wa.db` is deleted. Link again with
  `POST /v1/instance/link`, or delete the instance with `DELETE /v1/instance`.
- A temporary WhatsApp ban shows as `banned`. The gateway does not restart a banned
  instance at boot; relink once the ban is over.

## Event socket: epoch and resume

`GET /v1/events?after=<seq>` replays the events after `seq` from a ring of 1024 per
instance, or sends `resync` when that point is gone. Sequences start at 1 again when
the gateway restarts, so `seq` alone cannot tell two runs apart. The gateway therefore
has an **epoch**: 16 hex digits, new for every process.

- The first frame of every socket is
  `{"seq":<start>,"at_ms":...,"event":{"type":"hello","epoch":"9f2c41d07ab35e68"}}`.
  `seq` is the point the socket starts from: the `after` it resumes, or the newest
  event for a socket without `after` or with one the gateway no longer has. It never
  moves a resume point past an event that was not sent.
- `resync` names it too: `{"type":"resync","epoch":"9f2c41d07ab35e68"}`.
- `GET /v1/instance` and every `state` event carry it as `epoch`.

A device stores the epoch next to its last `seq`. When a frame or the status names
another epoch, everything since its last sync may be missing, whatever `seq` says:
it refetches as for `resync`. A gateway from before this sends no `hello` and a bare
`{"type":"resync"}`; a client from before this skips `hello` and ignores the field.

## History sync: recent or full

After a device is paired, the phone sends the chat history in chunks. Each chunk is
stored (messages already there are kept as they are) and announced once:

```
{"type":"history_synced","chats":12,"progress":40,"sync_type":"recent","messages":3500,"oldest_ms":1500000000000}
```

- `chats`: chats the chunk wrote. `messages`: messages of the chunk that were not
  stored before. `oldest_ms`: the oldest message in the chunk.
- `sync_type`: `initial` (the chat list and newest messages), `recent`, `full` (the
  older messages of a full history), `on_demand`, or `other` (push names, status
  updates and the like, with `chats` 0).
- `progress`: what WhatsApp said for the chunk, 0 to 100 within its `sync_type`, or
  `null`.

`GET /v1/instance` (and every `state` event) reports the whole picture as `history`:

```
"history": {"state":"syncing","progress":55,"chats":120,"messages":48000,"oldest_ms":1500000000000}
```

- `state`: `idle` (no chunk since the account was linked), `syncing`, `done`.
- `progress`: 0 to 100 over the whole sync, never going back while it runs. With a
  full history the recent messages are the first 25 and the older ones the rest;
  that split is the gateway's guess, not a number from WhatsApp.
- `chats`, `messages`, `oldest_ms`: what the gateway holds for the account now, live
  messages included.
- `done` is the last expected chunk at 100 (`recent`, or `full` when a full history
  was asked for). WhatsApp does not say when a sync ends, so a sync that sent nothing
  for 30 minutes also reads `done`; a chunk that arrives later (the phone was
  offline) makes it `syncing` again. The state is kept in `gateway.db` and survives a
  restart.

A chunk is imported in pieces of at most 2000 messages per database transaction, read
from the compressed blob one conversation at a time, so a large history neither holds
the database nor sits in memory whole.

**`full_history` (default `true`)** makes a newly paired device ask for the full
history instead of the recent messages only. For that the device registers the way
WhatsApp's Windows desktop client does (platform type `UWP`, `require_full_sync`, a
365-day limit and the on-demand flags; the library documents these as one set), so
the phone lists it as Windows under Linked devices. How much the phone then sends is
its decision.

These device properties are sent only when a device registers. **An account that is
already linked keeps the sync it was paired with: to get the full history, unlink it
(on the phone, or let it log out) and link it again** with `POST /v1/instance/link`.
A relink of the same account keeps every stored chat and message; the new sync adds
what is missing. Set `full_history = false` to pair as before (recent messages, an
unnamed device).

## Another account on the same instance

An instance keeps its id, keys and push tokens when it is linked again. When the
account that links has **another phone number** than the one the instance holds data
for, the gateway first deletes everything it stored for the old account (chats,
messages, reactions, receipts, edits, chat settings, profiles, status updates,
scheduled texts, identity pairs, and the cached media and profile pictures), raises
`account_epoch` in the instance status and then sends

```
{"type":"account_changed","old_pn":"100000000001@s.whatsapp.net","new_pn":"100000000002@s.whatsapp.net"}
```

An app drops its local copy on that event, or when the status shows another
`account_epoch` than the one it stored. Nothing is deleted when either number is
unknown, and a relink of the same number keeps everything. The check runs when a
pairing completes, on every connect and before a history chunk is stored.

## Is this number on WhatsApp?

`POST /v1/contacts/lookup` with `{"jids": ["905321234567@s.whatsapp.net", ...]}`
answers `[{jid, on_whatsapp, business, verified_name}]`, one entry per distinct JID
in request order. `business` and `verified_name` are optional in the reply.

- Needs the bearer key. Send 1 to 50 user JIDs (`number@s.whatsapp.net`, no
  device part); an empty list, more than 50, a group or any other JID is a 400
  `bad_request`.
- A number WhatsApp does not know comes back with `on_whatsapp: false`. If WhatsApp
  answers with a LID, the answer is reported under the queried number.
- Answers are cached per instance for 24 hours (4096 entries) and served from the
  cache without asking WhatsApp.
- To protect the account from WhatsApp's abuse limits, every number that is not
  cached costs one token of a per-instance bucket (60 tokens, one comes back every
  second). A request that needs more tokens than are left is refused whole with
  429 `rate_limited`; retry later or ask for fewer numbers. Cached numbers are
  always answered.
- WhatsApp's own limit (it answers `rate-overlimit`, code 429) is a 429 too, never
  a 500. See "Rate limits" below for the body, the `Retry-After` header and the
  per-instance cooldown.
- The instance must be connected, else the usual `not_connected` error.

`rosty_wa_client::Gateway::lookup_users(&[String])` wraps it. Apps look up one
contact's numbers when its card opens, never the whole address book.

## Message and chat meta

`GET /v1/chats` and `GET /v1/chats/{jid}/messages` send the old fields plus a `meta`
object (left out when empty), so older clients keep reading them as `ChatSummary` /
`WaMessage`. The types are in `rosty_wa_proto::meta`; the client reads them with
`chats_with_meta()` (`RichChat`) and `messages_with_meta(...)` (`RichMessage`).

A message's `meta` (`MessageMeta`) can hold:

| Field | Meaning |
| --- | --- |
| `quoted` | `{id, sender, preview}` of the message this one replies to |
| `forwarded`, `mentions` | forwarded flag; JIDs mentioned with `@` |
| `edited_ms` | last edit (the body already holds the new text) |
| `revoked` | deleted for everyone; the body is `unsupported`, nothing else is kept |
| `status` | ticks of my messages: `sent`, `delivered`, `read`, `played` (only forward) |
| `sender_name` | the author's push name when it arrived (groups) |
| `media` | `file_name`, `seconds`, `width`, `height`, `pages`, `voice_note`, `animated`, `view_once`, `thumbnail`, `waveform_b64` |
| `location` | `{latitude, longitude, name, address, live}` (the body stays `unsupported`) |
| `contact` | a shared contact card `{display_name, vcard}` (body `unsupported`) |
| `starred` | starred on any device |
| `forwarding_score` | how often it was forwarded before; from 5 on show "Forwarded many times" (R4) |
| `link_preview` | `{url, title?, description?, thumbnail}`: the card of the link in the text; `thumbnail` true when `GET /v1/messages/{id}/thumbnail` has its picture (R4) |
| `expires_s` | the disappearing timer it carries: it disappears that long after it was sent (R4) |
| `system` | a line of the conversation, not a message: `{"type":"disappearing","seconds":N,"by":"me"\|jid}`; the body is `unsupported` (R4) |
| `unavailable` | `view_once`, `hosted` or `bot`: a placeholder only, the body is `unsupported` (below) |

A chat's `meta` (`ChatMeta`) holds `picture_id`, the other person's `push_name`,
`archived`, `pinned`, `muted_until_ms` (`-1` is "until unmuted"), `marked_unread` and
`ephemeral_s` (the disappearing timer in seconds, left out when off; R4);
`last_meta` is the meta of the newest message.

Live, new event types carry the same data; older clients skip them:

- `message_meta {chat, id, body?, meta}`: a message's meta is new or changed (reply
  context of a new message, an edit with the new `body`, a delete for everyone, a
  star). A new message gets one only when there is more to say than "sent" (and, in
  one-to-one chats, the push name).
- `message_deleted {chat, id}`: deleted on my devices only.
- `chat_meta {jid, meta}`: archive, pin, mute or unread mark changed here or on
  another device (WhatsApp app-state sync).
- `chat_deleted {jid}`, `chat_cleared {jid}`: done on another device.
- `chat_merged {from, into}`: the LID chat `from` was merged into the phone-number chat
  `into`. Clients move local state (drafts, pins, mutes, the open conversation) from
  `from` to `into` and refetch chats. Older clients skip the unknown event.
- `profile {jid, push_name, contact_name, about, picture_id, ...}`: a push name,
  about text, saved name or picture changed. A new `picture_id` means a cached
  picture is stale.

Receipts are stored as `status`; the `receipt` event is unchanged, but it is only
sent when a message's ticks actually move. In a one-to-one chat the other side's
receipt moves them. In a group each member's receipt is kept (table `receipts`), and
the message shows delivered once every other member has it, read once every other
member read it, played once every other member played it. The member count is
today's (whatsapp-rust's cached group info), not the one at send time, so a member
who joined later holds the ticks back; when the group cannot be read, ticks stop at
delivered. A played receipt also sends `message_meta` with `status: played` (a
`receipt` only knows delivered and read). Receipts from my own devices never move
ticks. A read on another of my devices (`read-self`) clears the chat's unread
counter and sends `receipt {kind: read}` for the other side's messages only, never
mine. `POST /v1/read` in a group sends one receipt per author: the request's
`sender`, else each stored message's author, as a LID in LID-addressed groups.
History syncs bring each message's
ticks, star and reactions, each chat's archive, pin and mute state, and push names.
Chats WhatsApp keys by LID are stored under the phone-number JID when WhatsApp
tells it, so they match address-book contacts.

### LID resolution and chat merge

A LID (`96413459456093@lid`) is WhatsApp's private id for a person. It is not a phone
number, even when it is all digits. Every chat, sender, author and participant id the
gateway produces goes through `wa/lid.rs`: a LID with a known mapping becomes
`<number>@s.whatsapp.net`, the device suffix (`:5`) is dropped, groups, broadcasts and
phone-number JIDs are untouched, and a LID without a mapping stays a LID. The mapping
comes from whatsapp-rust's `Client::get_lid_pn_entry` (cache, then its store), then
from the gateway's own table of learned pairs, with the number WhatsApp sent next to
the id as a fallback: `sender_alt` for someone else's message, `peer_recipient_pn` for
my own message echoed from another of my devices. The mapping wins over that number.
Mentions and quoted authors go through the same resolution.

whatsapp-rust has no "mapping learned" event, so the gateway records every pair it
sees (below), takes over the library's whole table on every connect, and sweeps what
is stored under a LID on every connect (idempotent), after each history sync, on
`DeviceListUpdate` and on `ContactNumberChanged`. The sweep covers every stored LID,
not only LID chats: group authors, reaction senders, receipt participants, who
deleted a message, quoted authors, mentions and profiles. It logs
`lid-merge: sweep moved N of M stored LIDs`.

Merging (`Store::merge_chat(from, into)`, one transaction) moves the LID chat's messages,
reactions and receipts to the phone-number chat (deduplicated by message id, the existing
row wins), sums the unread counters, keeps the newer `last_ms` and the name, carries
settings (pin, archive, mute) and scheduled texts over, and deletes the LID chat. It
also renames the person in every chat, whether or not they had a chat of their own:
authors, `deleted_by`, reaction senders (one reaction per person), receipt participants
(one row per person, at the furthest state), quoted authors, mentions and the profile.
A second call is a no-op. Sends keep working with either id: the store key is the
resolved id, the wire target is what the app sent.

Event `chat_merged {from, into}` (see above) tells clients to move local state. It is
sent only when a chat moved, as before.

### Identity: both ids, and the pairs the gateway learned

The gateway resolves ids but no longer hides what it resolved. Everything here is
additive: an older app skips the fields and events, and a newer app treats a missing
field as "not known". Ids are bare (`user@server`, no device).

- The other form of an id sits next to it when the gateway knows it (the LID next
  to a phone-number JID, or the reverse): `chat_alt` and `sender_alt` on a message,
  `sender_alt` on a reaction, `alt` on a chat, `lid` on a profile, `lid` and `pn` on a
  group participant, `jid_alt` on `presence` and `typing`, `peer_alt` on a call.
  `GET /v1/instance` and the `state` event carry `own_lid` next to `own_jid`. A group
  carries `addressing_mode` (`"pn"` or `"lid"`).
- `GET /v1/identity/mappings?since=<cursor>&limit=<n>` lists the learned pairs,
  oldest change first: `{ items: [{ lid, pn, source, learned_ms }], next }`. `next` is
  the cursor for the page after this one; an empty page has `next: null`, which means
  the app has everything. An app keeps the last cursor and asks from there on open,
  on reconnect and on `resync`. A gateway from before this route answers a bare 404,
  which `rosty_wa_client` reports as a `not_found` API error.
- `identity_mapped { lid, pn, source, learned_ms }` is sent when a pair is new or its
  LID moved to another number. Pairs learned in bulk (the library's table at connect,
  a history sync) are announced one by one up to 200; above that the apps get one
  `resync` and page the route.
- `identity_number_changed { old_pn, new_pn, old_lid?, new_lid? }` is sent when
  WhatsApp says a contact changed number. The two number chats are not merged here.

`source` is where the pair comes from: `number_change`, `peer_message`, `receipt`,
`call`, `usync`, `group_metadata`, `migration_sync`, `contact_action`, `history`,
`device_notification` or `other`. Pairs live in table `identity_pairs`, one row per
LID. A pair that disagrees with the stored one replaces it only from a source trusted
as much or more (`IdentitySource::rank`), and then gets a new, larger cursor. Synced
address-book contacts (`ContactAction`) are also fed to whatsapp-rust
(`Client::add_lid_pn_mapping`), which does not read them itself.

Each stored message keeps the chat and author id it arrived under (`wire_chat`,
`wire_sender`). Those are what goes back to WhatsApp: the `participant` of the message
key in a reaction and in an admin's delete for everyone, the quoted author of a reply,
and the chat and author of read and played receipts. Messages stored before schema v7
have none and fall back to the stored ids.

Group ticks count people: a member who acked under a LID and under the number the
gateway knows for that LID counts once, and this account's own devices never count.

`rosty_wa_proto::Timeline::apply_rich_page` and `Timeline::meta(id)` keep the meta
next to the timeline and apply `message_meta`, `message_deleted` and `chat_cleared`.

## Acting on messages and chats

All need the bearer key and answer 204. Unknown messages are 404, malformed JIDs 400.

| Request | Body | Notes |
| --- | --- | --- |
| `POST /v1/messages/edit` | `{chat, message_id, text}` | my own text or photo/video/document caption, within 15 minutes |
| `POST /v1/messages/revoke` | `{chat, message_id, sender?}` | delete for everyone; `sender` set: a group admin deletes someone else's message |
| `POST /v1/messages/delete` | `{chat, message_id}` | delete on my devices only |
| `POST /v1/messages/star` | `{chat, message_id, starred}` | |
| `POST /v1/played` | same as `/v1/read` | played receipts for voice and video notes |
| `POST /v1/chats/action` | `{chat, action}` | `action` is `{"type":"archive","archived":true}`, `{"type":"pin","pinned":true}`, `{"type":"mute","until_ms":null}` (null: forever; else a future unix ms), `{"type":"unmute"}`, `{"type":"mark_read","read":false}` or `{"type":"disappearing","seconds":N}` (0, 86400, 604800 or 7776000; R4) |
| `POST /v1/messages/forward` | `{chat, message_id, to}` | R4. Answers `Sent`. A copy marked forwarded goes to `to` (score raised, quote and mentions dropped, media relayed from the stored protobuf, `to`'s timer applied) and arrives as a `message` event there. 404 unknown message; 400 deleted, view once, placeholder, a line, or media without its protobuf |
| `GET /v1/messages/{id}/receipts?chat=` | | R4. `MessageReceipts {chat, id, members: [{jid, jid_alt?, name?, delivered_ms?, read_ms?, played_ms?, left}]}` of my message: per member in a group (today's members with nothing yet too, those who left `left: true`), the one person in a one-to-one chat. A time of 0: reached before the gateway kept times. 404 for an unknown message or someone else's |

Each one is sent to WhatsApp first, then stored and announced (`message_meta`,
`message_deleted` or `chat_meta`). Client: `edit_message`, `revoke_message`,
`delete_for_me`, `star_message`, `mark_played`, `chat_action`.

### Message actions (E6-WA R4)

- **Sending text** (`POST /v1/messages/text`) also takes `mentions` (JIDs; the text
  carries their `@<digits>` tokens) and `link_preview {url, title?, description?,
  thumbnail_b64?}` (a JPEG of at most 64 KiB; the phone made it, the gateway fetches
  nothing). In a group that names its members by LID, each mention with a known LID
  goes out as the LID, token and JID. An older gateway ignores both fields.
- **Disappearing messages**: the chat's timer comes from the `EPHEMERAL_SETTING`
  message (one-to-one), the group notification and the group's metadata, a history
  conversation, and an incoming message that names its timer with the time it was set;
  an older setting never overrides a newer one. Every send (text, media, forward)
  carries the timer, and the stored copy its `expires_s`. A change makes a line in the
  chat (`meta.system`), stored once per change, never counted as unread and never
  pushed; `chat_meta` follows. Messages keep `expires_ms`; the gateway does not purge
  them (open owner question, `plans/e6wa-r4.md`).
- **Receipt times**: each receipt's time is kept per member (and, from now on, for
  one-to-one chats with the chat as participant) for the receipts route.
- **Early changes**: an edit or delete for everyone of a message the gateway does not
  have yet waits in `pending_changes` (14 days) and is applied, with its
  `message_meta`, right after the message arrives.

Store schema **v11** adds `receipts.delivered_ms`, `read_ms`, `played_ms`,
`chat_settings.ephemeral_s`, `ephemeral_ms`, `messages.expires_ms` and the table
`pending_changes`: only new nullable columns and a new table. An older gateway refuses
a v11 file, so back it up before the deploy (`sqlite3 gateway.db ".backup gateway-v10.db"`).

`POST /v1/messages/media` also takes `quoted_id` (reply), `file_name` (documents;
no path separators, at most 255 bytes), `seconds` and `voice_note=true` in its query.
Client: `send_media_with(chat, kind, mime, caption, &MediaOptions, bytes)`.

More options of the same send (all optional; a request without them is sent as
before, and the gateway makes nothing up for a missing one). They are taken on
`POST /v1/messages/media` and on `POST /v1/messages/media/options`, which is the
same handler under a second path:

| Query | For | What it sets |
|---|---|---|
| `thumbnail_len=<n>` | photo, video, document | The body starts with `n` bytes of JPEG preview and the file follows. At most 64 KiB (65 536), must start with the JPEG marker, and something must follow it. Becomes the message's `jpegThumbnail`; the gateway stores it, so `meta.media.thumbnail` is true and `GET /v1/messages/{id}/thumbnail` serves it. The preview does not count against `max_media_bytes`. |
| `width`, `height` | photo, video, sticker | Pixel size. |
| `waveform_b64` | audio | The waveform of a voice note: base64 of exactly 64 bytes, each 0 to 100. |
| `animated=true` | video, sticker | A video plays as a GIF (`gifPlayback`); a sticker is marked animated. |
| `view_once=true` | photo, video, audio | View once: the inline flag on the media message, inside a `viewOnceMessage` wrapper. |

A preview over 64 KiB, a preview that is not a JPEG, a `thumbnail_len` longer than
the body, a waveform that is not base64 or not 64 samples of 0 to 100, and
`view_once` on a document or sticker are `400 bad_request`. An option the kind has
no field for (a waveform on a photo, a size on a document) is ignored. The preview
travels in the body, not as base64 in the query, because a URL of that size does
not pass a proxy (Cloudflare documents a 16 KB limit for a URL).

Why the second path: a gateway from before these options ignores query keys it does
not know. On the plain route it would send a view-once photo as an ordinary one and
read a preview in the body as part of the file. It has no `/v1/messages/media/options`,
so there it answers 404, and the client knows. `send_media_with` asks the second
path whenever one of the newer options is set. On that 404 (the router's, without
an API body) it sends the file the old way, without the newer options, as every
send was before; a view-once send is not sent at all and comes back as a
`not_found` API error. A send without the newer options asks the plain route with
the request it always made.

`MediaOptions` has the same options by name: `thumbnail_b64` (the client decodes it
and puts it in front of the body), `width`, `height`, `waveform_b64`, `animated`,
`view_once`. The client checks them and answers a `bad_request` API error without
a request when the gateway would refuse them.

```
curl -X POST 'https://rosty.erdemdev.tr/v1/messages/media/options?chat=100000000001%40s.whatsapp.net&kind=audio&mime=audio%2Fogg&seconds=7&voice_note=true&waveform_b64=<base64 of 64 bytes>' \
  -H 'Authorization: Bearer <key>' --data-binary @note.ogg
```

A received voice note carries its waveform in `meta.media.waveform_b64`: the bytes
WhatsApp sent, base64, as they came (64 samples of 0 to 100 from the official
apps; a reader checks that before drawing). A message that came through history
sync has it too. A message stored by a gateway before this change gains it when it
arrives again (history), not by a migration: the meta is stored as JSON and the
store did not change.

View once: `meta.media.view_once` is true for the inline flag and for media inside
any of the three view-once wrappers. The gateway keeps serving the file of a
view-once message like any other; opening it once and forgetting the file is the
app's job (the device store has a "viewed" mark).

A view-once message someone sends me never reaches a linked device: WhatsApp
sends the gateway an `<unavailable type="view_once">` placeholder, which
whatsapp-rust reports as `Event::UndecryptableMessage` with
`unavailable_type: ViewOnce` and does not ask the phone for (it never shares
it). The gateway stores and announces it as an ordinary message whose body is
`unsupported` and whose `meta.unavailable` is `"view_once"`: the id, chat,
author, time and `from_me` come from the envelope (`MessageInfo`). The two other
fanouts the phone never resends, a bot's message and one of a business hosted by
Meta, are stored the same way with `"hosted"` or `"bot"`. A plain fanout is left
to the library (the phone resends it and it arrives as usual), and so is a
message that failed to decrypt. History sync: a view-once wrapper with nothing
inside reads as the same placeholder. An older client reads an unsupported
message, as before. A reason a newer gateway adds reads as `other` in
`rosty_wa_proto::Unavailable`.

```json
{"seq": 41, "at_ms": 1700000000500, "event": {"type": "message", "id": "3EB0VO1",
  "chat": "100000000001@s.whatsapp.net", "sender": null, "from_me": false,
  "timestamp_ms": 1700000000000, "body": "unsupported", "reactions": []}}
{"seq": 42, "at_ms": 1700000000500, "event": {"type": "message_meta",
  "chat": "100000000001@s.whatsapp.net", "id": "3EB0VO1",
  "meta": {"unavailable": "view_once"}}}
```

`GET /v1/messages/{id}/thumbnail` answers the small preview image that came inside
a photo, video, document, sticker or location (`meta.media.thumbnail`), up to 64 KiB,
or 404. Client: `thumbnail(id)`.

## Profiles and profile pictures

`POST /v1/contacts/profiles` with `{"jids": [...], "refresh": false}` (1 to 50 user
JIDs `number@s.whatsapp.net`, LIDs or group JIDs) answers one `WaProfile` per distinct
JID in request order: `push_name`, `contact_name` (the name saved in WhatsApp's own
contact sync), `about`, `picture_id`, `business`, `verified_name`, `fetched_ms`.
Push names are learned from every incoming message and from history syncs; about,
picture id and business data are asked from WhatsApp at most once a day per JID
(`refresh: true` forces it). When WhatsApp cannot be asked the stored data is
returned. Client: `profiles(&jids)`, `refresh_profiles(&jids)`;
`WaProfile::display_name()` picks saved name, then business name, then push name.

`GET /v1/pictures/{jid}` (add `?full=true` for the large picture) answers the profile
picture bytes (JPEG) with `ETag: "<picture id>"` and `Cache-Control: private,
no-cache`. Send `If-None-Match: "<id>"` to get a 304 when the picture is unchanged; a
person or group without a (visible) picture is a 404. The gateway keeps the
downloaded pictures in `instances/<id>/pictures` (64 MiB per instance, least recently
used first out), checks the picture id against WhatsApp at most once a day, and
drops it at once when WhatsApp reports a change (`profile` event). Client:
`picture(jid, full, known_id)` returns `Picture::Image {id, bytes}`,
`Picture::Unchanged` or `Picture::None`. Pictures are fetched only from WhatsApp's
own HTTPS hosts.

Profile and picture queries that reach WhatsApp share a per-instance bucket of 120
tokens (two come back every second), separate from the lookup bucket; over it the
answer is 429 `rate_limited`. Stored answers never cost a token.

### Rate limits

Every 429 `rate_limited` from the contact routes (`/v1/contacts/lookup`,
`/v1/contacts/profiles`, `/v1/pictures/{jid}`) carries two optional fields and a
header. Older clients ignore them (no field is required, unknown fields are allowed):

```json
{"code": "rate_limited", "message": "...", "retry_after_ms": 60000, "source": "whatsapp"}
```

- `source`: `whatsapp` when WhatsApp itself refused (or the gateway is waiting out
  such a refusal), `gateway` when the gateway's own bucket is empty.
- `retry_after_ms`: when to ask again. `Retry-After` has the same in whole seconds,
  rounded up.
- **Cooldown.** After WhatsApp's 429 the instance pauses all lookup, profile and
  picture queries to WhatsApp: at least WhatsApp's own `backoff` hint and at least
  60 s, doubling with every further 429 (capped at 30 min). The strikes are forgotten
  10 minutes after a pause ends without a new 429. During the pause nothing is sent
  to WhatsApp; cached and stored answers are still served.
- **Stale answers.** When WhatsApp cannot be asked (cooldown, bucket, or a failed
  query) and every requested JID already has an answer WhatsApp really gave
  (`fetched_ms > 0`), `/v1/contacts/profiles` answers 200 with the stored profiles
  and the header `x-rosty-stale: rate-limited` (or `error`). Otherwise, and always
  with `refresh: true`, the error is returned; a never-fetched JID no longer comes
  back as an empty profile with `fetched_ms: 0`. A picture whose bytes the gateway
  already holds is served the same way with `x-rosty-stale`. Each such answer also
  carries the same value under the header's pre-rename name, `x-rovsti-stale`, for
  clients built before the rename.
- **Logging.** Every request is logged at INFO as
  `instance=<id> <METHOD> <route template> -> <status> <ms>ms` (`/healthz` and the
  event stream at DEBUG); every contact-route 429 also at WARN as
  `instance=<id> rate limited source=<whatsapp|gateway> route=<template>
  retry_after_ms=<n> n=<jids>`, cooldown start and end at INFO. No JID, number or
  query string is logged.
- **How the apps use it.** The people sync (Contacts, and Phone without Contacts) uses
  the people job below (people sync v3) and does not call these routes per contact.
  Only against a gateway without the job routes (the start answers a bare 404) does it
  fall back to them, asked again after a day: 15 JIDs at a time, 20 s apart (lookups
  and profiles share the gap), and one picture every 3 s; a 429 halves the batch,
  doubles the gap and waits at least the `retry_after_ms` hint (a minute, doubling, at
  most 30 min), and the settings row shows the time until it continues. A 200 profile
  with `fetched_ms: 0` (an older gateway) counts as a throttle, not an answer. The
  contact card, New Conversation and the names of unknown callers still use the routes
  one number at a time. See `docs/architecture.md`, "People sync".

`GET /v1/groups` and `GET /v1/groups/{jid}` now also carry the group `description`,
`announce` (only admins may send) and each participant's `name` (push name or saved
name) when the gateway knows it. Client: `groups()`, `group(jid)`.

## People sync job

People sync v3 (`_bmad-output/kotlin-ui/plans/people-sync-v3.md`): instead of one paced
request per contact step, the app hands the gateway its numbers once; the gateway looks
them up, asks for the profiles and fetches the pictures as one **job**, at the same pace
and under the same allowances and WhatsApp cooldown as the per-contact routes above, and
the app downloads the result in one **bundle** at the end. Code:
`services/wa_gateway/src/people_job.rs` (the task), `people_routes.rs` (the routes),
`store/people.rs` (schema v9); wire types in `rosty_wa_proto::people`.

| Route | |
|---|---|
| `POST /v1/people/sync` | Start or join. Body `{"mode": "auto"\|"full", "entries": [{"n": "+100000000001", "h": "<hash>", "p": "<picture id>"}, ...]}`, at most 5 000 entries (more: 400). Answers the job status. |
| `GET /v1/people/sync/{job_id}?rev=<n>&wait_ms=<ms>` | The status. With `rev` equal to the job's and a working job, waits up to `wait_ms` (at most 25 000) for the next change. 404 `not_found` when the gateway no longer has the job. |
| `GET /v1/people/sync/{job_id}/bundle?after=<seq>&max_bytes=<n>` | One part of a `ready` job's bundle (409 `conflict` before). |
| `DELETE /v1/people/sync/{job_id}` | Cancel a working job, or drop a finished one; 204 also when it is gone. |

```json
{"job_id": "5f0c9a1b22d4e7a3", "rev": 42, "state": "waiting", "phase": "profiles",
 "done": 120, "total": 340, "numbers": 812,
 "wait": {"until_ms": 1795000000000, "source": "whatsapp"}, "eta_ms": 95000,
 "account_epoch": 3, "created_ms": 1794999000000, "updated_ms": 1794999100000,
 "expires_ms": 1795020600000}
```

- **States** `running`, `waiting` (`wait.source`: `whatsapp` = WhatsApp's 429 or the
  cooldown, `gateway` = the instance's own allowance, `not_connected` = the WhatsApp
  session is down, tried again every minute), `ready` (with `result: {records, pictures,
  bytes}`), `failed` (with `error: {code, message}`, codes `expired`, `whatsapp`,
  `internal`), `cancelled`. **Phases** `lookup`, `profiles`, `pictures`, `bundle`;
  `done`/`total` belong to the phase. `mine: false` (only on a start answer): another
  device's job works on the instance and was left alone; watch it and start again when it
  is over. Every enum has an `other` catch-all for older clients.
- **One job per instance.** The same request (same mode and entries) joins its working
  job, or gets its `ready` job back; another request replaces a finished job.
- **What is asked.** Lookups: numbers without a stored answer; `auto` also negatives older
  than 7 days and positives older than 30 days; `full` all. Profiles: numbers on WhatsApp
  whose profile is older than a day (`full`: all). Pictures: numbers on WhatsApp whose
  picture id differs from the app's `p` (full size; from the picture cache when it holds
  that id). An unchanged run asks WhatsApp nothing.
- **Pace.** 15 JIDs per usync request, 20 s apart (lookups and profiles share it), one
  picture every 3 s, every gap ±20 %. Each request is charged to the instance's lookup or
  profile allowance; a refusal or WhatsApp's 429 makes the job `waiting` until the
  allowance or the cooldown allows it (at least WhatsApp's `backoff`, a minute doubling per
  strike, at most 30 min), halves the batch (at least 5) and doubles the gap (at most
  5 min, 1 min for pictures); five successes in a row start the recovery. Nothing is sent
  before the wait ends. Other errors are retried twice, then the job fails; a single
  picture that fails is delivered as `picture_missing`.
- **Persistence.** Jobs, their numbers (with per-number progress) and the lookup answers
  are in `gateway.db` (schema v9); picture bytes wait in
  `instances/<id>/people-job/<job_id>/` (at most 256 MiB a job). After a restart every
  working job continues from its progress (a stored wait is slept out first). A job not
  ready within 6 h fails as `expired`; a finished job is deleted 24 h after it ended;
  lookup answers (negatives included) are kept 30 days. The tables are cleared with the
  account (another number linked) and with the instance. The job writes profiles straight
  to the store and publishes no `profile` event.
- **Hash.** `h` is what the gateway sent as `hash` last time: SHA-256 over
  `rosty-people-v1\n` and seven fields, each `<byte length>:<bytes>,` (number, `1`/`0` for
  on WhatsApp, phone JID, LID JID, push name, verified name, picture id; absent = empty,
  trimmed), first 8 bytes in lower-case hex. The app stores it opaquely.
- **Bundle.** `Content-Type: application/x-rosty-people-bundle`: JSON lines. The first is
  `{"v":1,"job_id":"…","after":0}`; then one record per number whose hash differs from
  `h` or whose picture id differs from `p`, in request order, a record with
  `"picture_bytes": n` followed by exactly n raw bytes and `\n`; the last line is
  `{"end":true,"last_seq":<n>,"more":<bool>}`. A part stops after about `max_bytes`
  (default and cap 8 MiB, at least one record); ask again with `after=last_seq` while
  `more`. A part without its end line was cut: ask again from your cursor. An unchanged
  run's bundle is the two framing lines.

```
{"seq":17,"number":"+100000000001","hash":"9a41c0de5b7e1f02","on_whatsapp":true,"jid":"100000000001@s.whatsapp.net","lid":"123456@lid","push_name":"Ada","picture_id":"1716839201","picture_bytes":61234}
{"seq":18,"number":"+100000000002","hash":"0c11d2e3f4a5b6c7","on_whatsapp":false}
```

- **Logging.** `instance=<id> people job started|phase=…|waiting|rate limited|ready|
  cancelled|bundle …` with counts only, never a number or JID.
- **Old and new.** An older app keeps using the per-contact routes; nothing changed for it.
  A newer app against an older gateway gets the router's bare 404 on `POST` and falls back
  to the per-contact steps (asking again after a day).
- **Deploy.** Schema v9 only adds three tables, in one transaction. Take a backup first:
  `sqlite3 /var/lib/wa-gateway/gateway.db ".backup gateway-v8.db"`; an older binary
  refuses a v9 file ("newer than this gateway").
- Client: `people_sync_start`, `people_sync_status`, `people_sync_bundle`,
  `people_sync_cancel` (`rosty_wa_client`); FFI `wa.people_sync_*`.

## Status updates (stories)

WhatsApp delivers Status updates as messages to `status@broadcast`. The gateway keeps
them out of everything chat-shaped: they never become a chat, a chat message, an unread
count, a `message` event or an FCM push, and a history sync's `status@broadcast`
conversation is not imported as a chat. They go to their own table (`statuses`, schema
v5) with the author (by phone number when WhatsApp tells it), the JID the update was
addressed from (named in read receipts), the kind (`text`, `image`, `video`, `other`),
the text or caption, a text update's `background_argb`, `text_argb` and `font`, the media
details (`mime`, `size`, `seconds`, `width`, `height`, `thumbnail`), the encoded message
for downloading, the embedded thumbnail, `from_me` and a `viewed` flag. An update
expires 24 hours after it was posted; a sweeper deletes expired ones (and their cached
media) every 10 minutes. The v5 migration also deletes the `status@broadcast` chat,
messages, reactions and receipts an older gateway stored.

| Request | Meaning |
| --- | --- |
| `GET /v1/statuses` | `{mine: [...], authors: [{author, name, items: [...]}]}`: my own updates, then everyone else's grouped by author, people with unseen updates first, then newest first; each author's `items` oldest first. |
| `GET /v1/statuses/{id}/media` | The photo or video, downloaded once, then served from the media cache. A text update is 400, an unknown or expired one 404. |
| `GET /v1/statuses/{id}/thumbnail` | The small preview of a photo or video, or 404. |
| `POST /v1/statuses/viewed` `{ids}` | 1 to 100 ids. For the ones not seen before (never my own) a read receipt goes to `status@broadcast` with the author as participant, one per author (whatsapp-rust's `mark_as_read(status@broadcast, Some(author), ids)`, which adds the author's number when the author is a LID, as WhatsApp Web does). Then they are stored as seen. 204. |

New updates, a delete by the author, updates seen through `/v1/statuses/viewed` and
the expiry sweep publish `{"type":"status_changed","author":"..."}` (`author` left out
when several people changed); clients refetch `GET /v1/statuses`. Older clients skip it.
Client: `statuses()`, `status_media(id)`, `status_thumbnail(id)`,
`mark_status_viewed(&ids)`. The wire types and the viewer's pure model
(`StatusPlayer`) are in `rosty_wa_proto::status`.

Posting a status is not offered: whatsapp-rust's `client.status().send_text(...)` needs
the list of recipients (the contacts allowed to see it), which the gateway does not
keep.

### How Rosty Messages uses it

The Chats tab shows a "Status" row above the archived chats while anyone has a current
update: up to three avatars with a ring (green while unseen), "N new updates" and the
unseen count. It opens the Status page (`apps/messages/src/status_ui.rs`): "My status",
then "Recent updates" and "Viewed updates", a virtualised list. Tapping a person opens a
full-screen viewer that plays their updates oldest unseen first, then moves on to the next
person: text updates on their background colour, photos (downloaded and decoded off the
UI thread, at most six kept), videos as their thumbnail with the length (there is no
video playback here). Progress bars run for 5 seconds per update; tap the right two
thirds for the next, the left third for the previous, hold to pause, swipe down or press
Back to close (Back then closes the page). An update counts as seen, and its receipt goes
out, only while it is on screen in a window the user can see. Messages from an older
gateway that still sends `status@broadcast` as a chat are dropped by the app as well.

## Scheduled texts

The gateway can send a text later, so the phone may be off at that time.

| Request | Meaning |
| --- | --- |
| `POST /v1/scheduled` `{chat, text, send_at_ms}` | Schedule a text. 201 with the item. |
| `GET /v1/scheduled` | Pending items plus items finished in the last 7 days, soonest first. |
| `DELETE /v1/scheduled/{id}` | Cancel a pending item. 204. |

An item is `{id, chat, text, send_at_ms, created_ms, state}` where `state` is
`{"status":"pending"}`, `{"status":"sent","message_id":...}` or
`{"status":"failed","reason":...}`.

Rules: `chat` must be a valid JID; `text` is 1 byte to 64 KiB;
`send_at_ms` (unix milliseconds) must be 30 seconds to 365 days from now,
else 400. At most 200 items may be pending per instance (409
`limit_reached`). `DELETE` works on pending items only: an unknown, sent or
failed id gives 404, so cancelling twice gives 404.

A sweeper checks every 15 seconds and sends due items with the normal text
path, so a sent item also appears as a `message` event and in the chat. A
failed send is retried once after 60 seconds; a second failure (for example
WhatsApp is not connected) stores `failed`. The retry timer lives in memory,
so a gateway restart gives a pending item a fresh attempt. Finished items
are deleted after 7 days, and deleting the instance deletes all its items.

Every add, send and failure publishes `{"type":"scheduled", ...item}` on
`/v1/events`; clients that do not know the event skip it. A cancel publishes
nothing, so clients refetch the list when they need to see cancellations.

### How Rosty Messages uses it

`rosty_wa_client::Gateway` has `schedule_text(&ScheduleText)`, `scheduled()` and
`cancel_scheduled(id)`. Long-pressing Send in a WhatsApp chat opens the "Send later"
sheet (`apps/messages/src/schedule_ui.rs`); the pick becomes a `POST /v1/scheduled`.
The chat's Scheduled strip lists the pending (and failed) items of that chat from
`GET /v1/scheduled` and applies `scheduled` events as they arrive; Cancel is
`DELETE /v1/scheduled/{id}`, and a 404 (it was just sent) only refreshes the list.
SMS never goes through the gateway: it is queued locally and armed as an Android alarm.

## Not run here

Nothing in this repository links a real account: linking needs a phone. The unit tests
cover the protobuf conversion, the history mapping and the routes against a scripted
driver, and `cargo check -p wa_gateway` proves the whatsapp-rust driver compiles.
