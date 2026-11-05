//! Hand-off to a sibling Rovsti app that owns WhatsApp calling or chatting.
//! Kotlin side: `Handoff`.
//!
//! The target is found by intent action and accepted only when it is signed
//! with the same key as this app. The receiving app gets a one-shot `handoff`
//! event (`{action, jid, name}`) once its UI is attached. On desktop these
//! return [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// The intent action Kotlin uses to ask an app to start a WhatsApp call.
pub const ACTION_WA_CALL: &str = "dev.rovsti.action.WA_CALL";
/// The intent action Kotlin uses to ask an app to open a WhatsApp chat.
pub const ACTION_WA_CHAT: &str = "dev.rovsti.action.WA_CHAT";

/// Asks a sibling app to start a WhatsApp call to `jid` (`name` is shown while
/// it connects). `false` when no signature-matching app handles it.
pub fn wa_call(jid: &str, name: &str) -> Result<bool> {
    start(ACTION_WA_CALL, jid, name)
}

/// Asks a sibling app to open the WhatsApp chat with `jid`. `false` when no
/// signature-matching app handles it.
pub fn wa_chat(jid: &str, name: &str) -> Result<bool> {
    start(ACTION_WA_CHAT, jid, name)
}

fn start(action: &str, jid: &str, name: &str) -> Result<bool> {
    bridge::call_bool(
        "handoff",
        &[Arg::Str(action), Arg::Str(jid), Arg::Str(name)],
    )
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(
            wa_call("1@s.whatsapp.net", "Ayse"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            wa_chat("1@s.whatsapp.net", "Ayse"),
            Err(Error::Unsupported)
        ));
    }
}
