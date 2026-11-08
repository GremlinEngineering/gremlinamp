// Current libvgm, isolated in a dedicated worker per song.
let ready, runtime, output=0;
const MAX_FRAMES=16384;
async function load(message){
  if(!Number.isFinite(message.sampleRate)||message.sampleRate<8000||message.sampleRate>192000)throw Error('Недопустимая частота вывода');
  if((message.subsong||0)!==0)throw Error('VGM не содержит выбираемых subsong');
  const prefix=message.native_preroll_vgm_samples??0;
  if(!Number.isInteger(prefix)||prefix<0||prefix>7938000)throw Error('Недопустимая начальная позиция VGM');
  let bytes=new Uint8Array(message.bytes);
  if(bytes[0]===0x1f&&bytes[1]===0x8b){
    if(typeof DecompressionStream!=='function')throw Error('Браузер не поддерживает распаковку VGZ');
    const stream=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    bytes=new Uint8Array(await new Response(stream).arrayBuffer());
  }
  const response=await fetch(new URL('vendor/libvgm/gremlin-libvgm.wasm',self.location.href));
  if(!response.ok)throw Error('Декодер VGM недоступен ('+response.status+')');
  const wasmBinary=await response.arrayBuffer();
  const wasmModule=await WebAssembly.compile(wasmBinary);
  importScripts('vendor/libvgm/gremlin-libvgm.js');
  runtime=await GremlinLibVGM({
    instantiateWasm(imports,receive){
      const instance=new WebAssembly.Instance(wasmModule,imports);
      receive(instance);
      return instance.exports;
    },
    locateFile:name=>new URL('vendor/libvgm/'+name,self.location.href).href,
    print(){},printErr(){}
  });
  const input=runtime._malloc(bytes.byteLength);
  if(!input)throw Error('Недостаточно памяти для VGM');
  let status;
  try {
    runtime.HEAPU8.set(bytes,input);
    status=runtime._gremlin_vgm_load(input,bytes.byteLength,Math.round(message.sampleRate));
  } finally {runtime._free(input);}
  if(status)throw Error('VGM: '+runtime.UTF8ToString(runtime._gremlin_vgm_error()));
  output=runtime._malloc(MAX_FRAMES*2*4);
  if(!output)throw Error('Недостаточно памяти для звукового буфера');
  // Run the original recorded prefix to preserve chip state and envelopes.
  // This omits setup/other contexts from the listening window without a reset.
  let discard=Math.round(prefix*Math.round(message.sampleRate)/44100);
  while(discard>0){const frames=Math.min(MAX_FRAMES,discard);runtime._gremlin_vgm_render(output,frames);discard-=frames;}
  const devices=JSON.parse(runtime.UTF8ToString(runtime._gremlin_vgm_devices()));
  postMessage({type:'ready',decoder:'libvgm',devices,sampleRate:Math.round(message.sampleRate)});
  return true;
}
function render(message){
  const frames=Math.min(MAX_FRAMES,Math.max(1,Math.floor(message.frames||4096)));
  runtime._gremlin_vgm_render(output,frames);
  // Read the current heap after render: WASM memory may have grown.
  const pcm=new Float32Array(runtime.HEAPF32.buffer,output,frames*2).slice();
  for(const value of pcm)if(!Number.isFinite(value))throw Error('VGM вернул некорректный PCM');
  postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(error){postMessage({type:'error',message:String(error?.message||error)});}
self.addEventListener('unhandledrejection',event=>{event.preventDefault();report(event.reason);});
onmessage=({data:message})=>{
  if(message.type==='load')ready=load(message).catch(error=>{report(error);return false;});
  else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(report);
};
