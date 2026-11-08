/* The decoder runs outside the UI thread. Original music bytes stay native. */
let emu = 0, output = 0, outputFrames = 0;
let readyResolve, readyReject;
const ready = new Promise((resolve, reject) => { readyResolve=resolve; readyReject=reject; });
var Module = { noInitialRun: true, print() {}, printErr() {},
  locateFile: name => new URL('./vendor/'+name, self.location.href).href,
  onRuntimeInitialized: () => readyResolve(),
  onAbort: reason => readyReject(new Error(String(reason)))
};
importScripts('./vendor/libgme.js');

function checked(code) {
  if (code) throw new Error(Module.UTF8ToString(code) || 'Music decoder error');
}
function dispose() {
  if (emu) Module._gme_delete(emu);
  if (output) Module._free(output);
  emu = output = outputFrames = 0;
}
onmessage = async ({data: m}) => {
  try {
    await ready;
    if (m.type === 'load') {
      dispose();
      const input = new Uint8Array(m.bytes);
      const ptr = Module._malloc(input.length), ref = Module._malloc(4);
      try {
        Module.HEAPU8.set(input, ptr);
        checked(Module._gme_open_data(ptr, input.length, ref, m.sampleRate));
        emu = Module.HEAP32[ref >> 2];
      } finally { Module._free(ptr); Module._free(ref); }
      if (Module._gme_clear_playlist) Module._gme_clear_playlist(emu);
      Module._gme_ignore_silence(emu, 1);
      checked(Module._gme_start_track(emu, m.subsong || 0));
      postMessage({type:'ready', tracks:Module._gme_track_count(emu)});
    } else if (m.type === 'render') {
      if (!emu) throw new Error('No music file is loaded');
      const frames = Math.min(m.frames || 4096, 16384);
      if (outputFrames < frames) {
        if (output) Module._free(output);
        output = Module._malloc(frames * 4); outputFrames = frames;
      }
      checked(Module._gme_play(emu, frames * 2, output));
      const pcm = new Float32Array(frames * 2);
      for (let i=0; i<pcm.length; i++) pcm[i] = Module.HEAP16[(output >> 1) + i] / 32768;
      postMessage({type:'pcm', pcm:pcm.buffer}, [pcm.buffer]);
    } else if (m.type === 'dispose') { dispose(); }
  } catch (e) { postMessage({type:'error', message:String(e.message || e)}); }
};
