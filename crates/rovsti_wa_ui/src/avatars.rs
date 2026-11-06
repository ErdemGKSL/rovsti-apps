//! WhatsApp profile pictures and names for every Rovsti app.
//!
//! [`WaAvatars`] is one GPUI entity per app. It follows the app's
//! [`WhatsAppSettings`] (gateway, account, data folder) by itself; the app
//! only forwards gateway events ([`WaAvatars::on_event`]) and asks for the
//! avatars of the rows it draws ([`WaAvatars::want`]). The policy (what to
//! fetch when, 429 backoff, disk names) is the pure [`crate::avatar_book`].
//!
//! - Memory: at most [`CAPACITY`] decoded pictures of at most [`MAX_PX`] px,
//!   so about `CAPACITY * MAX_PX² * 4` bytes, least recently drawn first out.
//! - Disk: the JPEGs under `<data dir>/wa-pictures`, at most [`DISK_BYTES`].
//! - Network: only for avatars on screen, [`MAX_PARALLEL`] at a time, on the
//!   background executor, with a doubling wait after a 429.
//! - A `profile` or `chat_meta` event with a new picture id refetches.

use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use gpui::{
    Context, Div, Entity, Hsla, ObjectFit, ParentElement, RenderImage, SharedString, Styled,
    StyledImage, Task, px,
};
use rovsti_ui::{Icon, components::avatar, tokens::Theme};
use rovsti_wa_client::{ClientError, Gateway, Picture};
use rovsti_wa_proto::{ErrorCode, GatewayEvent, MAX_PROFILES, WaProfile};

use crate::{
    WhatsAppSettings,
    avatar_book::{self, AvatarBook, Found, Job},
};

/// Decoded pictures kept in memory.
pub const CAPACITY: usize = 96;
/// Longest side of a decoded picture.
pub const MAX_PX: u32 = 128;
/// Fetches running at once.
pub const MAX_PARALLEL: usize = 3;
/// Bytes of pictures kept on disk.
pub const DISK_BYTES: u64 = 8 * 1024 * 1024;
/// A picture fades in over the monogram this long.
pub const FADE: Duration = Duration::from_millis(220);
/// Folder under the app's data folder.
pub const DISK_DIR: &str = "wa-pictures";
/// Disk writes between two prunes.
const PRUNE_EVERY: u32 = 32;
/// Profile lookups wait this long to be batched.
const PROFILE_BATCH: Duration = Duration::from_millis(40);

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Decodes a JPEG, PNG or WebP into the BGRA image gpui draws, scaled down to
/// at most `max_px` on its longest side. Blocking: run it off the UI thread.
pub fn decode_image(bytes: &[u8], max_px: u32) -> Option<Arc<RenderImage>> {
    let decoded = image::load_from_memory(bytes)
        .map_err(|error| log::debug!("decoding a picture failed: {error}"))
        .ok()?;
    let decoded = if decoded.width() > max_px || decoded.height() > max_px {
        decoded.thumbnail(max_px, max_px)
    } else {
        decoded
    };
    let mut pixels = decoded.into_rgba8();
    for pixel in pixels.as_chunks_mut::<4>().0 {
        pixel.swap(0, 2);
    }
    Some(Arc::new(RenderImage::new(vec![image::Frame::new(pixels)])))
}

/// The opacity of a picture that arrived at `since` (0 to 1 over [`FADE`]).
pub fn fade_in(since: Instant) -> f32 {
    let t = (since.elapsed().as_secs_f32() / FADE.as_secs_f32()).clamp(0.0, 1.0);
    1.0 - (1.0 - t) * (1.0 - t)
}

/// An avatar: the monogram (or a person glyph when it is empty) on its tint,
/// with `photo` faded in over it at the given opacity.
pub fn photo_avatar(
    theme: Theme,
    monogram: impl Into<SharedString>,
    size: f32,
    tint: Hsla,
    photo: Option<(Arc<RenderImage>, f32)>,
) -> Div {
    let monogram = monogram.into();
    let base = if monogram.is_empty() {
        avatar(theme, "", size, tint).child(
            gpui::svg()
                .path(Icon::LucideUser.path())
                .size(px(size * 0.5))
                .text_color(gpui::white()),
        )
    } else {
        avatar(theme, monogram, size, tint)
    };
    base.relative().children(photo.map(|(image, opacity)| {
        gpui::img(image)
            .absolute()
            .top_0()
            .left_0()
            .size(px(size))
            .rounded_full()
            .object_fit(ObjectFit::Cover)
            .opacity(opacity)
    }))
}

