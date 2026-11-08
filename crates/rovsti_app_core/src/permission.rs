//! State of an Android permission group as the app logic sees it. The platform
//! layers (`rovsti_android`, the Kotlin `PermissionState`) convert into this.

/// State of a permission group.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum Access {
    #[default]
    Denied,
    Granted,
    /// Denied and Android will no longer show the dialog: send the user to
    /// the app's settings page instead.
    PermanentlyDenied,
}
