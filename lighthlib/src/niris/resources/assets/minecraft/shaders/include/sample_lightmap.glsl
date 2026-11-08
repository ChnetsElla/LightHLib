#ifndef MINECRAFT_SAMPLE_LIGHTMAP_GLSL
#define MINECRAFT_SAMPLE_LIGHTMAP_GLSL

// LightHLib niris build: the lightmap texture is 512x16 (see lightmap.fsh),
// and block-light bytes up to 255 address texels up to 263, so the upper
// clamp of the vanilla sampler is lifted accordingly. Byte B maps to texel
// B+8 (the +8 preserves the vanilla half-texel centering of the 16x16 grid);
// the sky axis is unchanged.
vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(vec2((uv.x + 8.5) / 512.0, (uv.y / 256.0) + 0.5 / 16.0),
            vec2(8.5 / 512.0, 0.5 / 16.0), vec2(263.5 / 512.0, 15.5 / 16.0)));
}

#endif
