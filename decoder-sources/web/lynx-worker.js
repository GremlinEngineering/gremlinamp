// Pinned original Lynx sound-driver state, isolated worker per song.
let ready,runtime,output=0;
const MAX_FRAMES=16384;
async function load(message){
 if(!Number.isFinite(message.sampleRate)||message.sampleRate<8000||message.sampleRate>192000)throw Error('Недопустимая частота вывода');
 if((message.subsong||0)!==0)throw Error('LSM содержит одну выбранную партитуру');
 let bytes=new Uint8Array(message.bytes);
 if(bytes[0]===31&&bytes[1]===139){
  const stream=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));bytes=new Uint8Array(await new Response(stream).arrayBuffer());
 }
 const response=await fetch(new URL('vendor/lynx/gremlin-lsm.wasm',self.location.href));if(!response.ok)throw Error('Декодер Lynx недоступен ('+response.status+')');
 const module=await WebAssembly.compile(await response.arrayBuffer());importScripts('vendor/lynx/gremlin-lsm.js');
 runtime=await GremlinLynxMusic({instantiateWasm(imports,receive){const instance=new WebAssembly.Instance(module,imports);receive(instance);return instance.exports;},locateFile:name=>new URL('vendor/lynx/'+name,self.location.href).href,print(){},printErr(){}});
 const input=runtime._malloc(bytes.byteLength);if(!input)throw Error('Недостаточно памяти для Lynx');
 let status;try{runtime.HEAPU8.set(bytes,input);status=runtime._gremlin_lsm_load(input,bytes.byteLength,Math.round(message.sampleRate));}finally{runtime._free(input);}
 if(status)throw Error('Lynx: '+runtime.UTF8ToString(runtime._gremlin_lsm_error()));
 output=runtime._malloc(MAX_FRAMES*2*4);if(!output)throw Error('Недостаточно памяти для звукового буфера');
 postMessage({type:'ready',decoder:'lynx',devices:JSON.parse(runtime.UTF8ToString(runtime._gremlin_lsm_devices())),sampleRate:Math.round(message.sampleRate)});return true;
}
function render(message){
 const frames=Math.min(MAX_FRAMES,Math.max(1,Math.floor(message.frames||4096)));
 if(runtime._gremlin_lsm_render(output,frames)!==frames)throw Error(runtime.UTF8ToString(runtime._gremlin_lsm_error())||'Ошибка исходного драйвера Lynx');
 const pcm=new Float32Array(runtime.HEAPF32.buffer,output,frames*2).slice();for(const value of pcm)if(!Number.isFinite(value))throw Error('Lynx вернул некорректный PCM');
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(error){postMessage({type:'error',message:String(error?.message||error)});}
self.addEventListener('unhandledrejection',event=>{event.preventDefault();report(event.reason);});
onmessage=({data:message})=>{if(message.type==='load')ready=load(message).catch(error=>{report(error);return false;});else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(report);};
