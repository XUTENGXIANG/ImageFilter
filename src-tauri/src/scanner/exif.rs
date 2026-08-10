use super::PhotoExif;

/// Lazy-load EXIF for a single photo (called when user selects a photo)
#[tauri::command]
pub fn get_exif(file_path: String) -> PhotoExif {
    extract_exif(std::path::Path::new(&file_path))
}

fn extract_exif(file_path: &std::path::Path) -> PhotoExif {
    let mut exif = PhotoExif::default();

    if let Ok(meta) = std::fs::metadata(file_path) {
        exif.file_size = meta.len();
    }

    let Some(exif_reader) = crate::exif_common::open_exif(file_path) else {
        return exif;
    };

    for field in exif_reader.fields() {
        match field.tag {
            exif::Tag::Make => {
                exif.camera_make = Some(field.display_value().to_string());
            }
            exif::Tag::Model => {
                exif.camera_model = Some(field.display_value().to_string());
            }
            exif::Tag::LensModel => {
                exif.lens_model = Some(field.display_value().to_string());
            }
            exif::Tag::FocalLength => {
                exif.focal_length = Some(field.display_value().to_string());
            }
            exif::Tag::FNumber => {
                exif.aperture = Some(field.display_value().to_string());
            }
            exif::Tag::ExposureTime => {
                exif.shutter_speed = Some(field.display_value().to_string());
            }
            exif::Tag::PhotographicSensitivity => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.iso = Some(v);
                }
            }
            exif::Tag::DateTimeOriginal | exif::Tag::DateTime => {
                let date_str = field.display_value().to_string();
                if !date_str.is_empty() {
                    exif.date_taken = Some(date_str);
                }
            }
            exif::Tag::ImageWidth => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.image_width = Some(v);
                }
            }
            exif::Tag::ImageLength => {
                if let Some(v) = field.value.get_uint(0) {
                    exif.image_height = Some(v);
                }
            }
            _ => {}
        }
    }

    exif
}
