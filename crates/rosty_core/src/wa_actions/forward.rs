//! Forwarding a WhatsApp message to another WhatsApp chat.

/// From this forwarding score on WhatsApp shows "Forwarded many times" (WA Web's threshold; whatsapp-rust 0.7.0
/// `prepare_for_forward` jumps the score to 127 there).
pub const FREQUENTLY_FORWARDED_SCORE: u32 = 5;

/// Whether a message with this forwarding score shows "Forwarded many times" instead of "Forwarded".
#[must_use]
pub fn forwarded_many(score: Option<u32>) -> bool {
    score.is_some_and(|s| s >= FREQUENTLY_FORWARDED_SCORE)
}

/// What decides whether a message can be forwarded.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ForwardSource {
    /// It has the gateway's id (not a row still in the outbox).
    pub sent: bool,
    pub deleted: bool,
    pub view_once: bool,
    /// A placeholder (the content never reached this device) or a system line.
    pub placeholder: bool,
    /// Media whose file the gateway can relay (a text always can).
    pub media_relayable: bool,
    pub is_media: bool,
}

/// Why a message cannot be forwarded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ForwardRefusal {
    NotSent,
    Deleted,
    ViewOnce,
    Placeholder,
    MediaMissing,
}

/// `Ok` when the message can be forwarded.
pub fn can_forward(source: &ForwardSource) -> Result<(), ForwardRefusal> {
    if !source.sent {
        return Err(ForwardRefusal::NotSent);
    }
    if source.deleted {
        return Err(ForwardRefusal::Deleted);
    }
    if source.view_once {
        return Err(ForwardRefusal::ViewOnce);
    }
    if source.placeholder {
        return Err(ForwardRefusal::Placeholder);
    }
    if source.is_media && !source.media_relayable {
        return Err(ForwardRefusal::MediaMissing);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn many_times_from_five() {
        assert!(!forwarded_many(None));
        assert!(!forwarded_many(Some(4)));
        assert!(forwarded_many(Some(5)));
        assert!(forwarded_many(Some(127)));
    }

    #[test]
    fn refusals() {
        let ok = ForwardSource {
            sent: true,
            ..ForwardSource::default()
        };
        assert_eq!(can_forward(&ok), Ok(()));
        assert_eq!(
            can_forward(&ForwardSource::default()),
            Err(ForwardRefusal::NotSent)
        );
        assert_eq!(
            can_forward(&ForwardSource {
                deleted: true,
                ..ok
            }),
            Err(ForwardRefusal::Deleted)
        );
        assert_eq!(
            can_forward(&ForwardSource {
                view_once: true,
                ..ok
            }),
            Err(ForwardRefusal::ViewOnce)
        );
        assert_eq!(
            can_forward(&ForwardSource {
                placeholder: true,
                ..ok
            }),
            Err(ForwardRefusal::Placeholder)
        );
        assert_eq!(
            can_forward(&ForwardSource {
                is_media: true,
                ..ok
            }),
            Err(ForwardRefusal::MediaMissing)
        );
        assert_eq!(
            can_forward(&ForwardSource {
                is_media: true,
                media_relayable: true,
                ..ok
            }),
            Ok(())
        );
    }
}