/// WhatsApp profile pictures and profiles, cached for the whole app.
pub struct WaAvatars {
    book: AvatarBook<Arc<RenderImage>>,
    gateway: Option<Gateway>,
    /// The linked instance, to drop everything when it changes.
    account: Option<String>,
    dir: Option<PathBuf>,
    writes: u32,
    last_arrival: Option<Instant>,
    /// A repaint is scheduled for when the backoff ends.
    wake_at: Option<i64>,
    profiles: HashMap<String, WaProfile>,
    profile_queue: Vec<String>,
    profile_asked: HashSet<String>,
    profile_task: Option<Task<()>>,
    _settings: gpui::Subscription,
}

impl WaAvatars {
    /// Follows `settings` for the gateway, the account and the data folder.
    pub fn new(settings: &Entity<WhatsAppSettings>, cx: &mut Context<Self>) -> Self {
        let subscription = cx.observe(settings, |this, settings, cx| {
            let (gateway, account, dir) = {
                let s = settings.read(cx);
                (
                    s.gateway(),
                    s.link().map(|l| l.instance_id.clone()),
                    s.data_dir().map(Path::to_path_buf),
                )
            };
            this.follow(gateway, account, dir, cx);
        });
        let mut this = Self {
            book: AvatarBook::new(CAPACITY, MAX_PARALLEL),
            gateway: None,
            account: None,
            dir: None,
            writes: 0,
            last_arrival: None,
            wake_at: None,
            profiles: HashMap::new(),
            profile_queue: Vec::new(),
            profile_asked: HashSet::new(),
            profile_task: None,
            _settings: subscription,
        };
        let (gateway, account, dir) = {
            let s = settings.read(cx);
            (
                s.gateway(),
                s.link().map(|l| l.instance_id.clone()),
                s.data_dir().map(Path::to_path_buf),
            )
        };
        this.follow(gateway, account, dir, cx);
        this
    }

    fn follow(
        &mut self,
        gateway: Option<Gateway>,
        account: Option<String>,
        dir: Option<PathBuf>,
        cx: &mut Context<Self>,
    ) {
        if account != self.account {
            self.account = account;
            for image in self.book.clear() {
                cx.drop_image(image, None);
            }
            self.profiles.clear();
            self.profile_asked.clear();
            cx.notify();
        }
        let had_gateway = self.gateway.is_some();
        self.gateway = gateway;
        if !had_gateway && self.gateway.is_some() {
            // Misses recorded while offline are not real answers.
            self.book.forget_misses();
            cx.notify();
        }
        let dir = dir.map(|d| d.join(DISK_DIR));
        if dir.is_some() && self.dir != dir {
            self.dir = dir.clone();
            if let Some(dir) = dir {
                cx.background_executor()
                    .spawn(async move { avatar_book::disk_prune(&dir, DISK_BYTES) })
                    .detach();
            }
            cx.notify();
        }
    }

    /// The picture of `jid` and its opacity while it fades in, if one is
    /// held. Cheap: clones an `Arc`.
    pub fn picture(&self, jid: &str) -> Option<(Arc<RenderImage>, f32)> {
        self.book
            .image(jid)
            .map(|(image, since)| (Arc::clone(image), fade_in(since)))
    }

    /// A picture is still fading in: keep animation frames coming.
    pub fn fading(&self) -> bool {
        self.last_arrival.is_some_and(|at| at.elapsed() < FADE)
    }

