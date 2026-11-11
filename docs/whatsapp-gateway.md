# WhatsApp gateway: running it and linking an account

The gateway (`services/wa_gateway`) owns every whatsapp-rust session. The apps only
talk to it through `rovsti_wa_client`. Design and API: `_bmad-output/plans/wa-gateway.md`
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
`127.0.0.1:7218`; a Cloudflare tunnel public hostname (`https://rovsti.erdemdev.tr`) points
at `http://localhost:7218`.

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
  (`/etc/systemd/system/wa-gateway.service.d/10-rovsti.conf`), so the unprivileged service
  user never owns the file. The project id goes into `config.toml`.
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

Creating an instance needs no key, so anyone who can reach the gateway can create one
up to `max_instances`. Keep the address private or behind the proxy's own access
rules. Set `trust_proxy = true` only behind a proxy that sets `X-Forwarded-For`;
failed-key rate limiting is per client IP.

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
nothing and rely on their open event socket.

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
3. Render the payload as a QR code (the apps use `rovsti_wa_ui`) and scan it from
   WhatsApp on the phone: Settings, Linked devices, Link a device.
4. When the phone approves, the stream sends `{"type":"state","state":"connected",...}`
   with the account's JID and push name. A history sync follows, and one
   `history_synced` event reports how many chats arrived.

## Link by pairing code

Create the instance with `{"link":{"type":"phone_number","number":"+90 532 123 45 67"},"app":"messages"}`
(a country code is required). The stream sends `{"type":"pair_code","code":"..."}`.
On the phone choose Linked devices, Link a device, then "Link with phone number
instead", and type the code.

## Day to day

- `POST /v1/messages/text`, `POST /v1/read`, `POST /v1/presence`, `POST /v1/chatstate`
  and `POST /v1/presence/subscribe` act on the instance behind the bearer key. A
  malformed JID is a `400 bad_request`.
- If WhatsApp unlinks the device (from the phone, or a server-side logout) the
  instance goes to `logged_out` and its `wa.db` is deleted. Link again with
  `POST /v1/instance/link`, or delete the instance with `DELETE /v1/instance`.
- A temporary WhatsApp ban shows as `banned`. The gateway does not restart a banned
  instance at boot; relink once the ban is over.

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
- The instance must be connected, else the usual `not_connected` error.

`rovsti_wa_client::Gateway::lookup_users(&[String])` wraps it. Apps look up one
contact's numbers when its card opens, never the whole address book.

## Message and chat meta

`GET /v1/chats` and `GET /v1/chats/{jid}/messages` send the old fields plus a `meta`
object (left out when empty), so older clients keep reading them as `ChatSummary` /
`WaMessage`. The types are in `rovsti_wa_proto::meta`; the client reads them with
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
| `media` | `file_name`, `seconds`, `width`, `height`, `pages`, `voice_note`, `animated`, `view_once`, `thumbnail` |
| `location` | `{latitude, longitude, name, address, live}` (the body stays `unsupported`) |
| `contact` | a shared contact card `{display_name, vcard}` (body `unsupported`) |
| `starred` | starred on any device |

A chat's `meta` (`ChatMeta`) holds `picture_id`, the other person's `push_name`,
`archived`, `pinned`, `muted_until_ms` (`-1` is "until unmuted") and `marked_unread`;
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
comes from whatsapp-rust's `Client::get_lid_pn_entry` (cache, then its store), with the
message's `sender_alt`/`recipient_alt` as a fallback. The mapping wins over the alt.

whatsapp-rust has no "mapping learned" event, so mappings are looked up lazily and the
stored LID chats are swept on every connect (this is also the one-time startup
migration, and it is idempotent), after each history sync, on `DeviceListUpdate` and
`ContactNumberChanged`, and when a message arrives for a LID the resolver had not seen.
The sweep logs `lid-merge: startup sweep merged N of M LID chats`.

Merging (`Store::merge_chat(from, into)`, one transaction) moves the LID chat's messages,
reactions and receipts to the phone-number chat (deduplicated by message id, the existing
row wins), rewrites sender and participant ids, sums the unread counters, keeps the newer
`last_ms` and the name, carries settings (pin, archive, mute), profile names and scheduled
texts over, and deletes the LID chat. A second call is a no-op. Sends keep working with
either id: the store key is the resolved id, the wire target is what the app sent.

Event `chat_merged {from, into}` (see below) tells clients to move local state.

`rovsti_wa_proto::Timeline::apply_rich_page` and `Timeline::meta(id)` keep the meta
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
| `POST /v1/chats/action` | `{chat, action}` | `action` is `{"type":"archive","archived":true}`, `{"type":"pin","pinned":true}`, `{"type":"mute","until_ms":null}` (null: forever; else a future unix ms), `{"type":"unmute"}` or `{"type":"mark_read","read":false}` |

Each one is sent to WhatsApp first, then stored and announced (`message_meta`,
`message_deleted` or `chat_meta`). Client: `edit_message`, `revoke_message`,
`delete_for_me`, `star_message`, `mark_played`, `chat_action`.

`POST /v1/messages/media` also takes `quoted_id` (reply), `file_name` (documents;
no path separators, at most 255 bytes), `seconds` and `voice_note=true` in its query.
Client: `send_media_with(chat, kind, mime, caption, &MediaOptions, bytes)`.

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

`GET /v1/groups` and `GET /v1/groups/{jid}` now also carry the group `description`,
`announce` (only admins may send) and each participant's `name` (push name or saved
name) when the gateway knows it. Client: `groups()`, `group(jid)`.

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
(`StatusPlayer`) are in `rovsti_wa_proto::status`.

Posting a status is not offered: whatsapp-rust's `client.status().send_text(...)` needs
the list of recipients (the contacts allowed to see it), which the gateway does not
keep.

### How Rovsti Messages uses it

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

### How Rovsti Messages uses it

`rovsti_wa_client::Gateway` has `schedule_text(&ScheduleText)`, `scheduled()` and
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
