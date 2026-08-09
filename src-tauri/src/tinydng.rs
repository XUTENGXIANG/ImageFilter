// tinydng FFI — DNG 优先解码器 (syoyo/tinydng)
// 加载 DNG 原始 bayer 数据 + C++ 端双线性 demosaic → RGB8
// 输出由 C 端 malloc, Rust 端负责 free

use image::{DynamicImage, RgbImage};

extern "C" {
    fn tinydng_decode(
        filename: *const std::os::raw::c_char,
        rgb_out: *mut *mut u8,
        w_out: *mut i32,
        h_out: *mut i32,
    ) -> i32;
    fn tinydng_free(p: *mut u8);
}

pub fn decode_dng_tinydng(path: &str) -> Option<DynamicImage> {
    let c_path = std::ffi::CString::new(path).ok()?;

    let mut rgb: *mut u8 = std::ptr::null_mut();
    let mut w: i32 = 0;
    let mut h: i32 = 0;

    let ret = unsafe {
        tinydng_decode(c_path.as_ptr(), &mut rgb, &mut w, &mut h)
    };

    if ret == 0 || rgb.is_null() || w <= 0 || h <= 0 {
        return None;
    }

    let buf_len = (w as usize) * (h as usize) * 3;
    let buf = unsafe { std::slice::from_raw_parts(rgb, buf_len) }.to_vec();

    // 释放 C 端内存
    unsafe { tinydng_free(rgb) };

    RgbImage::from_raw(w as u32, h as u32, buf)
        .map(DynamicImage::ImageRgb8)
}
