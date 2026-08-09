fn main() {
    tauri_build::build();

    // 编译 tinydng C++ 桥接 (DNG 解码器)
    cc::Build::new()
        .cpp(true)
        .std("c++11")
        .flag_if_supported("/utf-8")
        .file("third_party/tinydng/bridge.cpp")
        .include("third_party/tinydng")
        .compile("tinydng_bridge");
}
