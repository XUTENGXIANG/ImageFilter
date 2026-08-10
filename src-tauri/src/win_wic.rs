/// Windows WIC (Windows Imaging Component) RAW 解码
/// 利用系统自带 codec（Photos 应用注册的 RAW 解码器）— AVX 优化、秒级解码
/// 与 Windows Photos 看图同一条解码链

use image::{DynamicImage, RgbaImage};

#[cfg(target_os = "windows")]
pub fn decode_raw_wic(path: &str) -> Option<DynamicImage> {
    use windows::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED};

    let com_init = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
    let result = decode_raw_wic_inner(path);
    if com_init.is_ok() {
        unsafe { CoUninitialize() };
    }
    result
}

#[cfg(target_os = "windows")]
pub fn thumbnail_from_shell(path: &str, max_size: u32) -> Option<DynamicImage> {
    use windows::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED};

    let com_init = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
    let result = thumbnail_from_shell_inner(path, max_size);
    if com_init.is_ok() {
        unsafe { CoUninitialize() };
    }
    result
}

#[cfg(target_os = "windows")]
fn thumbnail_from_shell_inner(path: &str, max_size: u32) -> Option<DynamicImage> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::SIZE;
    use windows::Win32::Graphics::Gdi::{DeleteObject, HBITMAP, HPALETTE};
    use windows::Win32::Graphics::Imaging::{
        CLSID_WICImagingFactory, GUID_WICPixelFormat32bppBGRA, IWICImagingFactory,
        WICBitmapIgnoreAlpha, WICConvertBitmapSource, WICRect,
    };
    use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_INPROC_SERVER};
    use windows::Win32::UI::Shell::{
        SHCreateItemFromParsingName, IShellItemImageFactory, SIIGBF_BIGGERSIZEOK,
        SIIGBF_THUMBNAILONLY,
    };

    let wide: Vec<u16> = OsStr::new(path).encode_wide().chain(Some(0)).collect();
    let pcwstr = PCWSTR::from_raw(wide.as_ptr());
    let shell_item: IShellItemImageFactory =
        unsafe { SHCreateItemFromParsingName(pcwstr, None) }.ok()?;

    let size = SIZE {
        cx: max_size as i32,
        cy: max_size as i32,
    };
    let hbitmap: HBITMAP = unsafe {
        shell_item.GetImage(size, SIIGBF_THUMBNAILONLY | SIIGBF_BIGGERSIZEOK)
    }
    .ok()?;

    let factory: IWICImagingFactory = unsafe {
        CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)
    }
    .ok()?;

    let bitmap = unsafe {
        factory.CreateBitmapFromHBITMAP(hbitmap, HPALETTE::default(), WICBitmapIgnoreAlpha)
    };
    unsafe { let _ = DeleteObject(hbitmap.into()); };
    let bitmap = bitmap.ok()?;

    let mut w: u32 = 0;
    let mut h: u32 = 0;
    unsafe { bitmap.GetSize(&mut w, &mut h) }.ok()?;
    if w == 0 || h == 0 {
        return None;
    }

    let converted = unsafe { WICConvertBitmapSource(&GUID_WICPixelFormat32bppBGRA, &bitmap) }.ok()?;
    let stride = w * 4;
    let mut buffer: Vec<u8> = vec![0u8; (w * h * 4) as usize];
    let rect = WICRect {
        X: 0,
        Y: 0,
        Width: w as i32,
        Height: h as i32,
    };
    unsafe { converted.CopyPixels(&rect, stride, &mut buffer) }.ok()?;

    for chunk in buffer.chunks_exact_mut(4) {
        chunk.swap(0, 2);
    }

    let img = RgbaImage::from_raw(w, h, buffer).map(DynamicImage::ImageRgba8)?;
    if img.width().max(img.height()) > max_size {
        Some(img.thumbnail(max_size, max_size))
    } else {
        Some(img)
    }
}

#[cfg(target_os = "windows")]
fn decode_raw_wic_inner(path: &str) -> Option<DynamicImage> {
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
