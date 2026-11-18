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

## 15. Implementation notes (rounds 1 to 6)

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

Round 3, Messages memory-only and the lazy sync (`rosty_app_core::wa_sync`, `wa_sync.*`/`vault.*` ops, `:wa`, `:messages`):
- Store: Messages opens `wa_sync.open {memory: true, lazy: true, vault_cache: true}`. The lazy sync is a flag of the
  store (`Store::set_lazy`), not a property of memory stores, so the eager tests and fixtures keep their meaning. Lazy:
  the plan and a refreshed chat list owe only the first page of the chat on screen (`wa_sync.focus`), a page never names
  the next one, other gaps stay open and are paged when their chat is opened (`wa_sync.open_page`, pure
  `plan::on_open`/`plan::lazy_pages`), a chat paged once is not paged again in the session, older history stays
  `wa_sync.older` (one page per scroll).
- Identity cache (13.5): the bytes stay in Rust. `wa_sync.open {vault_cache}` seeds the map and the catch-up cursor
  from `wa-vault-cache.bin` when the vault is unlocked for the store's instance (`Store::seed_identity`, only into a
  store that knows nobody); `wa_sync.cache_save` writes them back after a catch-up that learned pairs. Pictures:
  `vault.pictures` / `vault.picture_put` (4 MiB, oldest out); `WaAvatars` takes an `AvatarPictures` (Messages:
  `VaultAvatarPictures`, memory plus the encrypted cache) instead of `cacheDir/wa-avatars`. The session keeps the
  decrypted cache in memory once read; the file is rewritten whole on each write.
- Budget and back-off are pure Kotlin in `:wa` (`dev.rosty.wa.sync`: `WaRequestBudget`, `WaBackoff`, `WaRequestGate`),
  not Rust: every request is issued by Kotlin (`WaSyncRunner`, `WaDownloads`, `StatusFeed`, `WaAvatars`), so the
  gate sits where the calls are and needs no FFI hop per request. The lazy planner is Rust and tested there.
- One socket: in Messages the sync listens to the repository's event socket (live, no `after=`), and takes the status
  the repository fetched when it opened the gateway (`GatewayWaRemote(shareRepository = true)`). Cold start: socket,
  status, one identity page, chat list = 4 requests (eager baseline with 10 chats that missed messages: 26).
- Media: auto-download off; a tap downloads through the gate (concurrency 1) into RAM (`WaRamFiles`: `memfd_create`
  files read through `/proc/self/fd/<n>`, so the image loader, the players and the gateway's send read them by path;
  this is the "equivalent" of `MediaDataSource`). The store's LRU cap is set to 64 MiB. Thumbnails, Status pictures
  and files to send live there too. A gateway opened from the unlocked vault answers every download inline (no temp
  file in `cacheDir/wa-media`); while the vault is still locked and the old `{url, key}` path is used (until round 4
  wires the unlock), downloads over 1 MiB still pass through a temp file that Kotlin deletes at once.
- In memory: drafts (`MemoryWaDrafts`), live names (`MemoryLiveNameStore`), the rekey cursor (`MemoryRekeyCursor`), the
  outbox (the store). The outbox wake job does nothing in a cold process (`memoryOnly`); WorkManager carries no input
  data. Status loads 10 s after the gateway is reachable (out of the cold-start burst), through the gate.
