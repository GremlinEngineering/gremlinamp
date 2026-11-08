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
uniform vec4 ball;

vec2 square(vec2 z) {
    return vec2(z.x*z.x-z.y*z.y, 2.0*z.x*z.y);
}
vec3 palette(float x, float theme) {
    return vec3(0.50,0.50,0.50) + vec3(0.48,0.46,0.48)
        * cos(6.28318 * (x + vec3(0.0,0.22,0.47) + theme));
}
float segment(vec2 p, vec2 a, vec2 b) {
    vec2 direction = b-a;
    return length(p-a-direction*clamp(dot(p-a,direction)/max(dot(direction,direction), .000001), 0.0, 1.0));
}
float beam(float distance) {
    return .77 * (1.0 - smoothstep(.0008, .0033, distance)) + .24 * exp(-82.0 * distance);
}
vec2 rotated(vec2 p, float angle) {
    return mat2(cos(angle), -sin(angle), sin(angle), cos(angle))*p;
}
float asteroid(vec2 p, float size, float seed) {
    float angle = atan(p.y, p.x);
    float sector = floor((angle+3.141593)/.785398);
    float a = sector*.785398-3.141593;
    float b = a+.785398;
    vec2 from = size * (.81+.17*sin(a*3.0+seed)) * vec2(cos(a),sin(a));
    vec2 to = size * (.81+.17*sin(b*3.0+seed)) * vec2(cos(b),sin(b));
    return segment(p, from, to);
}
vec3 vectorScene(vec2 p, float t) {
    if (scene == 1) {
        float court = min(segment(p, vec2(-.65,-.19), vec2(.65,-.19)),
                          segment(p, vec2(0.0,-.19), vec2(0.0,-.09)));
        float trail = beam(segment(p, ball.zw, ball.xy));
        float head = exp(-115.0*length(p-ball.xy));
        return vec3(.004,.018,.012) + vec3(.34,1.0,.63)*(.55*beam(court)+.26*trail+.89*head);
    }
    vec2 ship = rotated(p-vec2(.06*sin(t*.43),.11+.025*cos(t*.72)), t*.65);
    float edge = min(segment(ship,vec2(0.,.075),vec2(-.048,-.045)),
                     segment(ship,vec2(0.,.075),vec2(.048,-.045)));
    edge = min(edge,segment(ship,vec2(-.048,-.045),vec2(0.,-.023)));
    edge = min(edge,segment(ship,vec2(.048,-.045),vec2(0.,-.023)));
    float thrust = segment(ship,vec2(0.,-.04),vec2(0.,-.068-.047*spectrum.x));
    vec3 light = vec3(.36,1.0,.87) * beam(edge) + vec3(1.0,.63,.26)*beam(thrust)*(.28+.57*spectrum.x);
    for (int i=0; i<5; i++) {
        float n = float(i);
        vec2 at = vec2(fract(n*.38 + t*(.032+n*.006))*1.86-.93, .33*sin(n*1.89+t*.21)+.08);
        vec2 rock = rotated(p-at,t*(.14+.035*n)+n);
        float size = .047+.012*n;
        float rim = beam(asteroid(rock,size,n));
        float echo = beam(asteroid(rock,size*.54,n));
        light += vec3(.56,.68,1.0)*rim*(.45+.19*spectrum.y) + vec3(.58,.35,1.0)*echo*.26;
    }
    vec2 grid = p*vec2(42.0,31.0);
    float star = step(.975,fract(sin(dot(floor(grid),vec2(13.1,71.3)))*4375.84));
    light += vec3(.26,.36,.54)*star*exp(-13.0*length(fract(grid)-.5));
    return vec3(.008,.009,.026) + light;
}
void main() {
    vec2 p = (uv - 0.5) * vec2(resolution.x/resolution.y, 1.0);
    float t = time;
    if (scene < 2) {
        float scan = .95 + .05*sin(uv.y*resolution.y*3.141593);
        gl_FragColor = vec4(min(vec3(1.0), vectorScene(p,t)*scan), 1.0);
        return;
    }
    int fractal = scene-2;
    float angle = 0.16*sin(t*.13) + (fractal==2 ? t*.045 : 0.0);
    p = mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*p;
    vec2 z, c;
    if (fractal==1) {
        float zoom = exp(-2.75*(.5-.5*cos(t*.13)));
        c = vec2(-.745,.112) + 2.25*p*zoom/(1.0+.32*spectrum.x);
        z = vec2(0.0);
    } else if (fractal==2) {
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
    vec3 rgb = palette(phase, float(fractal)*.15);
    float glow = min(.88, .23 + .42*edge + .18*spectrum.z + .12*spectrum.x);
    if (escaped==42.0) glow = .055 + edge*(.34+.14*spectrum.z);
    float vignette = 1.0 - .33*clamp(dot(uv-.5,uv-.5)*2.0,0.0,1.0);
    gl_FragColor = vec4((vec3(.012,.019,.036) + rgb*glow)*vignette, 1.0);
}
