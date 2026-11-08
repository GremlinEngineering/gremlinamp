// Decode the timed writes to the SNES sound chip used by Imperial School Views.
const sourceRate = 32000;
const blockSize = 16384;
let ready, runtime, output, step;
let position = 0;
let pending = new Float32Array(0);

async function load(message) {
  if (!Number.isFinite(message.sampleRate) || message.sampleRate < 8000 || message.sampleRate > 192000)
    throw Error('Недопустимая частота вывода');
  if (message.bytes.byteLength > 1024 * 1024 || message.subsong !== 0)
    throw Error('Некорректный поток SNES');
  step = sourceRate / message.sampleRate;
  const response = await fetch(new URL('vendor/theme-snes/theme-snes.wasm', self.location.href));
  if (!response.ok) throw Error('Декодер SNES недоступен');
  const module = await WebAssembly.compile(await response.arrayBuffer());
  importScripts('vendor/theme-snes/theme-snes.js');
  runtime = await GremlinThemeSNES({
    instantiateWasm(imports, receive) {
      const instance = new WebAssembly.Instance(module, imports);
      receive(instance);
      return instance.exports;
    }
  });
  const input = runtime._malloc(message.bytes.byteLength);
  if (!input) throw Error('Недостаточно памяти для SNES');
  try {
    runtime.HEAPU8.set(new Uint8Array(message.bytes), input);
    if (runtime._theme_snes_load(input, message.bytes.byteLength))
      throw Error('Повреждённый поток SNES');
  } finally {
    runtime._free(input);
  }
  output = runtime._malloc(blockSize * 4 + 32);
  if (!output) throw Error('Недостаточно памяти для SNES');
  postMessage({type: 'ready', decoder: 'theme-snes', sampleRate: message.sampleRate});
}

function pull(pcm, start) {
  for (let offset = start; offset < pcm.length; ) {
    const frames = Math.min(blockSize, (pcm.length - offset) / 2);
    const count = runtime._theme_snes_render(output, frames);
    if (count < 0) throw Error('SNES вернул неверное число сэмплов');
    const source = new Int16Array(runtime.HEAP16.buffer, output, count * 2);
    for (let i = 0; i < source.length; ++i) pcm[offset + i] = source[i] / 32768;
    if (count < frames) break;
    offset += count * 2;
  }
}

function render(message) {
  const frames = Math.min(blockSize, Math.max(1, Math.floor(message.frames || 4096)));
  const end = position + frames * step;
  const consumed = Math.floor(end);
  const required = Math.max(consumed, Math.floor(end - step) + 2);
  if (pending.length / 2 < required) {
    const next = new Float32Array(required * 2);
    next.set(pending);
    pull(next, pending.length);
    pending = next;
  }
  const pcm = new Float32Array(frames * 2);
  for (let i = 0; i < frames; ++i) {
    const sample = position + i * step;
    const left = Math.floor(sample) * 2;
    const mix = sample - Math.floor(sample);
    for (let channel = 0; channel < 2; ++channel)
      pcm[i * 2 + channel] = pending[left + channel] * (1 - mix) + pending[left + channel + 2] * mix;
  }
  pending = pending.slice(consumed * 2);
  position = end - consumed;
  postMessage({type: 'pcm', pcm: pcm.buffer}, [pcm.buffer]);
}

function report(error) {
  postMessage({type: 'error', message: String(error?.message || error)});
}

onmessage = ({data: message}) => {
  if (message.type === 'load') {
    ready = load(message).then(() => true, error => { report(error); return false; });
  } else if (message.type === 'render') {
    ready?.then(ok => { if (ok) render(message); }).catch(report);
  }
};