- Lock: `MessagesContainer.lockWhatsApp()` -> `WaOffline.lock()`: the budget closes first, then the socket and the
  store, the RAM media; then the repository's gateway and event stream (`WaRepository.lock`, which also stops any
  reopen until `unlock`), Status pictures, thumbnails, drafts, picture memory, live names and `vault.lock`. Messages
  opens no call-audio handle (calls are Phone's).
- Not done in round 3 (user-visible or open): the outgoing file name of a document being sent now reads from the RAM
  path (`/proc/self/fd/<n>`), so the bubble may show a number until the echo arrives; the SMS/MMS export and the Share
  copies in `cacheDir/{share,exports,cards}` stay explicit exports; a "Remind me" on a WhatsApp message no longer
  stores the message preview in the alarm payload (`rosty_message_reminders`), only the chat title, so its
  notification shows no text; the migration wipe is round 6.

Round 4, the Keystore, the vault screens, the lock lifecycle and Messages (`:platform` `vault/`, `:wa` `vault/`,
`:shell`, `:messages`; Kotlin only, no Rust change):
- Keystore (`:platform` `VaultKeystore`, `AndroidVaultKeystore`, `BiometricVaultAuthenticator`; `FakeVaultKeystore` for
  JVM tests): AES-256-GCM keys generated in AndroidKeyStore, non-exportable, StrongBox when the phone has it (a
  `StrongBoxUnavailableException` falls back to the TEE), `setUnlockedDeviceRequired(true)`,
  `setUserAuthenticationParameters(0, …)` (every seal and open is its own `BiometricPrompt` with a `CryptoObject`).
  K_hw `rosty.wa.k.<id>`: Class 3 biometric or screen lock, not invalidated by enrolment (see the owner change below). K_daily
  `rosty.wa.d.b.<id>` (Class 3 biometric only) or `rosty.wa.d.c.<id>` (screen lock only, on a phone with no Class 3
  biometric enrolled; dropped as soon as one is). Sealed blobs are `0x01 | GCM nonce 12 | ciphertext+tag`, base64; only
  the opaque blobs of round 2 cross (Kotlin never holds DK, DDK or the connect key). A new K_hw alias per passphrase,
  the old key deleted after the commit; keys the vault no longer names are deleted on every read of the vault.
  `androidx.biometric` 1.1.0 (newest stable; nothing was cached) and `androidx.fragment` 1.9.1: `RostyShellActivity` is
  a `FragmentActivity` now (all four apps). The library's `USE_BIOMETRIC` stays in Messages (its `USE_FINGERPRINT` is
  removed); Phone, Contacts and Stops remove both until round 5.
- `WaVault` (`:wa`): the state (`NoLink`, `NeedsSetup`, `Locked(biometric, reason)`, `Unlocked`, `Broken`) and every
  operation, one at a time. Setup: `vault.create` (from `whatsapp-link.json`), the passphrase shown once, two random
  groups typed back, K_hw seal, `vault.commit`; then a second `vault.unlock` with the key blob still in hand gives the
  daily blob, sealed under a new K_daily (a second prompt, "Turn on quick unlock"; cancelling it only means the
  passphrase next time). Daily unlock: K_daily opens the daily blob, `vault.unlock_daily`. Passphrase unlock: the
  well-formedness is checked first (no prompt for a short or impossible passphrase), then K_hw opens the key blob for
  each try (one hardware authentication per guess), `vault.unlock`, and the daily unlock is offered again. Change:
  `vault.change`, the same two groups, a new K_hw, the old one deleted. Reset ("Forgot passphrase"): lock,
  `vault.reset`, every vault key deleted, then the app forgets its link like an unlink.
- Deviation (safer): a daily unlock does not re-seal a fresh daily blob. K_daily needs an authentication per use and
  one prompt authorizes one operation, so re-sealing would mean a second prompt on every unlock. The 72 h therefore
  count from the last passphrase unlock (the stamp of the blob), not from the last biometric unlock: the passphrase is
  asked at least every three days.
- Deviation (safer): after a passphrase change the daily unlock is not offered at once (a fresh `vault.unlock` would
  replace the session and release the gateway in use); the next unlock asks for the new passphrase once
  (`PassphraseReason.NewPassphrase`).
- Owner change (2026-11-18, replaces the harsh deviation): a biometric enrolment invalidates only the biometric
  K_daily. K_hw no longer uses `setInvalidatedByBiometricEnrollment`, so the vault asks for the passphrase once and
  seals a new daily key, as plan 6 and 13.2 list. `Broken` is left for a removed screen lock or a damaged file. This
  overrides the `setInvalidatedByBiometricEnrollment(true)` on K_hw in section 3.
- The drop rules (13.2): Rust drops the daily blob on another boot count, after 72 h and on a clock set back (read
  with `vault.state {boot_count, now_ms}` at every refresh and unlock); `BootReceiver` drops it on
  `BOOT_COMPLETED` (only when `wa-vault-daily.json` exists, so a phone without a vault starts no core); five failed
  biometric attempts (each `onAuthenticationFailed`, counted in the `rosty_wa_vault` preferences across processes, reset
  by a success) or a system lockout drop it; an invalidated K_daily drops it. Each drop deletes the K_daily key and
  names the reason on the unlock page.
