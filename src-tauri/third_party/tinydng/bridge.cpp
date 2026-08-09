// tinydng C bridge: load DNG -> simple bayer demosaic -> RGB8
// stb 声明先行, tinydng 不重复 include, 最后单独实例化实现
#include "stb_image.h"
#define TINY_DNG_LOADER_IMPLEMENTATION
#define TINY_DNG_LOADER_NO_STB_IMAGE_INCLUDE
#include "tiny_dng_loader.h"
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#include <cstdlib>
#include <cstring>

extern "C" {

// Output: rgb allocated by malloc, caller must free via tinydng_free.
// Returns 1 on success, 0 on failure.
int tinydng_decode(const char* filename, unsigned char** rgb_out, int* w_out, int* h_out) {
    std::vector<tinydng::DNGImage> images;
    std::vector<tinydng::FieldInfo> custom_fields;
    std::string warn, err;

    bool ret = tinydng::LoadDNG(filename, custom_fields, &images, &warn, &err);
    if (!ret || images.empty()) return 0;

    tinydng::DNGImage& img = images[0];
    int w = static_cast<int>(img.width);
    int h = static_cast<int>(img.height);
    if (w <= 0 || h <= 0) return 0;

    // Get decoded pixel data (len = spp * w * h * bps / 8)
    if (img.data.empty()) return 0;
    const unsigned char* src = img.data.data();
    int bits = img.bits_per_sample > 0 ? img.bits_per_sample : 16;
    int spp = img.samples_per_pixel;
    if (spp <= 0) spp = 1;
    size_t src_pitch = (size_t)w * spp * (bits >= 16 ? 2 : 1);

    // If already RGB (spp>=3), copy directly
    if (spp >= 3) {
        unsigned char* rgb = static_cast<unsigned char*>(malloc((size_t)w * h * 3));
        if (!rgb) return 0;
        const float max_val = (bits >= 16) ? 65535.0f : 255.0f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                size_t si = ((size_t)y * w + x) * spp * (bits >= 16 ? 2 : 1);
                size_t di = ((size_t)y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float v;
                    if (bits >= 16) {
                        v = (float)(src[si + c*2] | (src[si + c*2 + 1] << 8));
                    } else {
                        v = (float)src[si + c];
                    }
                    rgb[di + c] = (unsigned char)((v / max_val) * 255.0f + 0.5f);
                }
            }
        }
        *rgb_out = rgb; *w_out = w; *h_out = h;
        return 1;
    }

    // Simple bilinear demosaic (RGGB) -> RGB8
    unsigned char* rgb = static_cast<unsigned char*>(malloc((size_t)w * h * 3));
    if (!rgb) return 0;

    const float max_val = (bits >= 16) ? 65535.0f : 255.0f;

    auto fetch = [&](int yy, int xx) -> float {
        yy = yy < 0 ? 0 : (yy >= h ? h - 1 : yy);
        xx = xx < 0 ? 0 : (xx >= w ? w - 1 : xx);
        if (bits >= 16) {
            const unsigned char* p = src + (size_t)yy * src_pitch + (size_t)xx * 2;
            return (float)(p[0] | (p[1] << 8));
        } else {
            return (float)src[(size_t)yy * src_pitch + (size_t)xx];
        }
    };

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float v00;
            if (bits >= 16) {
                const unsigned char* p = src + (size_t)y * src_pitch + (size_t)x * 2;
                v00 = (float)(p[0] | (p[1] << 8));
            } else {
                v00 = (float)src[(size_t)y * src_pitch + (size_t)x];
            }

            // CFA position: (y%2, x%2) -> R=00 G=01/10 B=11 (RGGB)
            int row_par = y & 1, col_par = x & 1;

            float r, g, b;
            if (row_par == 0 && col_par == 0) {      // R
                r = v00;
                g = (fetch(y, x-1) + fetch(y, x+1) + fetch(y-1, x) + fetch(y+1, x)) * 0.25f;
                b = (fetch(y-1, x-1) + fetch(y-1, x+1) + fetch(y+1, x-1) + fetch(y+1, x+1)) * 0.25f;
            } else if (row_par == 0 && col_par == 1) { // Gr
                g = v00;
                r = (fetch(y, x-1) + fetch(y, x+1)) * 0.5f;
                b = (fetch(y-1, x) + fetch(y+1, x)) * 0.5f;
            } else if (row_par == 1 && col_par == 0) { // Gb
                g = v00;
                r = (fetch(y-1, x) + fetch(y+1, x)) * 0.5f;
                b = (fetch(y, x-1) + fetch(y, x+1)) * 0.5f;
            } else {                                 // B
                b = v00;
                r = (fetch(y-1, x-1) + fetch(y-1, x+1) + fetch(y+1, x-1) + fetch(y+1, x+1)) * 0.25f;
                g = (fetch(y, x-1) + fetch(y, x+1) + fetch(y-1, x) + fetch(y+1, x)) * 0.25f;
            }

            size_t idx = ((size_t)y * w + x) * 3;
            rgb[idx + 0] = (unsigned char)((r / max_val) * 255.0f + 0.5f);
            rgb[idx + 1] = (unsigned char)((g / max_val) * 255.0f + 0.5f);
            rgb[idx + 2] = (unsigned char)((b / max_val) * 255.0f + 0.5f);
        }
    }

    *rgb_out = rgb;
    *w_out = w;
    *h_out = h;
    return 1;
}

void tinydng_free(unsigned char* p) { free(p); }

} // extern "C"
