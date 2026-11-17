<!-- Owner-approved plan (2026-11-17): written by Fable in the APK session; the owner said to trust and implement it. -->

# WhatsApp message protection: plan (max-security variant)
Branch ui-overhaul. Plan only.

## 1. What is on disk today (Messages app, all plaintext)
- Chats, messages (text, quotes, reactions, edit history, deleted messages kept), thumbnails (messages.thumb BLOB), outbox, identity map, media rows: noBackupFilesDir/wa-sync-<instance>.db + -wal/-shm; rosty_app_core::wa_sync (rusqlite =0.39.0 bundled, no SQLCipher), schema v6 (v5 = media table, v6 = viewed_ms/played_ms/gallery index).
- Full media files: filesDir/wa-media/ (WaWiring.kt).
- Status media: cacheDir/wa-status/.
- Connect key (bearer secret: anyone with it can read and send as the account), gateway URL, instance id: noBackupFilesDir/whatsapp-link.json, in EVERY linked app (Phone, Contacts, Stops too).
- Names, drafts, rekey cursor, reminders, exports: wa-live-names.json, drafts file, WaRekeys file, MESSAGE_REMINDERS SharedPreferences, cacheDir/{share,exports,cards}.
- No Room, no Android Keystore use, no FLAG_SECURE, no androidx.security/biometric dependency. Existing crypto: aes-gcm 0.10.3 and pbkdf2 0.12.2 (600 000 iterations) in rosty_core::msg_backup. minSdk 34. allowBackup=false.
Key finding: encrypting only the database is not enough. With the plaintext connect key an attacker downloads the whole history from the gateway. Protect the connect key first.

## 2. Recommendation
Memory-only message store + a passphrase-and-hardware-sealed vault holding only the connect key.
- No message, thumbnail, media or name is ever written to disk. wa_sync.open already has memory: true (Store::open_in_memory); it becomes the only mode in the product.
- The only persisted secret is the vault (connect key, gateway URL, instance id). It cannot be opened without the passphrase AND this device's secure hardware.
- Rejected: encrypted-at-rest SQLite with the key in RAM. It leaves the whole history on disk as ciphertext forever (harvest now, break later) and needs SQLCipher, which conflicts with the pinned rusqlite/libsqlite3-sys 0.37 shared with whatsapp-rust.
- Usability cost: every cold start and unlock refetches chats and recent pages (needs network; no offline reading); media re-downloads; older history loads on scroll.

## 3. Key hierarchy
1. Passphrase P: app-generated, 130 bits (26 Crockford base32 chars in groups). Not user-chosen.
2. KEK_p = Argon2id(P, 16-byte random salt; m = 64 MiB, t = 3, p = 1, calibrated on device, parameters in the vault header). New crate argon2 (RustCrypto, pure Rust). Fallback: PBKDF2-HMAC-SHA256 at 600 000.
3. K_hw: AES-256-GCM key in Android Keystore (KeyGenParameterSpec, AndroidKeyStore), non-exportable, StrongBox when present (fallback TEE), setUnlockedDeviceRequired(true), setUserAuthenticationRequired(true) per use with biometric-strong or device credential, setInvalidatedByBiometricEnrollment(true).
4. DK (data key, 256-bit random) stored as K_hw.encrypt(AES-256-GCM(KEK_p, DK)). Both layers required. A disk image alone is useless; a stolen unlocked phone is useless without P; guessing P needs one hardware-authenticated Keystore operation per try (rate limit enforced by Gatekeeper, not an app counter).
5. DK encrypts the vault body (AES-256-GCM, header as associated data, same layout style as msg_backup).
Runtime: P only in the entry field and a zeroized buffer; KEK_p dropped after unwrap; DK and connect key live in Rust memory (zeroize) inside rosty_ffi state; K_hw never leaves hardware. Kotlin gets a handle, never the key.
Rotation and anti-rollback: changing the passphrase creates a new K_hw alias, re-wraps, deletes the old Keystore key, so an older vault copy can never be opened. Rotation also issues a new connect key and revokes the old one on the gateway (endpoint to be confirmed).

## 4. Passphrase display and copy
Setup screen: shown once in grouped monospace behind FLAG_SECURE. Copy uses the existing ClipboardRepository sensitive path (EXTRA_IS_SENSITIVE), clip cleared after 60 s. User retypes 2 random groups before setup completes. Never shown again; "Change passphrase" makes a new one. Entry accepts paste and autofill with IME_FLAG_NO_PERSONALIZED_LEARNING.

