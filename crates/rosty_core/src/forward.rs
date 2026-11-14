//! What a forwarded message becomes on the target channel, and the words the
//! UI shows about it.

/// Longest text an SMS forward may carry; longer text is refused.
pub const MAX_SMS_FORWARD_CHARS: usize = 5000;

/// What is being forwarded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ForwardBody {
    Text(String),
    Picture { caption: Option<String> },
}

/// Channel of the chat the message goes to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ForwardTo {
    Sms,
    WhatsApp,
}

/// What to send.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ForwardPlan {
    Text(String),
    Picture { caption: Option<String> },
    Nothing,
}

/// Plans a forward. Text is trimmed and blank text sends nothing; picture
/// captions are trimmed and a blank one is dropped. Pictures go to both
/// channels (as MMS over SMS). SMS text over [`MAX_SMS_FORWARD_CHARS`] sends
/// nothing. `to` is kept so later per-channel rules need no signature change.
pub fn plan(body: &ForwardBody, to: ForwardTo) -> ForwardPlan {
    match body {
        ForwardBody::Text(text) => {
            let text = text.trim();
            if text.is_empty()
                || (to == ForwardTo::Sms && text.chars().count() > MAX_SMS_FORWARD_CHARS)
            {
                ForwardPlan::Nothing
            } else {
                ForwardPlan::Text(text.to_owned())
            }
        }
        ForwardBody::Picture { caption } => ForwardPlan::Picture {
            caption: caption
                .as_deref()
                .map(str::trim)
                .filter(|c| !c.is_empty())
                .map(str::to_owned),
        },
    }
}

/// "Forwarded to Ada" or "Couldn't forward to Ada".
pub fn notice(title: &str, ok: bool) -> String {
    let verb = if ok { "Forwarded" } else { "Couldn't forward" };
    let title = title.trim();
    if title.is_empty() {
        verb.to_owned()
    } else {
        format!("{verb} to {title}")
    }
}

/// "Ada", or "Ada · WhatsApp" / "Ada · SMS" when the person offers both
/// channels in the chooser.
pub fn target_label(title: &str, to: ForwardTo, both: bool) -> String {
    let title = title.trim();
    if !both {
        return title.to_owned();
    }
    let channel = match to {
        ForwardTo::Sms => "SMS",
        ForwardTo::WhatsApp => "WhatsApp",
    };
    format!("{title} · {channel}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_text_forwards_nothing() {
        for to in [ForwardTo::Sms, ForwardTo::WhatsApp] {
            assert_eq!(
                plan(&ForwardBody::Text("  \n".into()), to),
                ForwardPlan::Nothing
            );
        }
        assert_eq!(
            plan(&ForwardBody::Text("  hi \n".into()), ForwardTo::Sms),
            ForwardPlan::Text("hi".into())
        );
        let long = "a".repeat(MAX_SMS_FORWARD_CHARS + 1);
        assert_eq!(
            plan(&ForwardBody::Text(long.clone()), ForwardTo::Sms),
            ForwardPlan::Nothing
        );
        assert_eq!(
            plan(&ForwardBody::Text(long.clone()), ForwardTo::WhatsApp),
            ForwardPlan::Text(long)
        );
    }

    #[test]
    fn caption_is_trimmed_and_optional() {
        let pic = |c: Option<&str>| ForwardBody::Picture {
            caption: c.map(str::to_owned),
        };
        assert_eq!(
            plan(&pic(Some("  look ")), ForwardTo::WhatsApp),
            ForwardPlan::Picture {
                caption: Some("look".into())
            }
        );
        assert_eq!(
            plan(&pic(Some("   ")), ForwardTo::WhatsApp),
            ForwardPlan::Picture { caption: None }
        );
        assert_eq!(
            plan(&pic(None), ForwardTo::Sms),
            ForwardPlan::Picture { caption: None }
        );
    }

    #[test]
    fn pictures_go_to_both_channels() {
        let body = ForwardBody::Picture {
            caption: Some("x".into()),
        };
        assert_eq!(
            plan(&body, ForwardTo::Sms),
            plan(&body, ForwardTo::WhatsApp)
        );
        assert_ne!(plan(&body, ForwardTo::Sms), ForwardPlan::Nothing);
    }

    #[test]
    fn notice_names_the_chat() {
        assert_eq!(notice("Ada", true), "Forwarded to Ada");
        assert_eq!(notice(" Ada ", false), "Couldn't forward to Ada");
        assert_eq!(notice("  ", true), "Forwarded");
        assert_eq!(notice("", false), "Couldn't forward");
    }

    #[test]
    fn label_names_the_channel_when_both() {
        assert_eq!(target_label("Ada", ForwardTo::WhatsApp, false), "Ada");
        assert_eq!(
            target_label("Ada", ForwardTo::WhatsApp, true),
            "Ada · WhatsApp"
        );
        assert_eq!(target_label("Ada", ForwardTo::Sms, true), "Ada · SMS");
    }
}
