# Test data

`gtfs-tiny.zip`: a three-stop GTFS feed (`stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt`) for `stops.gtfs_import`.
Fixture requests name it as `$DATA/gtfs-tiny.zip`; `tests/fixtures.rs` swaps `$DATA` for this directory.
`README.md` doubles as the "not a zip" case.