- Lock lifecycle (`:shell` `VaultLockLifecycle`, `VaultLockRules`): screen off (a runtime `ACTION_SCREEN_OFF`
  receiver), the auto-lock delay in the background (default 60 s; 0, 30 s, 1 min, 5 min in the WhatsApp settings)
  with a timer and a check when an activity starts again (a cached process is frozen, its timer may not fire), "Lock
  WhatsApp now", and process death (nothing to do: the keys only live in memory). A system prompt over the app (the
  screen-lock activity) does not start the timer. Locking runs `lockWhatsApp()` (round 3's teardown, then `vault.lock`
  zeroizes DK and the connect key) and takes away every WhatsApp notification that shows text.
- The process is not ended on lock (plan 6 asks for it "for a clean wipe"): Messages is the SMS app and the same
  process receives SMS and MMS, sends scheduled texts, and plays voice notes; ending it could drop an MMS download or a
  send in flight. Not safe, so not done.
- `wa.open`: Messages' repository reads its link from the vault (`WaLinkSource`): the vault's URL and instance with
  an empty key while unlocked (so `wa.open` takes the key from the vault, every download is inline and the >1 MiB temp
  file of round 3 is gone), nothing while a vault is locked, and the plaintext link only while there is no vault yet
  (the link flow). The repository and the store start locked and follow the vault (`DefaultWaRepository(startLocked)`,
  `WaOffline(startLocked)`): no request before the unlock. The store follows the vault's instance, never the plaintext
  link.
- Notifications while locked (13.6): the push carries `kind`, `chat`, `count` (gateway `push/mod.rs`); `chat` is a JID
  with the number and is never shown. `RostyMessagingService` no longer calls `headless("wa_digest")`: with the vault
  open in the process the event socket brings the message; otherwise `WaPushNotices`/`LockedWhatsAppNotice` post one
  notice, "WhatsApp", "N new messages", no action (so no inline reply), public on the lock screen, the count kept in
  the `rosty_wa_locked` preferences until the unlock or a reboot. No outbox retry while locked (the store is closed
  and the wake job does nothing in a memory-only process).
- Screens (`:wa` `VaultScreens`, `VaultGate`, `PassphraseField`): setup and change (passphrase in groups of four,
  monospace; "Copy passphrase" through the sensitive clipboard, cleared after 60 s while the clip is still ours or
  unreadable), unlock (biometric button and an automatic prompt once per lock; the passphrase field with the reason),
  "Forgot passphrase" and the broken vault (reset with a confirmation). The passphrase field is a platform `EditText`
  (Compose cannot set `IME_FLAG_NO_PERSONALIZED_LEARNING`): visible-password input, no suggestions, autofill hint
  `password`, paste from the long-press menu. `SecureWindow` holds `FLAG_SECURE` per window: every vault page, the
  WhatsApp pages while unlocked, and the whole Messages window while the vault is open (the merged inbox, search and
  stars can show WhatsApp text). Compose overlays are drawn in the same window; the system prompt is the system's.
- Messages entry: the WhatsApp tab, a WhatsApp chat and the WhatsApp settings sit behind `WaVaultGate`: no link -> the
  link flow, then the setup as soon as a plaintext link exists (migration from `whatsapp-link.json`; deleting it is
  round 6); locked -> the unlock page; broken -> the reset. In the settings, a new link (another key or gateway) and
  "Unlink" reset the vault first (it holds the old key).
- Still open: `whatsapp-link.json` stays on disk next to the vault until round 6 (the settings' "Show connect key" and
  the sibling hand-over still read it); a reset is followed by the automatic sibling hand-over when Phone, Contacts or
  Stops still hold a plaintext link (round 5); the clipboard is not cleared when the process dies within the minute;
  the connect-key rotation at setup and change still needs the Rust mint/revoke (round 2 note).

