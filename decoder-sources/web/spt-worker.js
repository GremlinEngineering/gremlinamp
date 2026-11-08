// Original S-SMP/S-DSP state and timed host port events; isolated per song.
let ready,runtime,output,rate=48000,position=0,pending=new Float32Array(0);
const MAX=16384,SOURCE_RATE=32040;
async function load(m){
 rate=m.sampleRate;if(!Number.isFinite(rate)||rate<8000||rate>192000)throw Error('Недопустимая частота вывода');
 if((m.subsong||0)!==0)throw Error('SPT содержит один музыкальный поток');
 let bytes=new Uint8Array(m.bytes);
 if(bytes[0]===31&&bytes[1]===139){const stream=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));bytes=new Uint8Array(await new Response(stream).arrayBuffer());}
 if(bytes.length>16777216)throw Error('Слишком большой SPT');
 const response=await fetch(new URL('vendor/spt/gremlin-spt.wasm',self.location.href));if(!response.ok)throw Error('Декодер SPT недоступен');
 const module=await WebAssembly.compile(await response.arrayBuffer());importScripts('vendor/spt/gremlin-spt.js');
 runtime=await GremlinSPT({instantiateWasm(imports,receive){const instance=new WebAssembly.Instance(module,imports);receive(instance);return instance.exports;},print(){},printErr(){}});
 const ptr=runtime._malloc(bytes.length);if(!ptr)throw Error('Недостаточно памяти для SPT');let status;
 try{runtime.HEAPU8.set(bytes,ptr);status=runtime._spt_load(ptr,bytes.length);}finally{runtime._free(ptr);}
 if(status)throw Error('Неподдерживаемый или повреждённый SPT ('+status+')');output=runtime._malloc(MAX*4);if(!output)throw Error('Недостаточно памяти для SPT');
 postMessage({type:'ready',decoder:'spt',sampleRate:rate});return true;
}
function pull(frames){let result=new Float32Array(frames*2),offset=0;while(offset<frames){let n=Math.min(MAX,frames-offset),got=runtime._spt_render(output,n),a=new Int16Array(runtime.HEAP16.buffer,output,got*2);for(let i=0;i<a.length;i++)result[offset*2+i]=a[i]/32768;offset+=n;if(got<n)break;}return result;}
function render(m){const frames=Math.min(MAX,Math.max(1,Math.floor(m.frames||4096))),ratio=SOURCE_RATE/rate,required=Math.floor(position+(frames-1)*ratio)+2;
 if(pending.length/2<required){const next=new Float32Array(required*2);next.set(pending);next.set(pull(required-pending.length/2),pending.length);pending=next;}
 const pcm=new Float32Array(frames*2);for(let i=0;i<frames;i++){let p=position+i*ratio,a=Math.floor(p),mix=p-a;for(let ch=0;ch<2;ch++)pcm[i*2+ch]=pending[a*2+ch]*(1-mix)+pending[(a+1)*2+ch]*mix;}
 position+=frames*ratio;let consumed=Math.floor(position);pending=pending.slice(consumed*2);position-=consumed;postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(e){postMessage({type:'error',message:String(e?.message||e)});}
self.addEventListener('unhandledrejection',e=>{e.preventDefault();report(e.reason);});
onmessage=({data:m})=>{if(m.type==='load')ready=load(m).catch(e=>{report(e);return false;});else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m);}).catch(report);};