    /// Call for each avatar about to be drawn. `picture_id` is the id the
    /// caller knows (`ChatMeta::picture_id`, `WaProfile::picture_id`), `None`
    /// when it knows none. Starts a fetch when one is due; cheap otherwise.
    pub fn want(&mut self, jid: &str, picture_id: Option<&str>, cx: &mut Context<Self>) {
        if jid.is_empty() || (self.gateway.is_none() && self.dir.is_none()) {
            return;
        }
        let now = now_ms();
        let Some(job) = self.book.request(jid, picture_id, now) else {
            self.wake_after_backoff(now, cx);
            return;
        };
        let gateway = self.gateway.clone();
        let dir = self.dir.clone();
        let work = job.clone();
        let task = cx
            .background_executor()
            .spawn(async move { fetch(gateway.as_ref(), dir.as_deref(), &work) });
        cx.spawn(async move |this, cx| {
            let (found, rate_limited, wrote) = task.await;
            this.update(cx, |this, cx| {
                let now = now_ms();
                if matches!(found, Found::Image { .. }) {
                    this.last_arrival = Some(Instant::now());
                }
                for image in this.book.arrived(&job, found, now) {
                    cx.drop_image(image, None);
                }
                if rate_limited {
                    this.book.rate_limited(now);
                }
                if wrote {
                    this.wrote(cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Several avatars at once (the rows on screen).
    pub fn want_all<'a>(
        &mut self,
        wanted: impl IntoIterator<Item = (&'a str, Option<&'a str>)>,
        cx: &mut Context<Self>,
    ) {
        for (jid, picture_id) in wanted {
            self.want(jid, picture_id, cx);
        }
    }

    /// What WhatsApp tells about `jid`, once known (from a `profile` event or
    /// an earlier [`Self::want_profile`]).
    pub fn profile(&self, jid: &str) -> Option<&WaProfile> {
        self.profiles.get(jid)
    }

    /// WhatsApp's name for `jid`, once known.
    pub fn name(&self, jid: &str) -> Option<&str> {
        self.profiles.get(jid).and_then(WaProfile::display_name)
    }

    /// Asks the gateway for the profile of `jid` (batched with the other
    /// requests of this frame, once per session).
    pub fn want_profile(&mut self, jid: &str, cx: &mut Context<Self>) {
        if self.gateway.is_none()
            || jid.is_empty()
            || self.profiles.contains_key(jid)
            || !self.profile_asked.insert(jid.to_owned())
        {
            return;
        }
        self.profile_queue.push(jid.to_owned());
        self.flush_profiles(cx);
    }

    fn flush_profiles(&mut self, cx: &mut Context<Self>) {
        if self.profile_task.is_some() || self.profile_queue.is_empty() {
            return;
        }
        let backoff = self.book.backoff();
        let wait = if backoff.active(now_ms()) {
            Duration::from_millis(u64::try_from(backoff.until_ms() - now_ms()).unwrap_or(0))
        } else {
            PROFILE_BATCH
        };
        self.profile_task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(wait).await;
            let Ok(Some((gateway, batch))) = this.update(cx, |this, _| {
                let gateway = this.gateway.clone()?;
                let take = this.profile_queue.len().min(MAX_PROFILES);
                Some((
                    gateway,
                    this.profile_queue.drain(..take).collect::<Vec<_>>(),
                ))
            }) else {
                this.update(cx, |this, _| this.profile_task = None).ok();
                return;
            };
            let asked = batch.clone();
            let result = cx
                .background_executor()
                .spawn(async move { gateway.profiles(&asked) })
                .await;
            this.update(cx, |this, cx| {
                this.profile_task = None;
                match result {
                    Ok(profiles) => {
                        for profile in profiles {
                            this.learn(profile);
                        }
                        cx.notify();
                    }
                    Err(error) => {
                        if is_rate_limited(&error) {
                            this.book.rate_limited(now_ms());
                        } else {
                            log::debug!("WhatsApp profiles failed: {error}");
                        }
                        // Ask again on the next request.
                        for jid in batch {
                            this.profile_asked.remove(&jid);
                        }
                    }
                }
                this.flush_profiles(cx);
            })
            .ok();
        }));
    }

    fn learn(&mut self, profile: WaProfile) {
        // A profile the gateway never asked WhatsApp about knows no picture
        // id; only a real answer (or an id) says what is current.
        if profile.picture_id.is_some() || profile.fetched_ms > 0 {
            self.book
                .on_picture_id(&profile.jid, profile.picture_id.as_deref());
        }
        self.profiles.insert(profile.jid.clone(), profile);
    }

    /// Forward every gateway event: `profile` and `chat_meta` refresh
    /// pictures and names, `resync` forgets misses.
    pub fn on_event(&mut self, event: &GatewayEvent, cx: &mut Context<Self>) {
        match event {
            GatewayEvent::Profile(profile) => {
                self.learn(profile.clone());
                cx.notify();
            }
            GatewayEvent::ChatMeta { jid, meta } => {
                if self.book.held_id(jid) != meta.picture_id.as_deref() {
                    self.book.on_picture_id(jid, meta.picture_id.as_deref());
                    cx.notify();
                }
            }
            GatewayEvent::Resync => {
                self.book.forget_misses();
                cx.notify();
            }
            _ => {}
        }
    }

    fn wrote(&mut self, cx: &mut Context<Self>) {
        self.writes += 1;
        if self.writes.is_multiple_of(PRUNE_EVERY)
            && let Some(dir) = self.dir.clone()
        {
            cx.background_executor()
                .spawn(async move { avatar_book::disk_prune(&dir, DISK_BYTES) })
                .detach();
        }
    }

    /// While the backoff holds, repaint once it ends so the rows ask again.
    fn wake_after_backoff(&mut self, now: i64, cx: &mut Context<Self>) {
        let backoff = self.book.backoff();
        if !backoff.active(now) || self.wake_at == Some(backoff.until_ms()) {
            return;
        }
        let until = backoff.until_ms();
        self.wake_at = Some(until);
        let wait = Duration::from_millis(u64::try_from(until - now).unwrap_or(0) + 50);
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(wait).await;
            this.update(cx, |this, cx| {
                this.wake_at = None;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

fn is_rate_limited(error: &ClientError) -> bool {
    matches!(error, ClientError::Api(api) if api.code == ErrorCode::RateLimited)
}

/// One job on the background executor: disk first, then the gateway.
/// Returns what was found, whether a 429 came back, and whether a file was
/// written.
fn fetch(
    gateway: Option<&Gateway>,
    dir: Option<&Path>,
    job: &Job,
) -> (Found<Arc<RenderImage>>, bool, bool) {
    // The picture on disk, unless memory already holds one to revalidate.
    let mut from_disk = None;
    if job.held.is_none()
        && let Some(dir) = dir
        && let Some((id, path)) = avatar_book::disk_find(dir, &job.jid, job.wanted.as_deref())
        && let Some(image) = std::fs::read(&path)
            .ok()
            .and_then(|bytes| decode_image(&bytes, MAX_PX))
    {
        if job.wanted.as_deref() == Some(id.as_str()) {
            // The caller named this very picture: no need to ask.
            return (
                Found::Image {
                    id,
                    image,
                    verified: true,
                },
                false,
                false,
            );
        }
        from_disk = Some((id, image));
    }
    let known = job
        .held
        .clone()
        .or_else(|| from_disk.as_ref().map(|(id, _)| id.clone()));
    let unverified = |from_disk: Option<(String, Arc<RenderImage>)>, fallback| {
        from_disk.map_or(fallback, |(id, image)| Found::Image {
            id,
            image,
            verified: false,
        })
    };
    let Some(gateway) = gateway else {
        return (unverified(from_disk, Found::Failed), false, false);
    };
    match gateway.picture(&job.jid, false, known.as_deref()) {
        Ok(Picture::Image { id, bytes }) => {
            let wrote = dir.is_some_and(|dir| {
                avatar_book::disk_store(dir, &job.jid, &id, &bytes)
                    .map_err(|error| log::debug!("storing a WhatsApp picture failed: {error}"))
                    .is_ok()
            });
            match decode_image(&bytes, MAX_PX) {
                Some(image) => (
                    Found::Image {
                        id,
                        image,
                        verified: true,
                    },
                    false,
                    wrote,
                ),
                None => (Found::Failed, false, wrote),
            }
        }
        Ok(Picture::Unchanged) => match from_disk {
            Some((id, image)) => (
                Found::Image {
                    id,
                    image,
                    verified: true,
                },
                false,
                false,
            ),
            None => (Found::Unchanged, false, false),
        },
        Ok(Picture::None) => {
            if let Some(dir) = dir {
                avatar_book::disk_remove(dir, &job.jid, None);
            }
            (Found::None, false, false)
        }
        Err(error) if is_rate_limited(&error) => {
            (unverified(from_disk, Found::RateLimited), true, false)
        }
        Err(error) => {
            log::debug!("a WhatsApp picture failed: {error}");
            (unverified(from_disk, Found::Failed), false, false)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decoding_scales_down_and_swaps_channels() {
        let mut pixels = image::RgbaImage::new(300, 150);
        for p in pixels.pixels_mut() {
            *p = image::Rgba([10, 20, 30, 255]);
        }
        let mut png = Vec::new();
        image::DynamicImage::ImageRgba8(pixels)
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        let image = decode_image(&png, MAX_PX).unwrap();
        let size = image.size(0);
        assert_eq!((size.width.0, size.height.0), (128, 64));
        assert_eq!(
            image.as_bytes(0).map(|b| &b[..4]),
            Some(&[30, 20, 10, 255][..])
        );
        assert!(decode_image(b"not an image", MAX_PX).is_none());
    }

    #[test]
    fn fades_run_from_zero_to_one() {
        assert!(fade_in(Instant::now()) < 0.5);
        let past = Instant::now()
            .checked_sub(FADE * 2)
            .unwrap_or_else(Instant::now);
        assert!((fade_in(past) - 1.0).abs() < f32::EPSILON || past.elapsed() < FADE);
    }
}
