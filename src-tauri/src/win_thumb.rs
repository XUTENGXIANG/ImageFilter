/// Windows IShellItemImageFactory — mimics Explorer's thumbnail behavior.
/// Returns (width, height, RGBA pixel data).
#[cfg(target_os = "windows")]
pub fn get_shell_thumbnail(path: &str, size: u32) -> Option<(u32, u32, Vec<u8>)> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows::core::{Interface, PCWSTR};
    use windows::Win32::Graphics::Gdi::{
        CreateCompatibleDC, DeleteDC, DeleteObject, GetDIBits, GetObjectW, SelectObject,
        BITMAPINFO, BITMAPINFOHEADER, DIB_RGB_COLORS, BI_RGB,
    };
    use windows::Win32::UI::Shell::{
        IShellItemImageFactory, SHCreateItemFromParsingName,
        SIIGBF_BIGGERSIZEOK, SIIGBF_RESIZETOFIT,
    };

    let wide: Vec<u16> = OsStr::new(path).encode_wide().chain(Some(0)).collect();
    let pcwstr = PCWSTR::from_raw(wide.as_ptr());

    let item: windows::Win32::UI::Shell::IShellItem =
        unsafe { SHCreateItemFromParsingName(pcwstr, None) }.ok()?;
    let factory: IShellItemImageFactory = item.cast().ok()?;

    let sz = windows::Win32::Foundation::SIZE {
        cx: size as i32,
        cy: size as i32,
    };

    let flags = SIIGBF_RESIZETOFIT.0 | SIIGBF_BIGGERSIZEOK.0;
    let hbitmap = unsafe { factory.GetImage(sz, windows::Win32::UI::Shell::SIIGBF(flags)) }.ok()?;

    #[repr(C)]
    struct Bm {
        _typ: i32, w: i32, h: i32, _wb: i32, _planes: u16, _bpp: u16, _bits: usize,
    }
    let mut bm = unsafe { std::mem::zeroed::<Bm>() };
    unsafe { GetObjectW(hbitmap.into(), std::mem::size_of::<Bm>() as i32, Some(&mut bm as *mut _ as _)) };
    let w = bm.w.max(1) as u32;
    let h = bm.h.max(1) as u32;

    let mut pixels: Vec<u8> = vec![0u8; (w * h * 4) as usize];
    let bih = BITMAPINFOHEADER {
        biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
        biWidth: w as i32, biHeight: -(h as i32), biPlanes: 1, biBitCount: 32,
        biCompression: BI_RGB.0 as u32, biSizeImage: 0,
        biXPelsPerMeter: 0, biYPelsPerMeter: 0, biClrUsed: 0, biClrImportant: 0,
    };
    let bi = BITMAPINFO { bmiHeader: bih, bmiColors: [Default::default()] };

    let dc = unsafe { CreateCompatibleDC(None) };
    let _old = unsafe { SelectObject(dc, hbitmap.into()) };
    unsafe {
        GetDIBits(dc, hbitmap.into(), 0, h, Some(pixels.as_mut_ptr() as *mut _),
                   &bi as *const _ as *mut _, DIB_RGB_COLORS);
    }
    #[allow(unused_must_use)]
    unsafe { DeleteDC(dc); DeleteObject(hbitmap.into()); }

    for chunk in pixels.chunks_exact_mut(4) { chunk.swap(0, 2); } // BGRA → RGBA

    Some((w, h, pixels))
}
