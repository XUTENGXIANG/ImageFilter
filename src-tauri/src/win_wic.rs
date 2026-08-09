/// Windows WIC (Windows Imaging Component) RAW 解码
/// 利用系统自带 codec（Photos 应用注册的 RAW 解码器）— AVX 优化、秒级解码
/// 与 Windows Photos 看图同一条解码链

use image::{DynamicImage, RgbaImage};

#[cfg(target_os = "windows")]
pub fn decode_raw_wic(path: &str) -> Option<DynamicImage> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows::core::PCWSTR;
    use windows::Win32::Graphics::Imaging::{
        CLSID_WICImagingFactory, GUID_WICPixelFormat32bppBGRA, IWICImagingFactory,
        WICDecodeMetadataCacheOnDemand, WICConvertBitmapSource, WICRect,
    };
    use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_INPROC_SERVER};
    use windows::Win32::Foundation::GENERIC_READ;

    let wide: Vec<u16> = OsStr::new(path).encode_wide().chain(Some(0)).collect();
    let pcwstr = PCWSTR::from_raw(wide.as_ptr());

    // 创建 WIC 工厂
    let factory: IWICImagingFactory = unsafe {
        CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)
    }
    .ok()?;

    // 打开文件解码（系统 codec 自动选择，RAW 走 Camera Codec）
    let decoder = unsafe {
        factory.CreateDecoderFromFilename(
            pcwstr,
            None,
            GENERIC_READ,
            WICDecodeMetadataCacheOnDemand,
        )
    }
    .ok()?;

    let frame = unsafe { decoder.GetFrame(0) }.ok()?;

    // 获取尺寸
    let mut w: u32 = 0;
    let mut h: u32 = 0;
    unsafe { frame.GetSize(&mut w, &mut h) }.ok()?;
    if w == 0 || h == 0 { return None; }

    // 转换到 32bpp BGRA
    let converted = unsafe { WICConvertBitmapSource(&GUID_WICPixelFormat32bppBGRA, &frame) }.ok()?;

    let stride = w * 4;
    let mut buffer: Vec<u8> = vec![0u8; (w * h * 4) as usize];
    let rect = WICRect { X: 0, Y: 0, Width: w as i32, Height: h as i32 };
    unsafe { converted.CopyPixels(&rect, stride, &mut buffer) }.ok()?;

    // BGRA → RGBA
    for chunk in buffer.chunks_exact_mut(4) {
        chunk.swap(0, 2);
    }

    RgbaImage::from_raw(w, h, buffer).map(DynamicImage::ImageRgba8)
}

#[cfg(not(target_os = "windows"))]
pub fn decode_raw_wic(_path: &str) -> Option<DynamicImage> {
    None
}