## 5. Recovery
No key recovery; recovery is re-linking. A forgotten passphrase deletes the vault and Keystore key, then the user links again (new connect key from the gateway, or pasted from another unlocked app); history returns from the gateway.
- Security question: rejected (10-20 bits, guessable, a second weaker door).
- Question + recovery code: only as strong as the code.
- One-time recovery code: no benefit here, since re-linking recovers everything.

## 6. Lock and unlock (superseded for daily use by section 13.2)
- Lock on: screen off, 60 s in background (configurable down to immediately), manual Lock, process death.
- Lock closes the in-memory store, the event socket, zeroizes DK; lock also ends the process for a clean wipe.
- While locked: FCM pushes carry no text ({"kind":"message","chat","count"}). Locked, show only a content-free notification. No inline reply, no outbox retry.
- FLAG_SECURE on all WhatsApp screens.

## 7. Lost on restart in memory-only mode
Offline reading; unsent outbox messages; cached media; locally kept deleted messages and edit history beyond what the gateway returns; view-once "viewed" and voice "played" marks (risk of a played receipt sent twice, to be checked); sync cursors; drafts and live names (see 13.5). Media in a RAM LRU (about 64 MiB), plays via MediaDataSource; save/share are explicit plaintext exports the user chooses.

## 8. Upgrade migration
First start of the new build: block WhatsApp screens behind setup; create vault from whatsapp-link.json; delete wa-sync-*.db, -wal, -shm, wa-media/, wa-status/, wa-live-names.json, WhatsApp drafts, whatsapp-link.json. Flash deletion is not provable erasure: PRAGMA secure_delete + VACUUM before unlink; advise rotating the connect key. Only a factory reset is a guarantee; say so in the UI.

## 9. Crates and modules
- rosty_core::vault (new, pure): header format, Argon2id/PBKDF2 derivation, wrap/unwrap, passphrase generation from caller entropy, formatting and typo check. Unit tests with fixed vectors.
- rosty_app_core: vault file I/O and migration/wipe helpers (&Path, as storage.rs); wa_sync loses the file path in product use.
- rosty_ffi: vault.* ops (create, unlock, lock, change, reset, state); wa.open takes the key from the unlocked vault.
- Kotlin: :platform gets VaultKeystore (Keystore + BiometricPrompt); :wa replaces WaLinkStore reads, adds setup/unlock/change screens; :messages changes WaWiring, WaDownloads, StatusFeed, notifier, outbox worker; :shell lock lifecycle.
- rosty_android: unchanged unless Keystore must be reachable from Rust; then every new function returns Error::Unsupported on desktop.
- Docs: architecture.md Storage, whatsapp-gateway.md, android.md.

## 10. Tests
Rust: KDF and AEAD vectors; wrong passphrase, tampered header, truncated file, unknown version; rotation makes the old blob fail; migration deletes every listed file (temp dir fixture); FFI golden fixtures for vault.*. Kotlin: Robolectric for lock timers and locked notification text; manifest test for FLAG_SECURE; device test grepping the app data dir for a known message string after sync, lock and kill. Gate: scripts/kotlin-ui-check.sh, release profiles only.

## 11. Conflicts and limits
1. The gateway stores plaintext (gateway.db holds chats, messages, session). Server never gets key material, but sees messages by design.
2. Other apps hold the same connect key in plaintext (Phone, Contacts, Stops, WaLinkProvider handover). Protection is void unless they adopt the vault too.
3. CLAUDE.md names rosty_core/rosty_geo for domain logic; the store lives in rosty_app_core. Crypto goes in rosty_core, I/O in rosty_app_core.
4. SMS/MMS stay in the system provider; out of scope.
5. New dependency argon2.

## 12. Strongest option rejected
Passphrase plus hardware key with NO reset path and a wipe after N failed tries: re-linking gives an attacker nothing, while a wipe counter lets anyone holding the phone destroy the link.

