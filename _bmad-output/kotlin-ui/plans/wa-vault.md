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

## 6. Lock and unlock (superseded for daily use by section 13.2, and the lock timings by round 7c in section 15)
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
2. Passphrase rarely, biometric daily (owner constraint; supersedes "passphrase on every unlock"). **Timings
   superseded by round 7c (section 15):** no reboot rule, no 72 h, no 30 days, no auto-lock, no screen-off lock; the
   vault stays unlocked and needs the passphrase again only after 90 days without a use.
   - Passphrase typed at setup and again only after a reboot, on a new device or reinstall, after 72 h without an unlock, after a biometric enrolment change, or after 5 failed daily unlocks.
   - After a passphrase unlock, DK is also sealed under K_daily (AES-256-GCM, non-exportable, StrongBox when present, auth required per use, setInvalidatedByBiometricEnrollment(true)). Only the sealed blob is on disk.
   - Daily unlock: BiometricPrompt with a CryptoObject on K_daily, Class 3 biometric. Lock-screen PIN accepted only on phones with no Class 3 biometric enrolled.
   - Reboot and idle rules: the blob carries boot count and last unlock time as authenticated data; on mismatch, and from BootReceiver, the app deletes K_daily and the blob. Enforced by the app, not hardware.
   - What a thief gets: phone stolen unlocked, attacker without the owner's finger or face: nothing. Attacker who knows the PIN on a biometric phone: enrolling their own fingerprint destroys K_daily, passphrase still needed. Attacker who passes the biometric (coercion, sleeping owner) or knows the PIN on a PIN-only phone: full read and send access through the gateway until the next reboot or 72 h idle. This is the price of not typing.
   - What the passphrase still protects: powered-off, rebooted or forensically imaged phone; 72 h untouched; restored or cloned install; a newly enrolled biometric. Limit: root plus PIN plus a running phone can bypass the app rules, but still needs hardware authentication for K_daily.
   - Gateway cost: none.
3. Vault in all four apps. One plaintext whatsapp-link.json anywhere voids everything. Each app has its own vault and Keystore key; WaLinkProvider hands the key over only between unlocked apps, in memory. Locked Phone shows the incoming call from the push payload (no gateway request) and needs unlock to answer, so most such calls will be missed. Stops keeps the key only in the RAM of its foreground TripService for a trip started while unlocked; drops it at trip end.
4. No gateway changes (until round 7b, which seals push previews; section 15): no scoped keys, no new endpoints, no server-side crypto. Connect-key rotation only at migration and on passphrase change (one call each, existing revoke path, manual if no endpoint), never on a timer.
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

## 15. Implementation notes (rounds 1 to 7c)

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

Round 7a, the passphrase clock, one prompt for the four apps, the history after an unlock and the WhatsApp mutes
(`rosty_core::vault` `clock`/`link`, `rosty_app_core::wa_vault`, `wa_sync::history`, `rosty_ffi` `vault.unlock_link`,
`vault.unlock_daily {biometric}`, `wa_sync.history_save`; `:core-bridge`, `:wa`, `:shell`, `:messages`, `:stops`, the
other manifests). Owner direction: keep the threat model (seized or cloned device, other apps, backups) and make the UX
as good as possible; a safer option only where its UX cost is invisible.
- Passphrase clock (replaces round 4's deviation "the 72 h count from the last passphrase unlock"; **superseded by
  round 7c**: the 72 h, the 30 days, the reboot rule and the `RWVA` seen record are gone). The daily blob stays
  sealed once, at the passphrase unlock: its authenticated stamp is the passphrase epoch (boot count, passphrase time).
  The time of the last unlock lives in a *seen* record, `RWVA | ver | boot u64 | passphrase ms i64 | last unlock ms
  i64 | nonce | tag` sealed under DK (no plaintext, the header is the associated data), stored as `seen` in
  `wa-vault-daily.json` next to a plain copy `last_unlock_ms` for `vault.state`. The daily unlock opens DK anyway, so
  Rust reads the record, checks it, and writes the next one: one prompt. Rules (`DailyClock::check`, pure, tested):
  the stamp's boot count; the clock not more than 5 min behind the last unlock; at most 30 days
  (`DAILY_MAX_AGE_MS`, refusal `expired`) since the passphrase; at most 72 h since the last unlock (`idle`). A record
  of another epoch (other boot or passphrase time), a damaged one, one under another DK or none at all leaves the clock
  at the passphrase time, so deleting or rolling back the file only makes the rule stricter, and forging one needs DK.
  Only a Class 3 biometric K_daily moves the clock (`vault.unlock_daily {biometric: true}`, Kotlin sets it from the
  alias kind); a screen-lock K_daily (a phone with no Class 3 biometric) keeps the 72 h from the passphrase (task
  text; see the open questions). Reboot, a new enrolment, five failed attempts and a lockout still drop the daily
  unlock at once. New UI reasons: `Idle` now reads "WhatsApp wasn't unlocked for three days", `Expired` "The
  passphrase was last typed 30 days ago" (en, tr).
