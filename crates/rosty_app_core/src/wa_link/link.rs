//! The link an app keeps to reach its WhatsApp instance, and its file.

use std::{fmt, io, path::Path};

use serde::{Deserialize, Serialize};

use crate::storage;

/// Where the link is kept, in the app's own data directory.
pub const LINK_FILE: &str = "whatsapp-link.json";

/// What an app keeps to reach its instance. The key is a bearer secret.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredLink {
    pub gateway_url: String,
    pub instance_id: String,
    pub key: String,
}

impl fmt::Debug for StoredLink {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StoredLink")
            .field("gateway_url", &self.gateway_url)
            .field("instance_id", &self.instance_id)
            .field("key", &"rwk_…")
            .finish()
    }
}

impl StoredLink {
    /// The link saved in `dir`; `None` when there is none or it no longer parses.
    pub fn load(dir: &Path) -> Option<Self> {
        storage::load_json(dir, LINK_FILE)
    }

    /// Saves the link atomically in `dir`.
    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, LINK_FILE, self)
    }

    /// Removes the link file; a missing file is fine.
    pub fn remove(dir: &Path) -> io::Result<()> {
        match std::fs::remove_file(dir.join(LINK_FILE)) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_hides_the_key() {
        let link = StoredLink {
            gateway_url: "https://g".into(),
            instance_id: "i".into(),
            key: "rwk_secret".into(),
        };
        assert!(!format!("{link:?}").contains("secret"));
    }
}
