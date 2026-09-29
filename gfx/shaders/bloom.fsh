#version 330 core

smooth in vec2 vertUv;

layout(location = 0) out vec4 frag;

uniform sampler2D screen;
uniform int mode;
uniform float threshold;
uniform float radius;

vec3 toLinear(vec3 colour) {
  return pow(max(colour, vec3(0.0)), vec3(2.2));
}

vec3 brightPass(vec2 uv) {
  vec2 texel = 1.0 / vec2(textureSize(screen, 0));

  vec3 colour = (
    toLinear(texture(screen, uv + vec2(-0.5, -0.5) * texel).rgb) +
    toLinear(texture(screen, uv + vec2( 0.5, -0.5) * texel).rgb) +
    toLinear(texture(screen, uv + vec2(-0.5,  0.5) * texel).rgb) +
    toLinear(texture(screen, uv + vec2( 0.5,  0.5) * texel).rgb)
  ) * 0.25;

  float brightness = max(max(colour.r, colour.g), colour.b);
  const float knee = 0.25;
  float soft = clamp((brightness - threshold + knee) / (2.0 * knee), 0.0, 1.0);
  soft = soft * soft * knee;
  float contribution = max(brightness - threshold, 0.0) + soft;
  contribution /= max(brightness, 0.0001);

  return colour * contribution;
}

vec3 gaussianBlur(vec2 uv, vec2 direction) {
  vec2 texel = 1.0 / vec2(textureSize(screen, 0));
  vec2 stepUv = texel * direction * radius;

  vec3 colour = texture(screen, uv).rgb * 0.2270270270;
  colour += texture(screen, uv + stepUv * 1.0).rgb * 0.1945945946;
  colour += texture(screen, uv - stepUv * 1.0).rgb * 0.1945945946;
  colour += texture(screen, uv + stepUv * 2.0).rgb * 0.1216216216;
  colour += texture(screen, uv - stepUv * 2.0).rgb * 0.1216216216;
  colour += texture(screen, uv + stepUv * 3.0).rgb * 0.0540540541;
  colour += texture(screen, uv - stepUv * 3.0).rgb * 0.0540540541;
  colour += texture(screen, uv + stepUv * 4.0).rgb * 0.0162162162;
  colour += texture(screen, uv - stepUv * 4.0).rgb * 0.0162162162;
  return colour;
}

void main() {
  vec3 colour;

  if(mode == 0) {
    colour = brightPass(vertUv);
  } else if(mode == 1) {
    colour = gaussianBlur(vertUv, vec2(1.0, 0.0));
  } else {
    colour = gaussianBlur(vertUv, vec2(0.0, 1.0));
  }

  frag = vec4(colour, 1.0);
}