- One prompt for the four apps. Design: each vault also stores a *link wrap*, `RWVL | ver | salt 16 | nonce 12 |
  DK+tag` with KL = PBKDF2-HMAC-SHA256(connect key, salt ‖ "rosty-wa-link", 1 round) (`vault::wrap_for_link`; one
  round, since the connect key is 32 random bytes, not a password). It is written in `wa-vault.json` (`link_sealed`)
  at every commit (setup, passphrase change: the new DK) and, for a vault of an earlier build, at its next normal unlock
  (best effort; until then the hand-over answers `no_link_wrap` and the normal prompt shows). When a locked app's unlock
  page shows (`VaultFlow.autoUnlock`, once per lock, also from Settings' unlock row), `WaVault.unlockFromSibling` first
  asks the siblings' `WaLinkProvider` `/link` in order (Messages, Phone, Contacts, Stops); a sibling answers only while
  its own vault is unlocked (`vault.export_link`, memory only, round 5). The link goes straight to `vault.unlock_link
  {link, boot_count, now_ms}`: Rust unwraps DK with the handed key, opens the body and requires the same gateway URL
  and instance (`other_link` otherwise), and opens this app's own session; no daily blob is prepared (that would need a
  prompt). Only if no sibling answers does the biometric prompt (or the passphrase page) show, so the person never sees
  two prompts for one unlock. The hand-over also moves the idle clock of a still valid daily unlock (same boot, within
  72 h and 30 days); a lapsed one is dropped as `vault.state` would. The window is the source's own lock rules: a
  sibling answers only while it is unlocked, that is in the foreground or within its auto-lock delay in the background
  (at most 5 min, default 1 min); screen off locks every app. So "about 5 min, or while any of them is in the
  foreground" holds with the delay set to 5 min; with the default it is 1 min (a shorter window, no new rule).
- Why it does not weaken the threat model: (1) seized, imaged or cloned device: the wrap is under a key derived from the
  connect key, which is on disk only inside the body, under DK; a disk image holds no way in that it did not have
  before. Whoever holds the connect key already reads and sends everything through the gateway, so DK (which only adds
  the caches) is no new secret for them. (2) Other apps: the provider is `exported` only behind the signature
  permission `WA_LINK` (read and write), checks the caller's signing certificates itself, and the receiver checks the
  sibling's signature before it asks (`ResolverSiblingLinks`); a foreign app that claims a sibling's authority can only
  hand over a wrong key, which fails the GCM tag. (3) Backups: `allowBackup=false`, `noBackupFilesDir`. No plaintext
  is written, DK never leaves its app (each app opens its own DK with its own wrap), the key crosses Kotlin memory only
  in the cursor and the request (as in round 5). After a reboot a sibling still needs its own passphrase first, and an
  enrolment change invalidates every app's K_daily, so the hand-over adds no path around those rules: it only spares the
  second prompt.
