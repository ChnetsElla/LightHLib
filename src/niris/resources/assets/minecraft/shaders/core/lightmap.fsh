#version 330
#extension GL_ARB_separate_shader_objects : require

// LightHLib niris build: extended HDR lightmap.
//
// The vanilla lightmap is 16x16 RGBA8 and clamps colors to 1.0, which is why
// nothing can ever render brighter than a vanilla torch. This patched shader
// (together with NirisLightmapMixin replacing the texture with a 512x16
// RGBA16F one) extends the curve past the vanilla maximum at the vanilla
// top-level growth ratio (~+28% per level), up to ~7.4x vanilla full
// brightness. Light bytes 241..255 (written by the LightHLib render hooks)
// address the over-bright part of the curve.

layout(std140) uniform LightmapInfo {
    float SkyFactor;
    float BlockFactor;
    float NightVisionFactor;
    float DarknessScale;
    float BossOverlayWorldDarkeningFactor;
    float BrightnessFactor;
    vec3 BlockLightTint;
    vec3 SkyLightColor;
    vec3 AmbientColor;
    vec3 NightVisionColor;
} lightmapInfo;

// LightHLib takeover switch, provided as a shader define by
// NirisLightmapMixin: the takeover pipeline variant is compiled with
// HLIB_TAKEOVER defined; the vanilla pipeline compiles it out. Absent the
// define, the vanilla-exact path below is used (conservative default).
#ifdef HLIB_TAKEOVER
#define HL_TAKEOVER 1
#else
#define HL_TAKEOVER 0
#endif

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float get_brightness(float level) {
    return level / (4.0 - 3.0 * level);
}

vec3 notGamma(vec3 color) {
    float maxComponent = max(max(color.x, color.y), color.z);
    float maxInverted = 1.0f - maxComponent;
    float maxScaled = 1.0f - maxInverted * maxInverted * maxInverted * maxInverted;
    return color * (maxScaled / maxComponent);
}

float parabolicMixFactor(float level) {
    return (2.0 * level - 1.0) * (2.0 * level - 1.0);
}

// The vanilla lightmap curve in light-byte space: byte B encodes the texel
// position B/16 on the vanilla 16x16 grid, i.e. normalized level B/240.
// vanilla_brightness(B) = (B/240) / (4 - 3*(B/240)) = B / (960 - 3B).
float lighthlib_block_brightness(float blockByte) {
    if (blockByte <= 240.0) {
        if (bool(HL_TAKEOVER)) {
            // takeover: continuous sub-texel curve with the lifted fringe -
            // smoother falloff and faintly visible field edges, but it also
            // shifts vanilla content's brightness (brighter darks)
            float vanilla = blockByte / (960.0 - 3.0 * blockByte);
            return max(vanilla, blockByte * 0.0019);
        }
        // EXACT vanilla curve: byte B maps to the vanilla 16-level grid
        // level floor(B/16)/15, matching vanilla's
        // get_brightness(floor(texCoord.x*16)/15) sampling. The earlier
        // continuous interpolation and fringe lift here changed brightness of
        // vanilla-only worlds (dark areas brighter, torch falloff smoother
        // than vanilla) - both are gone; vanilla bytes look exactly vanilla.
        return get_brightness(floor(blockByte / 16.0) / 15.0);
    }
    // LightHLib 16-bit extension: continue past the vanilla maximum at the
    // vanilla top-level growth ratio (~+28% per level), capped at ~7.4x.
    return min(7.4, pow(1.284, (blockByte - 240.0) * (8.0 / 15.0)));
}

void main() {
    // The 512-wide texture: fragment texel T holds the value for light byte
    // T - 8 (the +8 preserves the vanilla half-texel centering).
    float blockByte = clamp(floor(texCoord.x * 512.0) - 8.0, 0.0, 263.0);
    float sky_level = floor(texCoord.y * 16) / 15;

    // Bytes 0..240 (the vanilla range) must map EXACTLY onto vanilla's
    // 16-level curve: vanilla computes get_brightness(floor(texCoord.x*16)/15),
    // so byte B (B = 16*L) resolves to vanilla level L = B/16. Restoring the
    // vanilla block_light tint mix keeps torches warm and dark areas black
    // without any HLib source placed; only the 241..263 over-bright extension
    // (written solely by LightHLib render hooks) takes the new curve.
    float block_level;
    if (blockByte <= 240.0) {
        block_level = floor(blockByte / 16.0) / 15.0;
    } else {
        block_level = 1.0;
    }

    float block_brightness = lighthlib_block_brightness(blockByte) * lightmapInfo.BlockFactor;
    float sky_brightness = get_brightness(sky_level) * lightmapInfo.SkyFactor;

    // Calculate ambient color with or without night vision
    vec3 nightVisionColor = lightmapInfo.NightVisionColor * lightmapInfo.NightVisionFactor;
    vec3 color = max(lightmapInfo.AmbientColor, nightVisionColor);

    // Add sky light
    color += lightmapInfo.SkyLightColor * sky_brightness;

    // Add block light
    // LightHLib neutral-tint note: vanilla mixes the warm BlockLightTint out
    // at high levels, and LightHLib's LHCE vertex tint needs a neutral base
    // for colored light - but only the over-bright bytes are LightHLib's.
    // Vanilla bytes 0..240 keep the exact vanilla BlockLightColor mix unless
    // takeover is enabled; the extension bytes (and takeover mode) use a
    // neutral white base for the LHCE color multiply.
    vec3 BlockLightColor;
    if (blockByte > 240.0 || bool(HL_TAKEOVER)) {
        BlockLightColor = vec3(1.0);
    } else {
        BlockLightColor = mix(lightmapInfo.BlockLightTint, vec3(1.0), 0.9 * parabolicMixFactor(block_level));
    }
    color += BlockLightColor * block_brightness;

    // Apply boss overlay darkening effect
    color = mix(color, color * vec3(0.7, 0.6, 0.6), lightmapInfo.BossOverlayWorldDarkeningFactor);

    // Apply darkness effect scale
    color = color - vec3(lightmapInfo.DarknessScale);

    // Apply brightness - the vanilla 1.0 clamp for the vanilla byte range
    // (0..240), so no-HLib worlds render exactly vanilla: vanilla saturates
    // ambient+sky+block sums to 1.0, and any extra headroom here brightens
    // every torch-lit surface unconditionally. HDR headroom (7.5) applies to
    // the over-bright extension bytes (241..263) and to takeover mode.
    color = clamp(color, 0.0, (blockByte > 240.0 || bool(HL_TAKEOVER)) ? 7.5 : 1.0);
    vec3 notGamma = notGamma(min(color, 1.0));
    color = mix(color, notGamma, lightmapInfo.BrightnessFactor);

    fragColor = vec4(color, 1.0);
}
