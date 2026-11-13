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
uniform vec3 impact;
uniform vec4 ship;
uniform vec4 rocks[5];
uniform vec4 shots[4];

vec2 square(vec2 z) {
    return vec2(z.x*z.x-z.y*z.y, 2.0*z.x*z.y);
}
vec3 palette(float x, float theme) {
    return vec3(0.50,0.50,0.50) + vec3(0.48,0.46,0.48)
        * cos(6.28318 * (x + vec3(0.0,0.22,0.47) + theme));
}
float segment(vec2 p, vec2 a, vec2 b) {
    vec2 direction = b-a;
    float lengthSquared = dot(direction, direction);
    if (lengthSquared == 0.0) return length(p - a);
    return length(p-a-direction*clamp(dot(p-a,direction)/lengthSquared, 0.0, 1.0));
}
float beam(float distance) {
    return .77 * (1.0 - smoothstep(.0008, .0033, distance)) + .24 * exp(-82.0 * distance);
}
vec2 rotated(vec2 p, float angle) {
    return mat2(cos(angle), -sin(angle), sin(angle), cos(angle))*p;
}
float asteroid(vec2 p, float size, float seed) {
    float angle = p.x == 0.0 ? sign(p.y) * 1.570796 : atan(p.y, p.x);
    float sector = floor((angle+3.141593)/.785398);
    float a = sector*.785398-3.141593;
    float b = a+.785398;
    vec2 from = size * (.81+.17*sin(a*3.0+seed)) * vec2(cos(a),sin(a));
    vec2 to = size * (.81+.17*sin(b*3.0+seed)) * vec2(cos(b),sin(b));
    return segment(p, from, to);
}
vec3 tennisScene(vec2 p) {
    float court = min(segment(p, vec2(-.65,-.19), vec2(.65,-.19)),
                      segment(p, vec2(0.0,-.19), vec2(0.0,-.09)));
    float trail = beam(segment(p, ball.zw, ball.xy));
    float head = exp(-95.0 * length(p - ball.xy));
    float flash = impact.z * exp(-47.0 * length(p - impact.xy));
    float glow = .50 * beam(court) + (.23 + .22 * spectrum.x) * trail
               + (.85 + .33 * spectrum.y) * head;
    return vec3(.004,.018,.012) + vec3(.34,1.0,.63) * glow + vec3(.69,1.0,.46) * flash;
}
vec3 asteroidScene(vec2 p) {
    vec3 light = vec3(0.0);
    if (ship.w >= 0.0) {
        vec2 hull = rotated(p - ship.xy, ship.z - 1.570796);
        float edge = min(segment(hull, vec2(0.0,.034), vec2(-.023,-.023)),
                         segment(hull, vec2(0.0,.034), vec2(.023,-.023)));
        edge = min(edge, segment(hull, vec2(-.023,-.023), vec2(0.0,-.013)));
        edge = min(edge, segment(hull, vec2(.023,-.023), vec2(0.0,-.013)));
        float flame = .025 + .039 * ship.w + .023 * spectrum.x;
        float exhaust = min(segment(hull, vec2(-.012,-.020), vec2(0.0,-flame)),
                            segment(hull, vec2(.012,-.020), vec2(0.0,-flame)));
        light += vec3(.75,1.0,.86) * beam(edge) + vec3(.55,1.0,.71) * beam(exhaust) * ship.w;
    }
    for (int i = 0; i < 5; i++) {
        vec4 rock = rocks[i];
        vec2 relative = p - rock.xy;
        if (rock.z > 0.0 && length(relative) < rock.z + .05) {
            float rim = beam(asteroid(rotated(relative, rock.w), rock.z, 1.7 * float(i)));
            light += vec3(.61,.86,.76) * rim * (.71 + .17 * spectrum.y);
        }
    }
    for (int i = 0; i < 4; i++) {
        vec4 shot = shots[i];
        if (dot(shot.zw, shot.zw) > .5) {
            float bolt = beam(segment(p, shot.xy, shot.xy - .022 * shot.zw));
            light += vec3(.86,1.0,.69) * bolt;
        }
    }
    return vec3(.004,.009,.008) + light;
}
void main() {
    vec2 p = (uv - 0.5) * vec2(resolution.x/resolution.y, 1.0);
    float t = time;
    if (scene < 2) {
        float scan = .95 + .05*sin(uv.y*resolution.y*3.141593);
        vec3 light = scene == 1 ? tennisScene(p) : asteroidScene(p);
        gl_FragColor = vec4(min(vec3(1.0), light * scan), 1.0);
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
