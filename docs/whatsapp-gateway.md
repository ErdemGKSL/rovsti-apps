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

Put a TLS reverse proxy in front of it for anything beyond your own machine. An app
accepts `http://` only for loopback, `10.0.2.2` (the Android emulator), private
ranges and `.local` hosts.

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
