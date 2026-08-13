use std::io::BufReader;
use std::path::Path;

pub(crate) fn open_exif(path: &Path) -> Option<exif::Exif> {
    let file = std::fs::File::open(path).ok()?;
    let mut reader = BufReader::new(file);
    exif::Reader::new().read_from_container(&mut reader).ok()
}

/// 读取 EXIF Orientation 值（1-8, 无则 None）— images.rs 缩略图/全图方向校正统一走这里
pub(crate) fn orientation(path: &Path) -> Option<u16> {
    let exif_reader = open_exif(path)?;
    let value = exif_reader
        .fields()
        .find(|f| f.tag == exif::Tag::Orientation)
        .and_then(|f| f.value.get_uint(0))
        .map(|v| v as u16);
    value
}

/// 读取第一个匹配 tag 的文本值（importer.rs 的日期/相机字段收敛用）
pub(crate) fn first_text_field(path: &Path, tags: &[exif::Tag]) -> Option<String> {
    let exif_reader = open_exif(path)?;
    let value = exif_reader
        .fields()
        .find(|f| tags.contains(&f.tag))
        .map(|f| f.display_value().to_string());
    value
}
