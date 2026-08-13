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

    // ── 边界校验: w/h 由 C 侧返回, 来自不可信 DNG 头 — 与 C 侧同样的上限 ──
    const MAX_DIM: i32 = 20000;
    const MAX_BUFFER: usize = 512 * 1024 * 1024; // w*h*3 上限 512MB
    let buf_len = if w <= MAX_DIM && h <= MAX_DIM {
        (w as usize)
            .checked_mul(h as usize)
            .and_then(|v| v.checked_mul(3))
    } else {
        None
    };
    let Some(buf_len) = buf_len else {
        unsafe { tinydng_free(rgb) };
        return None;
    };
    if buf_len > MAX_BUFFER {
        unsafe { tinydng_free(rgb) };
        return None;
    }

    let buf = unsafe { std::slice::from_raw_parts(rgb, buf_len) }.to_vec();

    // 释放 C 端内存
    unsafe { tinydng_free(rgb) };

    RgbImage::from_raw(w as u32, h as u32, buf)
        .map(DynamicImage::ImageRgb8)
}