Round 5, the vault in Phone, Contacts and Stops, and the in-memory hand-over (`rosty_ffi` `vault.export_link`,
`vault.trip_hold`/`vault.trip_release`, `wa_idbook.* {vault}`; `:core-bridge`, `:platform`, `:wa`, `:shell`, `:people`,
`:phone`, `:contacts`, `:stops`, `:messages`):
- One wiring for all four apps (`:shell` `ShellContainer`): `vaultOps`, `vaultKeystore`, `waVault`, `vaultFlow`,
  `vaultLock` (now never null), `waLinkSource` and the vault-following `newWaRepository()` moved there from Messages.
  An app adds its own teardown by overriding `lockWhatsApp`, `unlockedWhatsApp` and `forgetWhatsApp`. Each app keeps
  its own vault files and Keystore aliases (its own data directory and Keystore). Shared pages: `:shell`
  `WhatsAppVaultSettings` (the link screen behind `WaVaultGate`, the vault group, "Change passphrase") and
  `WaVaultGated`; `VaultSettingsRepository` and `MemoryLiveNameStore` moved to `:wa` and `:people`; `VaultBoot` drops the
  daily unlock from the boot receivers of Messages, Phone and Stops (Contacts has none: Rust's boot-count check at the
  next refresh does the same). Profile pictures of every app go to the vault cache (`VaultAvatarPictures`).
  `USE_BIOMETRIC` is back in Phone, Contacts and Stops (`USE_FINGERPRINT` stays removed).
- No Kotlin path reads or writes `whatsapp-link.json` any more. A link made, pasted or handed over goes to
  `WaVault.adopt` (`vault.create {link}`): Rust keeps it in the uncommitted session of the setup, the state turns
  `NeedsSetup` and the setup screens follow; only the committed vault holds it. `waLinkSource` answers the open vault's
  link with no key, else nothing. A plaintext file of an earlier build is still read by Rust alone (`vault.create`
  without a link, the migration); deleting it stays round 6.
- Hand-over (`WaLinkProvider`, `dev.rosty.permission.WA_LINK`): `/link` answers only while this process has its vault
  unlocked, from `vault.export_link {handle}` (current, committed session only; the JNI buffers of `vault.*` are
  zeroized); `/offer` says whether it would, without the key. A locked vault or a cold process answers no row; no link
  file is read. The receiver asks only on the person's tap ("Use the link from …" on its link screen, which starts its
  vault setup) and passes the link straight to `vault.create {link}`; the key crosses Kotlin memory in the cursor and a
  `StoredLink` only (no zeroizing on the JVM). The automatic ask at container start, the offer of a removed link and
  its one-way mark (`declined`, a SHA-256 of the key in `wa-link-share`) are gone. Stops has no WA_LINK permission and
  still takes no part (link by paste or QR).
- Phone: the LID table (`wa_idbook.* {vault: true}`) lives only in the vault cache (`VaultCache.id_book`); the first
  unlock moves a `wa-identity.json` in and deletes it; locked, every op answers `locked`. The lock forgets the table in
  memory, the asked names (`wa-live-names.json` is deleted, names are in memory only) and the full-size pictures
  (`cacheDir/wa-full`, emptied on lock). A push while locked (`WaPush.locked`, true in a cold process) shows the call
  content-free: "WhatsApp call", no name, no number, no lookup, no wake of the stream, no full-screen intent, public
  on the lock screen. Answer opens Phone (the screen unlock, then `PhoneRoute.WhatsAppUnlock`, which closes once the
  vault is open); the answer goes out then, if the gateway still rings (most such calls are missed, 13.3). Decline only
  stops the ring here: the caller hears it ring until the gateway's 60 s timeout, then it is a missed call. The missed
  notice is content-free too. The whole Phone window is FLAG_SECURE while the vault is open (WhatsApp calls and peers
  show in Recents, the call screens and a person).
