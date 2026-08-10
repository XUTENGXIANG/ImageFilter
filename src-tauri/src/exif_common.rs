use std::io::BufReader;
use std::path::Path;

pub(crate) fn open_exif(path: &Path) -> Option<exif::Exif> {
    let file = std::fs::File::open(path).ok()?;
    let mut reader = BufReader::new(file);
    exif::Reader::new().read_from_container(&mut reader).ok()
}
