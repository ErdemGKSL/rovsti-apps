//! Pure menu data: items, groups and a de-duplicated model. No gpui imports.

/// Look of an item.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum MenuRole {
    #[default]
    Normal,
    Destructive,
}

/// One tappable row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MenuItem {
    /// Stable id; unique within a model.
    pub key: &'static str,
    pub label: String,
    /// Icon name resolved by the renderer; `None` leaves the icon column empty.
    pub icon_name: Option<&'static str>,
    pub role: MenuRole,
    pub enabled: bool,
    pub checked: bool,
    /// Opens a nested list inside the popover.
    pub submenu: bool,
}

impl MenuItem {
    pub fn new(key: &'static str, label: impl Into<String>) -> Self {
        Self {
            key,
            label: label.into(),
            icon_name: None,
            role: MenuRole::Normal,
            enabled: true,
            checked: false,
            submenu: false,
        }
    }
    pub fn icon(mut self, name: &'static str) -> Self {
        self.icon_name = Some(name);
        self
    }
    pub fn destructive(mut self) -> Self {
        self.role = MenuRole::Destructive;
        self
    }
    pub fn disabled(mut self) -> Self {
        self.enabled = false;
        self
    }
    pub fn checked(mut self, checked: bool) -> Self {
        self.checked = checked;
        self
    }
    pub fn submenu(mut self) -> Self {
        self.submenu = true;
        self
    }
}

/// Items separated from other groups by a band.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct MenuGroup(pub Vec<MenuItem>);

impl MenuGroup {
    pub fn new(items: Vec<MenuItem>) -> Self {
        Self(items)
    }
}

/// Ordered groups; never contains an empty group.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct MenuModel {
    groups: Vec<MenuGroup>,
}

impl MenuModel {
    /// Drops empty groups and debug-asserts that keys are unique. Callers
    /// merging two sources use [`MenuModel::dedup_by_key`] first.
    pub fn new(groups: Vec<MenuGroup>) -> Self {
        let groups: Vec<MenuGroup> = groups.into_iter().filter(|g| !g.0.is_empty()).collect();
        #[cfg(debug_assertions)]
        {
            let mut seen = std::collections::HashSet::new();
            for item in groups.iter().flat_map(|g| g.0.iter()) {
                debug_assert!(seen.insert(item.key), "duplicate menu key {:?}", item.key);
            }
        }
        Self { groups }
    }

    /// Keeps the first item of each key (across groups), drops groups that
    /// become empty, preserves order, then builds the model.
    pub fn dedup_by_key(groups: Vec<MenuGroup>) -> Self {
        let mut seen = std::collections::HashSet::new();
        let groups = groups
            .into_iter()
            .map(|g| MenuGroup(g.0.into_iter().filter(|i| seen.insert(i.key)).collect()))
            .collect();
        Self::new(groups)
    }

    pub fn groups(&self) -> &[MenuGroup] {
        &self.groups
    }

    pub fn is_empty(&self) -> bool {
        self.groups.is_empty()
    }

    pub fn item_count(&self) -> usize {
        self.groups.iter().map(|g| g.0.len()).sum()
    }

    /// Item counts per group, for `layout::geom::menu_height`.
    pub fn group_sizes(&self) -> Vec<usize> {
        self.groups.iter().map(|g| g.0.len()).collect()
    }

    pub fn find(&self, key: &str) -> Option<&MenuItem> {
        self.groups
            .iter()
            .flat_map(|g| g.0.iter())
            .find(|i| i.key == key)
    }

    /// Longest label in characters-agnostic order; renderer measures text.
    pub fn labels(&self) -> impl Iterator<Item = &str> {
        self.groups
            .iter()
            .flat_map(|g| g.0.iter())
            .map(|i| i.label.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn g(keys: &[&'static str]) -> MenuGroup {
        MenuGroup(keys.iter().map(|k| MenuItem::new(k, *k)).collect())
    }

    #[test]
    fn empty_groups_are_dropped() {
        let m = MenuModel::new(vec![g(&[]), g(&["a"]), g(&[]), g(&["b"])]);
        assert_eq!(m.groups().len(), 2);
        assert_eq!(m.group_sizes(), vec![1, 1]);
    }

    #[test]
    fn order_is_preserved() {
        let m = MenuModel::new(vec![g(&["c", "a"]), g(&["b"])]);
        let keys: Vec<_> = m
            .groups()
            .iter()
            .flat_map(|g| g.0.iter().map(|i| i.key))
            .collect();
        assert_eq!(keys, vec!["c", "a", "b"]);
    }

    #[test]
    fn dedup_keeps_first_and_drops_emptied_groups() {
        let m =
            MenuModel::dedup_by_key(vec![g(&["pin", "mute"]), g(&["pin", "mute"]), g(&["del"])]);
        assert_eq!(m.group_sizes(), vec![2, 1]);
        assert_eq!(m.item_count(), 3);
        assert!(m.find("del").is_some());
    }

    #[test]
    fn dedup_across_groups_keeps_first_position() {
        let m = MenuModel::dedup_by_key(vec![g(&["a"]), g(&["b", "a"])]);
        assert_eq!(m.group_sizes(), vec![1, 1]);
        assert_eq!(m.groups()[1].0[0].key, "b");
    }

    #[test]
    #[should_panic(expected = "duplicate menu key")]
    #[cfg(debug_assertions)]
    fn duplicate_keys_assert_in_debug() {
        let _ = MenuModel::new(vec![g(&["a"]), g(&["a"])]);
    }

    #[test]
    fn builders_set_flags() {
        let i = MenuItem::new("x", "X")
            .icon("trash")
            .destructive()
            .disabled()
            .checked(true)
            .submenu();
        assert_eq!(i.role, MenuRole::Destructive);
        assert!(!i.enabled && i.checked && i.submenu);
        assert_eq!(i.icon_name, Some("trash"));
    }
}