- Phone call history found: the native call log is system data and stays; WhatsApp calls are not stored by the app
  (the list is the gateway's, in memory); `wa_calls_forgotten` (SharedPreferences) holds only the opaque gateway ids
  of deleted calls (no peer, no time), kept; a call-back reminder holds the number and name the person chose to be
  reminded of, kept as an explicit action (like round 3's reminders); CSV exports are explicit.
- Contacts: a people sync run that finds the vault locked (the 12-hourly work, its continuation, a start from the app,
  the foreground trigger) gets no host (`peopleSyncWhenUnlocked`), makes no request and ends; the unlock runs the
  foreground trigger, which syncs only when the last run is older than the period, so the deferred run costs the
  gateway nothing extra. Same for Phone without Contacts (`standaloneSync`, `StandalonePeople.syncIfStale`). The sync's
  gateway address comes from `waLinkSource`.
- Contacts storage decision: `people.json`, `people-sync.json` and the people pictures stay Contacts' (and Phone's
  standalone) own data on disk. They are identity data (names and pictures of numbers already in the address book or
  the call log), no message, no key and no LID pairs; they are what Contacts' people provider hands Phone and Messages.
  Under the vault they would be unreadable whenever Contacts is locked, so every surface would lose names and pictures
  or each app would run its own gateway people sync (more gateway load, against 14). 13.5's DK-only rule is kept for
  the WhatsApp identity map and the avatar cache.
- Stops: the arriving-soon message is sent through a link the trip keeps in RAM (`vault.trip_hold`,
  `wa_link::send::hold_trip_link`; `trip_fix` uses `send_text_held`, never a link file). It is copied when a trip with a
  message starts while the vault is open and dropped at every end of the trip (`stops::store::end_trip`, the user's
  stop, the service's stopped/ended status, `vault.trip_release`); it survives the vault's lock. A trip started while
  locked carries no message (the row under Start is hidden then). A process death loses the copy: a trip resumed
  after it sends no message. The chat picker of the message is behind the vault.
- Deviation (safer): the content-free locked call names nobody, not even a saved contact (no lookup while locked).
- Deviation (safer): no sibling link is taken in the background; the link screen offers it and the tap takes it.
- User-visible differences: Phone, Contacts and Stops ask for a passphrase setup and an unlock before their WhatsApp
  pages; Phone's window cannot be screenshotted while WhatsApp is unlocked; locked WhatsApp calls show as "WhatsApp
  call" with no full-screen ring, and Answer asks for the screen unlock and the vault first; a sibling's link is used
  only on the tap and only while that app is unlocked; Contacts' and Phone's people sync waits for the next unlock;
  Stops sends the arriving-soon message only for trips started while unlocked; each app's WhatsApp names asked on
  demand are forgotten on lock and restart.
- Not verified on a device: the BiometricPrompt over each app, the Answer path from the lock screen to the vault page,
  the CallStyle notice without a full-screen intent on HyperOS, the trip message after a lock and the people sync's
  deferral across a real WorkManager period.

Round 6, the migration that deletes the plaintext leftovers, the docs and the device test plan (`:wa`
`WaPlaintextMigration`, `:shell`, `:platform`, `:messages`, `:contacts`; Kotlin only, no Rust change):
- One migration for all four apps (`:wa` `WaPlaintextMigration`, wired in `ShellContainer.migratePlaintext`): it runs
  at every activity start (`RostyShellActivity.onStart`, off the main thread) and after every unlock (after the app's
  own `unlockedWhatsApp`, off the vault's lock) until it has finished once; the mark is `plaintext_wiped` in the
  `rosty_wa_migration` preferences, read before anything else, so a finished migration starts no core and reads no
  file. A run with any failure (a file Rust could not delete, the core not answering, a `messages-prefs.json` the
  store cannot read, which it never overwrites) leaves the mark unset and the next start tries again.
- When: the vault unlocked, no link, or a broken vault: at once. Locked: at once too, unless Phone's
  `wa-identity.json` is still there (the first unlock moves it into the vault cache, round 5): then at the next
  unlock. Never while a link waits for its vault (`NeedsSetup`) or the state is not read yet; Rust also refuses
  (`no_vault`) while `whatsapp-link.json` has no vault. So the link's content is always in a committed vault before
  the file goes, and with no vault the person sets one up from it (plan 8) or links again.
- What goes, Rust (`vault.wipe_plaintext`, the unchanged inventory of round 2): `wa-sync-*.db` with `-wal`, `-shm`,
  `-journal` (erased in place first), `whatsapp-link.json`, `wa-live-names.json`, `wa-rekeys.json`,
  `wa-identity.json` (each with `.tmp`), the `wa:` entries of `drafts.json`, `files/wa-media/`, `files/wa-stars.json`,
  `cache/wa-status/`, `cache/wa-avatars/`, `cache/wa-media/`. Kotlin, before the Rust wipe (`WaLegacyLeftovers` and
  the app's `dropPlaintextLeftovers`): the `declined` mark in `wa-link-share` (a SHA-256 of a connect key; the
  `adopted_*` entries stay: an instance id and a package name, still read by the hand-over), the message text of
  WhatsApp reminders in `rosty_message_reminders` (`preview` emptied, `notif_body` set to Rust's "Open the message",
  as for a reminder made now; the reminder, its chat title, time and alarm stay; SMS reminders keep their text),
  `cacheDir/wa-full` (full-size WhatsApp pictures), and in Messages the `wa:` drafts and the `wa:` mutes of
  `messages-prefs.json` (`MessagesStore.forgetThreads("wa:")`, flushed before Rust's wipe so no later write of the
  store brings them back; the notifier's copy of the mutes is refreshed).
- Kept: `people.json`, `people-sync.json` and the people pictures (round 5 decision), `recent-reactions.json`,
  `wa_calls_forgotten`, call-back reminders, `rosty_wa_locked` (a count), `rosty_wa_vault` (failed attempts and the
  auto-lock delay), the Stops notice chat choice, SMS data and every setting.
- WhatsApp mutes are no longer written to `messages-prefs.json` (`InboxViewModel` mutes and unmutes the SMS threads of
  a row there, the WhatsApp chat on the gateway only; the plan names no vault-cache home for them, so they are
  dropped). The inbox row and the notifications already followed the chat's `muted_until`, so a WhatsApp chat muted
  in WhatsApp stays muted. User-visible: Settings, Notifications, "Muted conversations" lists SMS conversations only
  (open question for the owner: list the WhatsApp chats muted in WhatsApp there while unlocked, from the store).
- Fixed on the way (safer): "Remind me in 1 hour" on a WhatsApp notification sent the message text to Rust, which put
  it into the alarm payload (`rosty_message_reminders`); `ReplyActions.remindRequest` now sends no body for a `wa:`
  tag, like round 3's "Remind me" on a message. Contacts now empties `cacheDir/wa-full` on lock, as Phone does.
- Deviation (safer than "on the first unlocked start"): a locked vault migrates at once when nothing waits to move
  into it, so the plaintext connect key and the old store do not wait for the person's next unlock.
- Known limit: an app whose vault is never set up after the update (for example Stops left alone) keeps its
  `whatsapp-link.json` until its setup, "Forgot passphrase" or Unlink. Deleting it without a vault would lose the
  link the plan migrates; the device plan checks it.
- Not done (unchanged from rounds 2 and 4): connect-key rotation at migration and passphrase change (needs a Rust-side
  mint/revoke), and the UI's advice to rotate the key and that only a factory reset guarantees erasure (plan 8).
- Tests: `:wa` `WaPlaintextMigrationTest` (every vault state, the key never wiped before a vault holds it, once, a
  failure retried, the leftovers gone and other preferences and files untouched), `:shell` `WaMigrationWiringTest`
  (the setup's commit then the wipe, a locked vault waiting for `wa-identity.json`, a finished migration touching
  neither the vault nor the core), `:platform` `WhatsAppReminderTextTest`, `:messages` `WaPlaintextLeftoversTest`.
  Rust's inventory stays covered by `rosty_app_core::wa_vault` `the_wipe_removes_every_listed_plaintext_file`.
- Docs: `docs/architecture.md` (Storage, AD-MSG5, AD-WA2 and the new "WhatsApp vault (AD-WA12)"),
  `docs/whatsapp-gateway.md` (how the apps keep the key, push while locked), `docs/android.md` ("WhatsApp vault
  (Kotlin apps)"). Device test plan: `wa-vault-device-tests.md`.
- Not verified on a device: everything in `wa-vault-device-tests.md`, in particular the migration from an old
  install, BiometricPrompt in every app, the enrolment change, the reboot, the lock lifecycle, the locked call notice
  on HyperOS, the trip message after a lock, the people sync deferral, the hand-over between two installed apps,
  memfd under SELinux and FLAG_SECURE.
