//! Process state set once by `init`.

use crate::envelope::FfiError;
use serde_json::{Value, json};
use std::path::PathBuf;
use std::sync::OnceLock;

/// The directories and identity the app gave to `init`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppDirs {
    pub data_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub app_id: String,
    pub sdk: i32,
}

static DIRS: OnceLock<AppDirs> = OnceLock::new();

/// Stores the dirs (first call) and answers `{"version", "abi"}`. A second call with the same
/// values is a no-op; different values are `invalid`.
pub fn init(data_dir: &str, cache_dir: &str, app_id: &str, sdk: i32) -> Result<Value, FfiError> {
    if data_dir.is_empty() || cache_dir.is_empty() {
        return Err(FfiError::invalid("data and cache dir must not be empty"));
    }
    crate::logger::init();
    crate::guard::install_panic_hook();
    let wanted = AppDirs {
        data_dir: data_dir.into(),
        cache_dir: cache_dir.into(),
        app_id: app_id.to_owned(),
        sdk,
    };
    let stored = DIRS.get_or_init(|| wanted.clone());
    if *stored != wanted {
        return Err(FfiError::invalid("init was already called with other dirs")
            .with_detail(json!({ "data_dir": stored.data_dir })));
    }
    Ok(version())
}

/// The stored dirs, `None` before `init`.
pub fn dirs() -> Option<&'static AppDirs> {
    DIRS.get()
}

/// `{"version": <crate version>, "abi": 1}`.
pub fn version() -> Value {
    json!({ "version": env!("CARGO_PKG_VERSION"), "abi": crate::ABI })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::ErrorKind;

    #[test]
    fn init_is_idempotent_and_refuses_other_dirs() {
        let first = init("/d", "/c", "app", 30).unwrap();
        assert_eq!(first["abi"], 1);
        assert_eq!(first["version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(init("/d", "/c", "app", 30).unwrap(), first);
        let error = init("/other", "/c", "app", 30).unwrap_err();
        assert_eq!(error.kind, ErrorKind::Invalid);
        assert_eq!(dirs().unwrap().data_dir, PathBuf::from("/d"));
        assert_eq!(init("", "/c", "a", 1).unwrap_err().kind, ErrorKind::Invalid);
    }
}
