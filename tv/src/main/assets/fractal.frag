#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 uv;
uniform vec2 resolution;
uniform vec3 spectrum;
uniform float time;
uniform int scene;

vec2 square(vec2 z) {
    return vec2(z.x*z.x-z.y*z.y, 2.0*z.x*z.y);
}
vec3 palette(float x, float theme) {
    return vec3(0.50,0.50,0.50) + vec3(0.48,0.46,0.48)
        * cos(6.28318 * (x + vec3(0.0,0.22,0.47) + theme));
}
void main() {
    vec2 p = (uv - 0.5) * vec2(resolution.x/resolution.y, 1.0);
    float t = time;
    float angle = 0.16*sin(t*.13) + (scene==2 ? t*.045 : 0.0);
    p = mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*p;
    vec2 z, c;
    if (scene==1) {
        float zoom = exp(-2.75*(.5-.5*cos(t*.13)));
        c = vec2(-.745,.112) + 2.25*p*zoom/(1.0+.32*spectrum.x);
        z = vec2(0.0);
    } else if (scene==2) {
        p.y = abs(p.y);
        z = p*(2.8-.45*spectrum.x);
        c = vec2(-.17+.10*sin(t*.15), .61+.055*cos(t*.23));
    } else {
        z = p*(2.75-.42*spectrum.x);
        c = vec2(-.74+.11*sin(t*.18), .16+.12*cos(t*.21));
        c.y += .06*spectrum.y;
    }
    float trap = 5.0;
    float escaped = 42.0;
    float radius = 1.0;
    for (int i=0; i<42; i++) {
        z = square(z) + c;
        radius = dot(z,z);
        trap = min(trap, abs(z.x*z.y)+0.14*abs(z.x+z.y));
        if (radius > 64.0) { escaped = float(i); break; }
    }
    float edge = exp(-17.0*trap);
    float band = escaped < 42.0 ? escaped+1.0-log2(log2(sqrt(radius))) : 41.5;
    float phase = band*.047 + edge*.16 + t*.021 + spectrum.y*.09;
    vec3 rgb = palette(phase, float(scene)*.15);
    float glow = min(.88, .23 + .42*edge + .18*spectrum.z + .12*spectrum.x);
    if (escaped==42.0) glow = .055 + edge*(.34+.14*spectrum.z);
    float vignette = 1.0 - .33*clamp(dot(uv-.5,uv-.5)*2.0,0.0,1.0);
    gl_FragColor = vec4((vec3(.012,.019,.036) + rgb*glow)*vignette, 1.0);
}