- Lock semantics stay per app (screen off, background delay), with one change: "Lock WhatsApp now" locks every Rosty
  app (`ShellContainer.lockWhatsAppEverywhere`, `:wa` `WaLockAll`). Otherwise a sibling left unlocked would open the
  app again with no prompt right after its "Lock now". The broadcast carries nothing, is sent per sibling package with
  `WA_LINK` as receiver permission and heard only by runtime receivers registered with `WA_LINK` as broadcast
  permission: a cold process is not started (it has nothing unlocked) and no other signature can send or receive it.
  The UX cost is invisible: the other apps simply show their unlock page next time, which the hand-over then opens if
  one app is unlocked again.
- Setup without a tap: the link screen (`WhatsAppSettingsViewModel.start`) takes an unlocked sibling's link by itself
  (`WaLinkHandover.takeAutomatically`) and the setup follows, unless the person removed this app's link before (unlink
  or reset set `auto_off` in `wa-link-share`; then it offers the link and the tap takes it, as in round 5). With no
  sibling unlocked, the screen offers paste and QR as before. Stops now takes part: `WaLinkProvider`, the `WA_LINK`
  permission and the provider queries in all four manifests (`WaLinkShare.PACKAGES` ends with Stops).
- History after an unlock (Messages). `Store::history_snapshot` keeps the 40 chats listed first (pinned, then by newest
  message; hidden chats left out) with their 30 newest messages each (messages a clear left out are not kept),
  thumbnails up to 2 MiB in all (newest first; a message over the budget keeps no thumbnail), every other column up to
  4 MiB in all, and the chats' `chat_sync` cursors (oldest kept message as `oldest_ts`, `reached_start` only when every
  message was kept, an open gap kept open and started over from the top). Never the outbox (lost on restart as before),
  never a media row or file (full media stays fetch-on-tap). The JSON is sealed under DK in the RWVC format into its
  own file `wa-vault-history.bin` (so a save does not rewrite the pictures), written by `wa_sync.history_save` when
  WhatsApp locks (`WaOffline.closeLocked`, before `vault.lock`) and when the app goes to the background
  (`VaultLockLifecycle` -> `ShellContainer.wentToBackground`, Messages only, skipped when the delay is "at once").
  `wa_sync.open {vault_cache}` seeds it into the new memory store (`Store::seed_history`, only into a store with no
  chat, only for the same instance and schema version) and re-runs the identity backfill over the seeded rows. Nothing
  the lazy sync owes changes: the cold start is still status, chat list, one identity page and the socket (4); a chat
  whose newest message moved on gets a gap from the kept newest up, and opening any chat still costs its one page
  (tested against an empty store: same pages). The file is deleted by a reset, an unlink (both `vault.reset`) and a
  passphrase change (`vault.commit`); a file that does not open under DK is deleted.
- Muted conversations: Settings, Notifications lists the SMS mutes (a `wa:` entry left in the preferences by an
  earlier build is no longer listed) and, while the vault is open, the WhatsApp chats muted in WhatsApp
  (`muted_until_ms` -1 or in the future, from the chats in memory, named by the name rules); Unmute on one goes through
  the chat action (`WaChats.act(Unmute)`). While the vault is locked, one row "Unlock to see WhatsApp chats" (en, tr)
  runs the unlock: a sibling hand-over, else the biometric prompt over the page, else (passphrase needed) it opens the
  WhatsApp settings page, whose gate shows the passphrase page. Goldens: `e6.12-notifications-wa-unlocked`,
  `e6.12-notifications-wa-locked` (light and dark) and `-tr` of the locked page.
