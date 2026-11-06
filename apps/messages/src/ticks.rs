//! WhatsApp delivery ticks of my messages, drawn like WhatsApp draws them: a
//! clock while sending, one grey tick once the server has it, two grey ticks
//! once delivered, two blue ticks once read, and a blue microphone next to
//! them once a voice note was played.

use gpui::{Hsla, IntoElement, ParentElement, Styled, div, px, rgb};
use rovsti_ui::Icon;
use rovsti_wa_proto::{DeliveryStatus, Item, ItemState, MessageMeta};

/// Blue ticks on my own (accent-filled) bubble: a light sky blue that stands
/// out from the grey-white of the other ticks.
pub const READ_ON_BUBBLE: u32 = 0x9BE8FF;
/// Blue ticks on a plain background (the inbox), WhatsApp's own tick blue.
pub const READ_ON_PLAIN: u32 = 0x34B7F1;

/// What the ticks of one of my messages show.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Tick {
    /// Not confirmed by the gateway yet.
    Pending,
    /// The server has it.
    Sent,
    /// The recipient's phone has it (in a group: every member's).
    Delivered,
    /// The recipient opened it (in a group: every member did).
    Read,
    /// A voice note I sent was played.
    Played,
}

impl Tick {
    /// The ticks of a bubble: `None` for messages of others and failed sends
    /// (they say "Not sent" instead).
    pub fn of_item(item: &Item, meta: Option<&MessageMeta>) -> Option<Self> {
        if !item.message.from_me {
            return None;
        }
        Some(match item.state {
            ItemState::Pending { .. } => Self::Pending,
            ItemState::Failed { .. } => return None,
            ItemState::Sent => Self::Sent,
            ItemState::Delivered => Self::Delivered,
            ItemState::Read if voice_played(meta) => Self::Played,
            ItemState::Read => Self::Read,
        })
    }

    /// The ticks a stored status shows; a message of mine without one was at
    /// least sent.
    pub fn of_status(status: Option<DeliveryStatus>, voice_note: bool) -> Self {
        match status {
            None | Some(DeliveryStatus::Sent) => Self::Sent,
            Some(DeliveryStatus::Delivered) => Self::Delivered,
            Some(DeliveryStatus::Read) => Self::Read,
            Some(DeliveryStatus::Played) if voice_note => Self::Played,
            Some(DeliveryStatus::Played) => Self::Read,
        }
    }

    /// The icons, left to right.
    pub fn icons(self) -> &'static [Icon] {
        match self {
            Self::Pending => &[Icon::LucideClock],
            Self::Sent => &[Icon::LucideCheck],
            Self::Delivered | Self::Read => &[Icon::LucideCheckCheck],
            Self::Played => &[Icon::LucideCheckCheck, Icon::LucideMic],
        }
    }

    /// Blue, not grey.
    pub fn is_read(self) -> bool {
        matches!(self, Self::Read | Self::Played)
    }
}

/// A voice note whose meta says it was played.
fn voice_played(meta: Option<&MessageMeta>) -> bool {
    meta.is_some_and(|m| {
        m.status == Some(DeliveryStatus::Played) && m.media.as_ref().is_some_and(|m| m.voice_note)
    })
}

/// The ticks as icons `size` px tall: `grey` until read, `read` after.
pub fn ticks(tick: Tick, size: f32, grey: Hsla, read: Hsla) -> impl IntoElement {
    let color = if tick.is_read() { read } else { grey };
    div()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(1.0))
        .children(tick.icons().iter().map(move |icon| {
            gpui::svg()
                .path(icon.path())
                .size(px(size))
                .text_color(color)
        }))
}

/// [`READ_ON_BUBBLE`] / [`READ_ON_PLAIN`] as colours.
pub fn read_color(on_bubble: bool) -> Hsla {
    rgb(if on_bubble {
        READ_ON_BUBBLE
    } else {
        READ_ON_PLAIN
    })
    .into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::{MediaMeta, MessageBody, WaMessage};

    fn item(from_me: bool, state: ItemState) -> Item {
        Item {
            message: WaMessage {
                id: "m".into(),
                chat: "c@s.whatsapp.net".into(),
                sender: None,
                from_me,
                timestamp_ms: 1,
                body: MessageBody::Text("x".into()),
                reactions: vec![],
            },
            state,
        }
    }

    fn voice(status: DeliveryStatus) -> MessageMeta {
        MessageMeta {
            status: Some(status),
            media: Some(MediaMeta {
                voice_note: true,
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn each_state_has_its_own_ticks() {
        let mine = |state| Tick::of_item(&item(true, state), None);
        assert_eq!(
            mine(ItemState::Pending { local_id: 1 }),
            Some(Tick::Pending)
        );
        assert_eq!(mine(ItemState::Sent), Some(Tick::Sent));
        assert_eq!(mine(ItemState::Delivered), Some(Tick::Delivered));
        assert_eq!(mine(ItemState::Read), Some(Tick::Read));
        assert_eq!(mine(ItemState::Failed { local_id: 1 }), None);
        // Their messages carry no ticks.
        assert_eq!(Tick::of_item(&item(false, ItemState::Read), None), None);

        let icons: Vec<&[Icon]> = [
            Tick::Pending,
            Tick::Sent,
            Tick::Delivered,
            Tick::Read,
            Tick::Played,
        ]
        .map(Tick::icons)
        .into();
        assert_eq!(
            icons,
            [
                &[Icon::LucideClock][..],
                &[Icon::LucideCheck],
                &[Icon::LucideCheckCheck],
                &[Icon::LucideCheckCheck],
                &[Icon::LucideCheckCheck, Icon::LucideMic],
            ]
        );
        // Only read ticks are blue; delivered ones look like WhatsApp's grey.
        assert!(!Tick::Delivered.is_read());
        assert!(Tick::Read.is_read() && Tick::Played.is_read());
        assert_ne!(read_color(true), read_color(false));
    }

    #[test]
    fn played_needs_a_voice_note_heard() {
        let read = item(true, ItemState::Read);
        assert_eq!(
            Tick::of_item(&read, Some(&voice(DeliveryStatus::Played))),
            Some(Tick::Played)
        );
        assert_eq!(
            Tick::of_item(&read, Some(&voice(DeliveryStatus::Read))),
            Some(Tick::Read)
        );
        // Played on anything but a voice note shows as read.
        let photo = MessageMeta {
            status: Some(DeliveryStatus::Played),
            ..Default::default()
        };
        assert_eq!(Tick::of_item(&read, Some(&photo)), Some(Tick::Read));
    }

    #[test]
    fn stored_statuses_for_previews() {
        use DeliveryStatus::*;
        assert_eq!(Tick::of_status(None, false), Tick::Sent);
        assert_eq!(Tick::of_status(Some(Sent), false), Tick::Sent);
        assert_eq!(Tick::of_status(Some(Delivered), false), Tick::Delivered);
        assert_eq!(Tick::of_status(Some(Read), false), Tick::Read);
        assert_eq!(Tick::of_status(Some(Played), true), Tick::Played);
        assert_eq!(Tick::of_status(Some(Played), false), Tick::Read);
    }
}
