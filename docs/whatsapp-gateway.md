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
`{"kind":"call","call_id","from","name"}` or `{"kind":"message","chat","count"}`.
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

## Not run here

Nothing in this repository links a real account: linking needs a phone. The unit tests
cover the protobuf conversion, the history mapping and the routes against a scripted
driver, and `cargo check -p wa_gateway` proves the whatsapp-rust driver compiles.
