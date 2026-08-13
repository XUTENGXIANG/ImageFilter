fn main() {
    tauri_build::build();

    // 编译 tinydng C++ 桥接 (DNG 解码器)
    let mut build = cc::Build::new();
    build
        .cpp(true)
        .flag_if_supported("/utf-8")
        .file("third_party/tinydng/bridge.cpp")
        .include("third_party/tinydng");
    // MSVC 的 cl 不识别 -std 标志(默认 C++14 已够用), 仅 gcc/clang 显式指定
    #[cfg(not(target_env = "msvc"))]
    {
        build.std("c++11");
    }
    build.compile("tinydng_bridge");
}