## 13. Decisions (made for the owner: security first, no added gateway load)
1. Memory-only: yes. Nothing readable or decryptable about messages stays on disk. Refetch made cheap by lazy sync (section 14).
2. Passphrase rarely, biometric daily (owner constraint; supersedes "passphrase on every unlock").
   - Passphrase typed at setup and again only after a reboot, on a new device or reinstall, after 72 h without an unlock, after a biometric enrolment change, or after 5 failed daily unlocks.
   - After a passphrase unlock, DK is also sealed under K_daily (AES-256-GCM, non-exportable, StrongBox when present, auth required per use, setInvalidatedByBiometricEnrollment(true)). Only the sealed blob is on disk.
   - Daily unlock: BiometricPrompt with a CryptoObject on K_daily, Class 3 biometric. Lock-screen PIN accepted only on phones with no Class 3 biometric enrolled.
   - Reboot and idle rules: the blob carries boot count and last unlock time as authenticated data; on mismatch, and from BootReceiver, the app deletes K_daily and the blob. Enforced by the app, not hardware.
   - What a thief gets: phone stolen unlocked, attacker without the owner's finger or face: nothing. Attacker who knows the PIN on a biometric phone: enrolling their own fingerprint destroys K_daily, passphrase still needed. Attacker who passes the biometric (coercion, sleeping owner) or knows the PIN on a PIN-only phone: full read and send access through the gateway until the next reboot or 72 h idle. This is the price of not typing.
   - What the passphrase still protects: powered-off, rebooted or forensically imaged phone; 72 h untouched; restored or cloned install; a newly enrolled biometric. Limit: root plus PIN plus a running phone can bypass the app rules, but still needs hardware authentication for K_daily.
   - Gateway cost: none.
3. Vault in all four apps. One plaintext whatsapp-link.json anywhere voids everything. Each app has its own vault and Keystore key; WaLinkProvider hands the key over only between unlocked apps, in memory. Locked Phone shows the incoming call from the push payload (no gateway request) and needs unlock to answer, so most such calls will be missed. Stops keeps the key only in the RAM of its foreground TripService for a trip started while unlocked; drops it at trip end.
4. No gateway changes: no scoped keys, no new endpoints, no server-side crypto. Connect-key rotation only at migration and on passphrase change (one call each, existing revoke path, manual if no endpoint), never on a timer.
5. Drafts and live names dropped on restart. Exception for gateway load: identity map with its cursor and profile pictures kept on disk encrypted under DK. No message content; unreadable without passphrase and hardware; saves the full identity catch-up and avatar downloads on each unlock. A forgotten passphrase also discards this cache.
6. Content-free notifications while locked: yes. One notification with the count; zero gateway requests while locked.
7. Argon2id approved: m = 64 MiB, t = 3, p = 1, at most about 1 s on a mid-range phone. PBKDF2 only as a compile-time fallback.

## 14. Gateway load
Rule: cost the gateway less than today's eager sync, never more.
- Per unlock (cold start): at most 4 requests: 1 status, 1 chat list, 1 identity page with cursor, 1 event WebSocket live (no after= replay). No per-chat history in advance.
- Per opened chat: 1 page of 50; older history 1 page per scroll, never prefetched. In-memory page cache within the session.
- Per sync: live updates on the one WebSocket; no polling. On resync: 1 chat list plus 1 page for the chat on screen; other chats refetch lazily instead of today's gap filling across all chats.
- Media: auto-download off; fetched only on tap, one at a time. Thumbnails inline with messages. Pictures from the encrypted cache, refetched only when picture_id changes.
- Client limits: history concurrency 1, media concurrency 1; token bucket 6 requests in the first 10 s after unlock, 30 per minute after; at most one cold sync per 30 s; exponential backoff with jitter 2 s to 5 min honouring Retry-After and retry_after_ms; no retries while locked.
- Server-side work added: none.
To verify during implementation: merges stay correct with lazy identity catch-up; real request count of today's WaSyncRunner as the baseline for a regression test.

## 15. Implementation notes (rounds 1 and 2)

Round 1, `rosty_core::vault` (pure; the caller passes `Entropy`):
- Blobs, all AES-256-GCM with the header as associated data: key blob `RWVK | ver | kdf id | 3 x u32 kdf words | salt 16 |
  nonce 12 | DK+tag` (94 bytes); body `RWVB | ver | nonce | JSON{gateway_url, instance_id, connect_key}+tag`; cache `RWVC`
  (same shape, opaque bytes: the 13.5 identity and picture cache, a separate file so pictures do not rewrite the vault);
  daily `RWVD | ver | boot count u64 | last unlock ms i64 | nonce | DK+tag`. Distinct magics stop a blob of one kind from
  being opened as another.