- Unchanged as decided: no process kill on lock (SMS/MMS), FLAG_SECURE, no gateway change.
- Tests: Rust `rosty_core::vault` (idle reset, 30-day cap, boot and clock back after a later unlock, a seen record of
  another epoch or from the past, the seen blob under DK only, the link wrap with the vault's key only),
  `rosty_app_core::wa_vault` (older files without the new fields, the daily file's sealed and plain clock, the history
  file under DK only, `remove_all` deletes it), `wa_sync::history_tests` (same inbox and threads after a seed, the
  40 x 30 bound and the pinned order, the thumbnail budget, hidden and cleared rows, refused seeds, no request more
  than an empty store), `rosty_ffi` vault unit tests (hand-over with the vault's own key only, the wrap written at the
  first unlock of an older vault, the biometric idle reset up to 30 days, no reset for a screen-lock key) and fixtures
  (`vault.unlock_link` 4 cases, `vault.unlock_daily.idle_reset`/`expired`, `wa_sync.history_save` 2 cases,
  `wa_sync.open.vault_history`; the seeded fixture vault now carries a link wrap). Kotlin: `:wa` `WaVaultTest` (idle
  reset, 30 days, hand-over with no prompt, hand-over after a reboot, no sibling or another link), `WaLinkHandoverTest`,
  `WaSettingsHandoverTest` (taken with no tap, offered after an unlink), `:messages` `WaVaultSyncTest` (history written
  at the lock and in the background, nothing while locked, the budget), `NotificationSettingsViewModelTest`,
  `E612ShotTest`, the manifest tests of the four apps and `AppPermissionsTest`.
- Open questions for the owner: (1) a phone with only a screen lock keeps "72 h since the passphrase" (the task names
  the Class 3 biometric); moving the clock on a screen-lock unlock too would be a one-line change (`biometric` flag) but
  lets a PIN holder keep access for 30 days instead of 72 h. (2) After a reboot each app's daily unlock is gone; an app
  unlocked only through hand-overs asks for its own passphrase the next time it is unlocked alone. Offering "Turn on
  quick unlock" after a hand-over would need a prompt right after the silent unlock, so it is not done. (3) The window
  is each source app's auto-lock delay (default 1 min, at most 5 min), not a fixed 5 min: a fixed 5 min would keep a
  sibling's vault unlocked in the background longer than its owner chose.
- Not verified on a device: everything in `wa-vault-device-tests.md` 8 to 8d, in particular the provider queries
  between four installed apps (and the start-up cost of a cold sibling), the lock-all broadcast to runtime receivers,
  the history file across a real lock and process death, and the unlock row's prompt over the Settings page.

Round 7b, end-to-end sealed push previews, the preview setting and the locked WhatsApp call (`services/wa_gateway`
`push::seal`, store migration v10; `rosty_wa_proto` `PushKey`/`PushKeyedRegistration`; `rosty_wa_client`,
`rosty_ffi` `wa.register_push`; `:core-bridge`, `:platform` `wa/push`, `:wa`, `:shell`, `:messages`, `:phone`). Owner
direction: do not hurt the UX, keep the threat model (seized or cloned device, other apps, backups). This round
changes the gateway (13.4 said no gateway change; the owner allowed this one).
- Push key: each install that has Firebase makes one EC P-256 key in Android Keystore for key agreement
  (`PURPOSE_AGREE_KEY`, minSdk 34), alias `rosty.wa.push.<16 hex id>` (the id is the alias, nothing else is stored),
  non-exportable, no user authentication and no unlocked-device requirement (it must open a push while the phone and
  the vault are locked), TEE (StrongBox key agreement is not on every phone). Made on the first registration that has
  an FCM token (`AndroidPushKeyStore.ensure`); deleted by `ShellContainer.forgetWhatsApp` (a reset, an unlink, the
  reset before a new link), so the next link registers a new key and a push sealed to the old one falls back to the
  content-free notice. Choice: ECDH rather than RSA-OAEP, because minSdk 34 has Keystore key agreement everywhere, the
  wrapped key is 65 bytes instead of 256 (more room for the preview under FCM's 4 KB) and the gateway already has
  `ring` (P-256 ECDH, HKDF, AES-GCM): no new crate anywhere.
- Registration: `wa.register_push` now takes `PushKeyedRegistration {app, token, push_key?: {key_id, version: 1,
  public_key: base64 SPKI}}` on the same `PUT /v1/push` (one request, as before; 14 holds). The gateway checks the key
  (id `[A-Za-z0-9_-]{1,64}`, version 1, a P-256 SPKI whose point is on the curve) and refuses a bad one with 400; a
  registration without a key clears a stored one. `PushRegistration` keeps its shape for older callers.
- Gateway DB: schema v10 adds `push_tokens.key_id TEXT`, `key_version INTEGER`, `public_key BLOB` (65-byte point), all
  nullable. Migration: `ALTER TABLE ... ADD COLUMN` in one transaction at start (`PRAGMA user_version` 9 -> 10); every
  row stays, existing tokens get content-free pushes until the app registers again with a key (its next link-up). No
  data is rewritten; a v10 database is refused by an older gateway binary ("newer than this gateway"), so a rollback
  needs the DB backup.
- Sealing (`push::seal`, scheme 1): per push a fresh P-256 key pair; z = ECDH(ephemeral, device); k =
  HKDF-SHA256(salt = ephemeral point, ikm = z, info = "rosty-wa-push/1" | device point); AES-256-GCM with a random
  12-byte nonce and AAD "rosty-wa-push/1|" kid. FCM data: `kind` (and `call_id`, plus `missed` for a call end) in
  clear, `v`, `kid`, `epk` (base64 point), `ct` (base64 nonce | ciphertext | tag). Sealed JSON: a message has `chat`,
  `chat_alt`, `chat_name` (the stored chat name: one local read, only when some token has a key), `group`, `sender`,
  `sender_alt`, `name` (push name), `text` (whole text, else caption, else media label; at most 1024 bytes, then halved
  until the data map is at most 3800 bytes), `id`, `ts`, `count`; a call or call end has `sender` and `name`. A token
  without a key gets today's payload. Nothing is logged but the app and the error. In clear by design: the kind and the
  opaque call id (so a push that cannot be opened still rings and ends the ring) and whether a call was missed; timing
  and size were visible to Google before too. Chat ids, numbers and names, which the old payload carried in clear,
  are now sealed for apps with a key.
- App side: `WaPush.onMessage` opens a sealed push with the Keystore key (`SealedPush.open`, pure JCA but the ECDH)
  and returns an `OpenedPush` whose `data` reads like the old payload, so every reader stays; `routing` (clear keys
  only) is what goes on the `wa_push` event. Nothing decrypted is written: it goes into the notification only; with
  the vault open the event socket brings the message as before (the push content is not put into the RAM store).
  A push that does not open (rotated key, other key id, tampered) gives the content-free notice.
- Messages while locked (`WaPushNotices`, `LockedWhatsAppNotice.addChat`): one notice per chat, title = device contact
  of the number (contacts are not vault secrets), else the chat name, else the push name, else the number; a group by
  its subject with "Author: text" lines; up to 6 lines and the chat's count kept only in the notification's extras.
  `VISIBILITY_PRIVATE` with the public version "WhatsApp, N new messages" (the total kept as a number in
  `rosty_wa_locked`). No action. A push without a key names the chat only when a device contact owns its number (name
  and count, no text, private); otherwise the content-free notice (public, as round 4). The unlock clears them all.
- Preview setting (Settings, Notifications, "WhatsApp previews": "Name and preview" default, "Name only", "No content",
  hint "Apps with notification access, and Android's notification history if it is on, can see what is shown"; en,
  tr; in the `rosty_wa_preview` preferences, a setting only, so a cold process reads it without the core). It applies
  to the locked notices and to the unlocked ones (`MessagesNotifier.forPreview`): "Name only" blanks the lines (their
  count stays) and shows "N new messages", "No content" also titles "WhatsApp", drops the picture and the inline reply.
  What the notification's extras keep follows the setting too (a listener reads no more than the screen). Unlocked
  WhatsApp notifications are now `VISIBILITY_PRIVATE` with the same public version. The group shows only when WhatsApp
  is linked. Goldens `e6.12-notifications-wa-preview` (light, dark) and `-tr`.
- Phone, locked call: the ring is titled by `waLockedCallerTitle` (device contact, else the sealed name, else the
  number, else "WhatsApp call"; an unsealed push is named by a device contact only, never by its clear name), public
  on the lock screen like any call. The full-screen intent is back: it, the tap and Answer open `WaLockedCallActivity`
  (own task, excluded from recents, `setShowWhenLocked`/`setTurnScreenOn` in code), which shows only the caller with
  Answer and Decline (the native fallback layout) and closes when the ring is over. Answer stops the ring,
  `requestDismissKeyguard` (the vault keys need an unlocked phone: `setUnlockedDeviceRequired`), then hands the answer
  to `MainActivity` as a notification answer; `PhoneApp` now opens the vault inline (`WaVault.unlockInline`: a sibling
  hand-over, asked for at most 600 ms, else the biometric prompt over the page) and only falls back to the unlock page
  when the passphrase is needed or the prompt was cancelled; then `WaCallActions` accepts. `WaAnswerTiming` logs each
  step as "<step> after N ms" under `wa-answer` (no id, number or name). Decline still only stops the ring here.
- Threat-model notes (accepted, mitigated by the setting): (a) apps the person granted notification access receive
  the decrypted sender and text of every WhatsApp notification, locked or not; (b) Android's notification history, when
  the person turned it on, keeps them in a CE-encrypted system store (readable only after the first unlock since boot).
  Otherwise unchanged: a seized or cloned device holds the push key only inside the Keystore (non-exportable; a clone
  gets no key and pushes stop opening), nothing decrypted is on disk in the app, the key and no content are in backups
  (`allowBackup=false`; Keystore keys are never backed up). A running phone in someone else's hands shows the previews
  the person chose, like any messenger; "No content" restores round 4's behaviour. The lock screen never shows message
  content; a caller's name for a ringing call is shown, as for a phone call.
- Unchanged as decided: no process kill on lock, FLAG_SECURE, the other vault rules, 14 (no extra request: the key
  rides on the existing registration; the gateway adds one local DB read per push with a keyed token).
- Tests: Rust `push::seal` (round trip with a test device key, fresh ephemeral and nonce, the kid bound as AAD, key
  checks), `push::tests` (clear keys only, nothing readable in the map, group and call content, the 4 KB fit, char
  boundaries, preview text, no FCM-reserved key, the app fixture), `store` (push keys follow their token, v9 -> v10
  keeps tokens), `tests/api.rs` (keyed registration, a bad key refused), FFI fixture `wa.register_push.keyed`. Kotlin:
  `:platform` `SealedPushTest` (opens `sealed-push-fixture.json`, a push the Rust gateway sealed to a test-only key
  pair in test resources; tampering, other keys and versions do not open; `OpenedPush`), `WaLockedPushTest` (sealed
  locked call named and ringing over the lock screen through the locked-call page, a device contact for an unsealed
  push, a push that does not open, the title rules), `:messages` `LockedWhatsAppPreviewTest`, `LockedWhatsAppNoticeTest`,
  `NotificationSettingsViewModelTest`, `E612ShotTest`; core-bridge contract for the keyed fixture.
- Deviations: the preview choice lives in its own preferences, not in `messages-prefs.json` (no Rust prefs change,
  readable by a cold process; "Reset settings" does not reset it). The push content is not inserted into the RAM store
  (the socket brings the message once the vault is open, which is what 14 already pays for). Messages also receives
  call pushes as before; it has no locked-call page (Phone only).
- Not verified on a device: everything in `wa-vault-device-tests.md` 8e and 8f, in particular Keystore ECDH on the
  owner's phones, `requestDismissKeyguard` plus the vault prompt order on HyperOS, the measured answer time, and that
  the full-screen intent opens the locked-call page on HyperOS.

Round 7c, the vault stays unlocked (`rosty_core::vault` `clock`/`stay`, `rosty_app_core::wa_vault`, `rosty_ffi`
`vault.unlock_stay`/`stay_store`/`stay_forget`/`stay_prepare`/`touch`; `:core-bridge`, `:platform`, `:wa`, `:shell`,
the four app manifests and boot receivers). Owner decision (2026-11-18), overriding the lock timings of section 6,
13.2 and rounds 4 to 7a:
- The vault stays unlocked all the time and locks only after 90 days with no use, or by "Lock now". Removed: the
  auto-lock delay (setting and timer), the screen-off lock, the reboot rule (boot receivers and the boot count
  check), the 72 h idle rule, the 30-day cap. Kept: "Lock now" (all four apps), FLAG_SECURE, encryption at rest (every
  `RWV*` blob and the history file stay sealed under DK), the biometric unlock, the hand-over.
- Reboot: **silent**, as designed. Every unlock (passphrase, biometric, hand-over, and the open session after a
  passphrase change) also wraps DK under a random stay key SK: `RWVS | ver | created ms i64 | nonce | DK+tag`
  (`vault::seal_stay`), stored in `wa-vault-stay.json` {version, alias, sealed, stay_key, created_ms} after Kotlin
  seals it with K_stay (`rosty.wa.s.<id>`: AES-256-GCM, non-exportable, StrongBox when present else TEE, no user
  authentication, `setUnlockedDeviceRequired(true)`; minSdk 34 >= 28, so no fallback). At each activity start of an
  app with a vault (`VaultUseLifecycle`, a file check first so no core starts without one) and on the locked page,
  `WaVault.unlockSilently` opens it with no prompt (`vault.unlock_stay`). The Keystore refuses K_stay while the phone
  is locked, also before the first unlock after a boot, so a seized locked phone cannot open it. No single biometric
  prompt is needed after a reboot; the biometric prompt is left for after "Lock now".
- Use clock (`vault::UseClock`, pure, tested): the last use is the later of the use record and the authenticated
  stamp of the wrap being opened (stay creation time, daily stamp time). Refused (`expired`, reason `idle` or
  `clock_back`) after 90 days (`MAX_IDLE_MS`) or with the clock more than 5 min (`CLOCK_SKEW_MS`) behind the last
  use; Rust then deletes the stay and daily files and the use record (`wa_vault::expire`), Kotlin their keys, and
  the passphrase is needed ("WhatsApp wasn't used for 90 days"). The use record is `RWVU | ver | last use ms i64 |
  nonce | tag` under DK in `wa-vault-use.json` {version, sealed, used_ms} (plain copy for `vault.state`; the sealed one
  decides at every unlock). It is written at every unlock and by `vault.touch` at each activity start while open, at
  most once an hour (`UseClock::record_due`, `USE_WRITE_INTERVAL_MS`); a touch in a running process past 90 days
  locks. A missing, damaged, foreign or rolled-back record only moves the clock back to the stamp (stricter); a
  forged plain copy is ignored by unlocks.
- Lock: "Lock now" (here or a sibling's) runs the teardown, `vault.lock`, then `vault.stay_forget` and deletes
  K_stay. The biometric unlock (or a hand-over, or the passphrase) opens it again and seals a new stay wrap. The
  lock-all broadcast now goes to a manifest receiver in each app (`WaLockAllReceiver`, exported behind `WA_LINK`):
  a running app locks; an app that is not running deletes its stay file and K_stay keys with no core
  (`WaLockAll.forgetStay`). Before, a runtime receiver let a cold sibling keep its unlock; with silent reopening
  that would have undone "Lock now".
- Dead wrap: K_stay that is invalidated (`KeyPermanentlyInvalidatedException`), refused on an unlocked phone
  (`UserNotAuthenticatedException`), gone, or that opens a blob Rust refuses: the wrap is deleted and the biometric,
  a sibling or the passphrase takes over. A phone that is locked (`KeyguardManager.isDeviceLocked`, or the key says
  so) keeps the wrap (`VaultCrypto.DeviceLocked`) and the unlock page shows; the next activity start tries again.
  No stay wrap is made or used without a secure screen lock.
- Sibling hand-over: unchanged for apps with no stay wrap yet (a vault of 7b until its first unlock in 7c, or after
  "Lock now"); after a hand-over the app seals its own stay wrap, so a reboot needs no sibling hop.
- Locked call (7b): `unlockInline` tries the stay wrap first, then the sibling, then the biometric prompt. With the
  vault normally open the call passes straight from the keyguard; after "Lock now" the 7b order holds.
- Removed prompts and settings: the "Auto-Lock" row and its choices (the `auto_lock_ms` preference is deleted at the
  next start), the screen-off receiver, the `BOOT_COMPLETED` daily drop (`VaultBoot`, Messages still clears its
  content-free count at boot), the passphrase reasons "phone restarted" and "30 days" (and their strings). The
  "quick unlock after a hand-over" prompt asked for in the task never existed (7a open question 2 left it unbuilt);
  "Turn On Quick Unlock" after a passphrase unlock stays (it is the biometric unlock used after "Lock now").
- Compatibility: a 7b `wa-vault.json` is unchanged and opens. A 7b `wa-vault-daily.json` opens (its boot count is
  ignored; its `seen` and `last_unlock_ms` are read and ignored, dropped at the next write); its stamp time starts
  the use clock, so a 7b daily unlock at most 30 days old is well within 90 days. A 7b vault has no stay wrap: its
  first unlock in 7c is the biometric prompt (or a hand-over, or the passphrase), then it is silent. `vault.state`,
  `vault.unlock`, `vault.unlock_daily`, `vault.unlock_link` still accept `boot_count`/`biometric` and ignore them;
  `vault.state`'s `daily_dropped` became `expired`, `vault.unlock_link` answers `{handle, stay_seal}`,
  `vault.unlock`'s `daily_seal` is optional and `daily_expired` is now `expired`. A commit (setup, passphrase change)
  deletes the stay, daily and use files (they hold the old DK); the open session then prepares a new stay wrap
  (`vault.stay_prepare`). `vault.reset` and an unlink delete them all. No DB or gateway change.
- Fixed on the way: `AndroidVaultKeystore.aliases()` listed every `rosty.wa.*` alias, so `WaVault`'s key tidy-up
  deleted the 7b push key (`rosty.wa.push.*`) at each refresh and sealed pushes stopped opening; it now lists only
  the vault's own kinds.
- Trade-offs (accepted by the owner's decision): anyone who knows or watches the device PIN (or passes the phone's
  biometric) gets WhatsApp, as for any app on the unlocked phone; the passphrase no longer protects a running or
  rebooted phone once its screen lock is passed. Data stays readable (decrypted in RAM, the gateway reachable) the
  whole time the vault is open, also with the screen off. Removing the screen lock invalidates K_hw and K_daily (the
  vault is broken, reset and link again); K_stay is never used without one. `setUnlockedDeviceRequired` exists on
  every supported device (minSdk 34). The 90-day clock is the wall clock: setting it back by more than 5 min before
  the last recorded use is refused, but a clock set back less than that, or set back on a phone that sat unused (the
  last recorded use is old), can stretch the 90 days; whoever can do that already passes the screen lock, so the
  90 days are hygiene against a forgotten phone, not a barrier against its owner's PIN.
- Tests: Rust `rosty_core::vault` (90 days, any use moves it with no cap, clock back, a record older than the stamp,
  the hourly throttle, the use record and the stay wrap under their keys only, magics), `rosty_app_core::wa_vault`
  (a 7b daily file with its old clock fields, the stay file owner-only with SK hidden, the use record under DK only,
  the plain clock and `expire`, `remove_all`), `rosty_ffi` unit tests (the life cycle with the stay wrap and "Lock
  now", 90 days and the touch throttle, the sealed clock against a forged plain copy, a damaged stay blob, a
  passphrase change and `stay_prepare`) and fixtures (`vault.unlock_stay` 6, `vault.stay_store` 3, `stay_forget`,
  `stay_prepare` 2, `vault.touch` 4, `vault.state` 7, `vault.unlock_daily` 7; the seeded fixture vault now carries a
  stay wrap). Kotlin: `:core-bridge` `FakeVaultOpsTest` and the contracts, `:platform` `VaultKeystoreTest` (K_stay
  spec, aliases, the push alias not listed, the fake's no-prompt key), `:wa` `WaVaultTest` (silent reopen after a
  restart or reboot, 90 days, no cap, a running process past 90 days, "Lock now" then the biometric, a locked phone, a
  dead K_stay, no screen lock, `unlockInline` silent first, the hand-over seals its own stay wrap, a change keeps it
  silent), `WaLockAllTest`, `VaultShotTest` goldens whose copy changed, `:shell` `VaultUseLifecycleTest`, the four
  manifest tests (`WaLockAllReceiver`).
- Not verified on a device: everything in `wa-vault-device-tests.md` 4, 5 and 8g, in particular K_stay's behaviour
  before the first unlock after a boot and while locked on HyperOS (the exception the Keystore throws), the manifest
  lock-all receiver reaching a stopped app, and the hourly use write.
