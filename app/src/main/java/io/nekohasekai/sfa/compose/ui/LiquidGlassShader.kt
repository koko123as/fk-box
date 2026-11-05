package io.nekohasekai.sfa.compose.ui

/*
 * flutter/shaders/liquid_glass.frag (FlClash UI kit, GPL-3.0) in AGSL, for a
 * RuntimeShader read as a RenderEffect over the pane's own layer.
 *
 * The layer holds the backdrop behind the pane in its top half and the same
 * backdrop blurred by the style's haze in its bottom half, as Flutter's capture
 * holds both in one atlas. Kept as the original: the circular bevel and the
 * raised lens, the frost on a Vogel disc, the haze, the parted channels along
 * the rim, saturation and brightness, the tint thickening over clashing
 * content, the lit rim with its tail, the darkened ends, the dark line and the
 * glow of a press. Left out: star outlines, flowing pills (goo), a menu's
 * banded rim, and the superellipse corners, which keep the circular inset.
 */
internal const val LIQUID_GLASS_AGSL = """
uniform shader uTexture;
uniform float2 uSize;
uniform float4 uFrame;
uniform float4 uSample;
uniform float4 uClamp;
uniform float2 uTexels;
uniform float4 uHazeSample;
uniform float4 uHazeClamp;
uniform float uHaze;
uniform float4 uShape;
uniform float4 uCorners;
uniform float3 uOptics;
uniform float4 uTint;
uniform float3 uLight;
uniform float4 uGlow;
uniform float2 uGlowSpread;
uniform float3 uEdge;
uniform float4 uRim;

const float3 kLuma = float3(0.2126, 0.7152, 0.0722);
const float kGoldenAngle = 2.39996322973;
const float kFrostReach = 2.07846096908;
const float kLine = 0.67;
const float kLineCap = 0.298;
const float kEndShade = 3.0;
const float kPartReach = 10.0;
const float kPartHold = 7.0;
const float kRimTail = 4.5;
const float kSteadyFrost = 8.0;

float4 texel(float2 pixel) {
  return uTexture.eval(clamp(pixel, uClamp.xy, min(uClamp.zw, uSize - 0.5)));
}

float4 sampleGlass(float2 point) {
  return texel((point - uSample.xy) / uSample.zw);
}

float4 sampleHaze(float2 point) {
  float2 pixel = (point - uHazeSample.xy) / uHazeSample.zw;
  return uTexture.eval(clamp(pixel, uHazeClamp.xy, min(uHazeClamp.zw, uSize - 0.5)));
}

float4 frostDisc16(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 16.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 16; i++) {
    float spread = sqrt((float(i) + 0.5) / 16.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

float4 frostDisc32(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 32.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 32; i++) {
    float spread = sqrt((float(i) + 0.5) / 32.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

float4 frostDisc64(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 64.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 64; i++) {
    float spread = sqrt((float(i) + 0.5) / 64.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

float4 sampleFrosted(float2 point, float frost) {
  float2 sigma = frost / uSample.zw;
  sigma = sqrt(max(sigma * sigma - uTexels.y * uTexels.y, float2(0.0)));
  float2 reach = max(sigma - 0.5, 0.0) * kFrostReach;
  if (reach.x <= 0.0 || reach.y <= 0.0) {
    return sampleGlass(point);
  }
  float2 ratio = reach / sigma;
  float falloff = 0.5 * ratio.x * ratio.y;
  float2 center = (point - uSample.xy) / uSample.zw;
  float widest = max(sigma.x, sigma.y);
  if (widest <= 2.0) {
    return frostDisc16(center, reach, falloff);
  }
  if (widest <= 3.5) {
    return frostDisc32(center, reach, falloff);
  }
  return frostDisc64(center, reach, falloff);
}

float4 sampleBehind(float2 point) {
  if (uHaze > 0.0) {
    return sampleHaze(point);
  }
  if (uShape.w >= kSteadyFrost) {
    return sampleGlass(point);
  }
  float2 spread = float2(uShape.w, -uShape.w);
  return 0.25 * (sampleGlass(point + spread.xx)
      + sampleGlass(point + spread.yy)
      + sampleGlass(point + spread.xy)
      + sampleGlass(point + spread.yx));
}

float roundedBoxDistance(float2 p, float2 halfSize, float radius) {
  float2 q = abs(p) - halfSize + radius;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedBoxNormal(float2 p, float2 halfSize, float radius) {
  float2 q = abs(p) - halfSize + radius;
  float2 n = q.x > 0.0 && q.y > 0.0
      ? normalize(q)
      : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
  return n * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

half4 main(float2 fragCoord) {
  float2 point = uFrame.xy + fragCoord * uFrame.zw;
  float2 halfSize = uShape.xy * 0.5;
  if (point.x < 0.0 || point.y < 0.0 || point.x > uShape.x || point.y > uShape.y) {
    return half4(0.0);
  }
  float halfMinor = min(halfSize.x, halfSize.y);
  float2 centered = point - halfSize;
  float2 side = step(0.0, centered);
  float radius = min(
    mix(mix(uCorners.x, uCorners.y, side.x), mix(uCorners.w, uCorners.z, side.x), side.y),
    halfMinor
  );
  float signedDistance = roundedBoxDistance(centered, halfSize, radius);
  // Antialiased cut to the pane's rounded outline.
  float shape = clamp(0.5 - signedDistance / uTexels.x, 0.0, 1.0);
  if (shape <= 0.0) {
    return half4(0.0);
  }
  float inset = max(-signedDistance, 0.0);
  float2 normal = roundedBoxNormal(centered, halfSize, radius);
  float edge = inset;

  bool lens = uOptics.x < 0.0;
  float bevel = max(min(uShape.z, halfMinor * 0.5), 1e-3);
  float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);
  float2 shift;
  if (lens) {
    shift = normal * -uOptics.x * smoothstep(0.0, bevel, inset)
        * (1.0 - smoothstep(bevel, halfMinor, inset));
  } else {
    float depth = min(uOptics.x, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));
    shift = -normal * depth;
  }

  float frost = uShape.w + uEdge.z * rise * rise;
  float4 base = sampleFrosted(point + shift, frost);
  if (uEdge.y > 0.0 && inset < kPartReach) {
    float2 part = normal.yx * 0.5 * uEdge.y
        * (0.6 + 0.4 * abs(normal.y) * max(1.0 - inset / kPartHold, 0.0))
        * (1.0 - smoothstep(kPartHold, kPartReach, inset));
    float4 red = sampleFrosted(point + shift + part, frost);
    float4 blue = sampleFrosted(point + shift - part, frost);
    base = float4(red.r, base.g, blue.b, max(base.a, max(red.a, blue.a)));
  }
  if (uHaze > 0.0) {
    base = mix(base, sampleHaze(point + shift), uHaze);
  }
  float alpha = base.a;
  float3 color = base.rgb / max(alpha, 1e-4);
  color = mix(float3(dot(color, kLuma)), color, uOptics.y) * uOptics.z;
  color *= 1.0 - uLight.z * abs(normal.x) * exp(-inset / kEndShade);

  float clash = 0.0;
  if (uGlow.w != 0.0) {
    float4 behind = sampleBehind(halfSize)
        + sampleBehind(halfSize * float2(0.4, 0.6))
        + sampleBehind(halfSize * float2(1.6, 0.6))
        + sampleBehind(halfSize * float2(0.4, 1.4))
        + sampleBehind(halfSize * float2(1.6, 1.4));
    behind = behind * 0.2;
    float behindLuma = dot(behind.rgb / max(behind.a, 1e-4), kLuma);
    clash = clamp((sign(uGlow.w) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5, 0.0, 1.0);
  }
  float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uGlow.w);
  float coverage = alpha * (1.0 - cover) + cover;
  float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

  float within = max(edge - kLine, 0.0);
  float facing;
  if (lens) {
    facing = smoothstep(0.65, 0.95, abs(normal.y));
  } else {
    float rise2 = normal.y * normal.y;
    float rise4 = rise2 * rise2;
    float rise16 = rise4 * rise4 * rise4 * rise4;
    facing = mix(rise2, 0.5 * rise2 * abs(normal.y) * (1.0 + rise16), uRim.y);
  }
  float band = clamp(1.0 - within / max(uLight.y, 1e-3), 0.0, 1.0);
  float tail = clamp(1.0 - max(inset - kLine, 0.0) / kRimTail, 0.0, 1.0);
  float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);
  glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;

  float line = clamp((kLine - edge) / uTexels.x + 0.5, 0.0, 1.0);
  float across = abs(normal.x);
  float darken = mix(uEdge.x, uRim.w, across * across * across);
  if ((uEdge.x > 0.0 || uRim.w > 0.0) && line > 0.0) {
    float4 under = sampleGlass(point);
    float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
    glass = mix(glass, mix(under.rgb, dark, darken), line);
    coverage = mix(coverage, under.a, line);
  }

  float2 toGlow = point - uGlow.xy;
  float reach = max(uGlowSpread.x * uGlowSpread.x, 1.0);
  glass += uGlow.z * coverage * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, uGlowSpread.y);

  float4 result = float4(min(glass, float3(coverage)), coverage) * shape;
  return half4(result);
}
"""