- KDF: Argon2id (`argon2 =0.5.3`, RustCrypto, no `alloc`/`password-hash` features; working memory zeroized) at m = 64 MiB,
  t = 3, p = 1, parameters in the header. `calibrate(measured, budget)` picks the strongest of (64 MiB, 3/2/1),
  (46 MiB, 1), (19 MiB, 2) that fits; nothing weaker. PBKDF2-HMAC-SHA256 600 000 is the `vault-pbkdf2` cargo feature
  (compile-time default for new vaults; both KDF ids are always readable). About 0.6 s per derivation in the host checks
  profile.
- Passphrase: 26 Crockford base32 data characters (130 bits from 17 caller bytes) plus 2 check characters, shown as
  `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`. The check is a Reed-Solomon code over GF(32) with two check symbols (distance 3):
  every single wrong character and every swap of two characters is detected; parsing ignores case, spaces and dashes
  and reads I/L as 1 and O as 0. The KDF input is the 26 upper-case data characters.
- Change (rotation) makes a new passphrase, salt and DK, so an old key blob fails with the new passphrase and an old
  body blob fails under the new DK.

Round 2, the Keystore boundary (`rosty_ffi` `vault.*`, `rosty_app_core::wa_vault`):
- Kotlin never holds DK, KEK_p, the daily key or the connect key. It only seals and unseals ciphertext: the key blob
  (DK under KEK_p) with K_hw, and the daily blob (DK under a random daily key DDK) with K_daily. The smallest thing it
  must hold in plain form is therefore one opaque wrapped blob per unlock, which is ciphertext without the passphrase
  (K_hw layer) or without DDK (K_daily layer).
- DDK is stored next to the K_daily-sealed blob in `wa-vault-daily.json`: without the biometric-gated K_daily it opens
  nothing, which is exactly the 13.2 trust model ("DK is also sealed under K_daily"). The boot count and the unlock time
  are authenticated inside the daily blob; Rust refuses (and deletes the file) on another boot count, after 72 h, or
  with a clock set back more than 5 minutes. `vault.state {boot_count, now_ms}` applies the same rule from the plain
  copy, for the BootReceiver path.
- The passphrase is the one secret Kotlin holds (shown once, typed by the user). It travels in the JSON request; the JNI
  `call` export zeroizes its request and answer buffers for every `vault.*` op, and Rust keeps it in `Zeroizing`.
  Kotlin should keep it out of Strings where the UI allows (round 3).
- DK and the connect key live in a `vault` session handle (one per process; a new unlock replaces it). Releasing it
  (`vault.lock` or `RustNative.release`) zeroizes them and releases every `wa.open` gateway opened from the session.
  Event streams and call-audio handles made from such a gateway keep a client clone until Kotlin releases them; round 3
  must close them on lock.
- `wa.open` with an unlocked vault takes key and URL from it, refuses a `key` (`vault_unlocked`) and another gateway URL
  (`other_gateway`). While the vault is locked the old `{url, key}` path stays: every current Kotlin caller (Messages,
  Phone, Contacts, Stops, the settings link flow, `wa.create_instance` with no key) still passes the plaintext key and
  is rewritten in round 3; removing it now would break all four apps.
- Files (data dir, owner-only, atomic temp + rename): `wa-vault.json` {version, hw_alias, hw_sealed, body},
  `wa-vault-daily.json` {version, alias, sealed, daily_key, stamp}, `wa-vault-cache.bin` (cache blob).
- Wipe (`vault.wipe_plaintext`, refused while a plaintext link has no vault): data dir `wa-sync-*.db` (+ `-wal`, `-shm`,
  `-journal`; every row deleted with `secure_delete` and the file vacuumed first), `whatsapp-link.json`,
  `wa-live-names.json`, `wa-rekeys.json`, `wa-identity.json` (each with `.tmp`), the `wa:` entries of `drafts.json`;
  files dir `wa-media/`, `wa-stars.json`; cache dir `wa-status/`, `wa-avatars/`, `wa-media/`. Left for Kotlin (round 3):
  the `rosty_message_reminders` and `wa-link-share` SharedPreferences, the mutes in `messages-prefs.json`, and the
  Contacts people cache (`people.json`, `people-sync.json`).
- Not done in round 2: connect-key rotation on the gateway at migration and passphrase change (needs a Rust-side
  mint/revoke so the new key never reaches Kotlin), the cache ops, and moving `wa_sync` to memory-only.
