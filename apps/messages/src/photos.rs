//! Address-book photos for the inbox and the conversation headers.
//!
//! A photo is read only for avatars on screen: Kotlin writes the thumbnail
//! as a PNG file (`rovsti_android::providers::contact_photo`), the background
//! executor decodes it (`rovsti_wa_ui::decode_image`), and the result is kept
//! in a [`CAPACITY`]-entry LRU keyed by the photo URI. The same scheme as the
//! Contacts and Phone apps; WhatsApp pictures come from `rovsti_wa_ui::WaAvatars`.

use std::{collections::HashSet, sync::Arc, time::Instant};

use gpui::{Context, RenderImage, SharedString};
use rovsti_core::lru::Lru;
use rovsti_wa_ui::avatars::{FADE, fade_in};

/// Longest side of a decoded thumbnail.
const SIZE: u32 = 128;
/// Decoded photos kept in memory.
const CAPACITY: usize = 64;
/// Reads running at once.
const MAX_PARALLEL: usize = 3;

struct Entry {
    image: Arc<RenderImage>,
    since: Instant,
}

pub struct ContactPhotos {
    cache: Lru<SharedString, Entry>,
    pending: HashSet<SharedString>,
    /// Photos that could not be read; tried again after the contacts change.
    missing: HashSet<SharedString>,
    last_arrival: Option<Instant>,
}

impl Default for ContactPhotos {
    fn default() -> Self {
        Self::new()
    }
}

impl ContactPhotos {
    pub fn new() -> Self {
        Self {
            cache: Lru::new(CAPACITY),
            pending: HashSet::new(),
            missing: HashSet::new(),
            last_arrival: None,
        }
    }

    /// The photo behind `uri` and its opacity while it fades in.
    pub fn photo(&self, uri: &str) -> Option<(Arc<RenderImage>, f32)> {
        self.cache
            .peek(&SharedString::from(uri.to_owned()))
            .map(|entry| (Arc::clone(&entry.image), fade_in(entry.since)))
    }

    pub fn fading(&self) -> bool {
        self.last_arrival.is_some_and(|at| at.elapsed() < FADE)
    }

    /// The address book changed: photos that failed may exist now.
    pub fn forget_misses(&mut self) {
        self.missing.clear();
    }

    /// Marks the photo as on screen and reads it when it is not held yet.
    pub fn want(&mut self, uri: &SharedString, cx: &mut Context<Self>) {
        if self.cache.get(uri).is_some()
            || self.pending.contains(uri)
            || self.missing.contains(uri)
            || self.pending.len() >= MAX_PARALLEL
        {
            return;
        }
        self.pending.insert(uri.clone());
        let key = uri.clone();
        let path_uri = uri.to_string();
        let task = cx
            .background_executor()
            .spawn(async move { read_photo(&path_uri) });
        cx.spawn(async move |this, cx| {
            let image = task.await;
            this.update(cx, |this, cx| {
                this.pending.remove(&key);
                match image {
                    Some(image) => {
                        let now = Instant::now();
                        this.cache.insert(key, Entry { image, since: now });
                        this.last_arrival = Some(now);
                    }
                    None => {
                        this.missing.insert(key);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

/// Blocking: asks Kotlin for the thumbnail file and decodes it.
fn read_photo(uri: &str) -> Option<Arc<RenderImage>> {
    match rovsti_android::providers::contact_photo(uri, SIZE) {
        Ok(path) if path.is_empty() => None,
        Ok(path) => {
            let bytes = std::fs::read(&path).ok()?;
            rovsti_wa_ui::decode_image(&bytes, SIZE)
        }
        Err(rovsti_android::Error::Unsupported) => None,
        Err(error) => {
            log::debug!("a contact photo failed: {error}");
            None
        }
    }
}
