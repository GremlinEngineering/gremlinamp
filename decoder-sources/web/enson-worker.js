// Original Street Fighter The Movie MC6809E and ES5506 execution.
let ready,runtime,output=0;
const MAX_FRAMES=16384,MAX_INPUT=16000000;
async function unpack(input){
 let bytes=new Uint8Array(input);if(bytes.length>MAX_INPUT)throw Error('Файл ES5506 слишком велик');
 if(bytes[0]===31&&bytes[1]===139){
  const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();const chunks=[];let total=0;
  try{for(;;){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>MAX_INPUT)throw Error('Распакованный ES5506 превышает предел');chunks.push(value);}}finally{await reader.cancel();}
  bytes=new Uint8Array(total);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
 }
 return bytes;
}
async function load(message){
 if(!Number.isFinite(message.sampleRate)||message.sampleRate<8000||message.sampleRate>192000)throw Error('Недопустимая частота вывода');
 const cue=message.subsong??0;if(!Number.isInteger(cue)||cue<0||cue>255)throw Error('Недопустимый музыкальный селектор ES5506');
 const bytes=await unpack(message.bytes);
 if(!runtime){
  const response=await fetch(new URL('vendor/enson/gremlin-e6m.wasm',self.location.href));if(!response.ok)throw Error('Декодер ES5506 недоступен ('+response.status+')');
  const module=await WebAssembly.compile(await response.arrayBuffer());importScripts('vendor/enson/gremlin-e6m.js');
  runtime=await GremlinEnsoniqMusic({instantiateWasm(imports,receive){const instance=new WebAssembly.Instance(module,imports);receive(instance);return instance.exports;},locateFile:name=>new URL('vendor/enson/'+name,self.location.href).href,print(){},printErr(){}});
 }
 if(output){runtime._free(output);output=0;}
 const input=runtime._malloc(bytes.byteLength);if(!input)throw Error('Недостаточно памяти для ES5506');
 let status;try{runtime.HEAPU8.set(bytes,input);status=runtime._gremlin_e6m_load(input,bytes.byteLength,Math.round(message.sampleRate),cue);}finally{runtime._free(input);}
 if(status)throw Error('ES5506: '+runtime.UTF8ToString(runtime._gremlin_e6m_error()));
 output=runtime._malloc(MAX_FRAMES*2*4);if(!output)throw Error('Недостаточно памяти для звукового буфера');
 postMessage({type:'ready',decoder:'enson',devices:JSON.parse(runtime.UTF8ToString(runtime._gremlin_e6m_devices())),sampleRate:Math.round(message.sampleRate)});return true;
}
function render(message){
 const frames=Math.min(MAX_FRAMES,Math.max(1,Math.floor(message.frames||4096)));
 if(runtime._gremlin_e6m_render(output,frames)!==frames)throw Error(runtime.UTF8ToString(runtime._gremlin_e6m_error())||'Ошибка ES5506');
 const pcm=new Float32Array(runtime.HEAPF32.buffer,output,frames*2).slice();for(const value of pcm)if(!Number.isFinite(value))throw Error('ES5506 вернул некорректный PCM');
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(error){postMessage({type:'error',message:String(error?.message||error)});}
self.addEventListener('unhandledrejection',event=>{event.preventDefault();report(event.reason);});
onmessage=({data:message})=>{if(message.type==='load')ready=load(message).catch(error=>{report(error);return false;});else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(report);};
