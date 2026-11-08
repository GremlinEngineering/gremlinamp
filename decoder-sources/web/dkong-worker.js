// Original Nintendo MB8884 firmware and original discrete output circuit.
let ready,runtime,output=0;
const MAX_FRAMES=16384,MAX_INPUT=1000000;
async function load(message){
 if(!Number.isFinite(message.sampleRate)||message.sampleRate<8000||message.sampleRate>192000)throw Error('Недопустимая частота вывода');
 if((message.subsong??0)!==0)throw Error('DKM содержит одну выбранную мелодию');
 const bytes=new Uint8Array(message.bytes);if(bytes.length>MAX_INPUT)throw Error('Файл DKM слишком велик');
 if(!runtime){
  const response=await fetch(new URL('vendor/dkong/gremlin-dkong.wasm',self.location.href));if(!response.ok)throw Error('Декодер Donkey Kong недоступен ('+response.status+')');
  const module=await WebAssembly.compile(await response.arrayBuffer());importScripts('vendor/dkong/gremlin-dkong.js');
  runtime=await GremlinDkongMusic({instantiateWasm(imports,receive){const instance=new WebAssembly.Instance(module,imports);receive(instance);return instance.exports;},locateFile:name=>new URL('vendor/dkong/'+name,self.location.href).href,print(){},printErr(){}});
 }
 if(output){runtime._free(output);output=0;}
 const input=runtime._malloc(bytes.byteLength);if(!input)throw Error('Недостаточно памяти для DKM');
 let status;try{runtime.HEAPU8.set(bytes,input);status=runtime._gremlin_dk_load(input,bytes.byteLength,Math.round(message.sampleRate),0);}finally{runtime._free(input);}
 if(status)throw Error('Donkey Kong: '+runtime.UTF8ToString(runtime._gremlin_dk_error()));
 output=runtime._malloc(MAX_FRAMES*2*4);if(!output)throw Error('Недостаточно памяти для звукового буфера');
 postMessage({type:'ready',decoder:'dkong',devices:JSON.parse(runtime.UTF8ToString(runtime._gremlin_dk_devices())),sampleRate:Math.round(message.sampleRate)});return true;
}
function render(message){
 const frames=message.frames??4096;if(!Number.isInteger(frames)||frames<1||frames>MAX_FRAMES)throw Error('Недопустимый размер звукового блока');
 if(runtime._gremlin_dk_render(output,frames)!==frames)throw Error(runtime.UTF8ToString(runtime._gremlin_dk_error())||'Ошибка звукового драйвера Donkey Kong');
 const pcm=new Float32Array(runtime.HEAPF32.buffer,output,frames*2).slice();for(const value of pcm)if(!Number.isFinite(value))throw Error('Donkey Kong вернул некорректный PCM');
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(error){postMessage({type:'error',message:String(error?.message||error)});}
self.addEventListener('unhandledrejection',event=>{event.preventDefault();report(event.reason);});
onmessage=({data:message})=>{if(message.type==='load')ready=load(message).catch(error=>{report(error);return false;});else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(report);};
