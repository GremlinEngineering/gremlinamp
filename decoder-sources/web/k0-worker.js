/* Original two AY8910 register streams plus original MAME K005289/PROM. */
const PROFILES={"1":{"version":1,"profile":1,"game_id":"game-ra-12157","track_id":"stage-music","title":"Музыка первого уровня — фрагмент","source_rate":48000,"source_cpu_clock":3579545,"capture_fps":60,"MAME_commit":"40d0f1125befade5edc08ec5699442ca3a229c01","K_clock":3579545,"AY_clock":1789772,"K_gain":0.35,"AY_absolute_VGM_volumes":[51,256],"start_sample":1504800,"audio_frames":528000,"rom_sha256":"f9304a894187ecab2e25f0989d4257cbbcc62b5b47d4e097db281a436314e72e","prom_sha256":"d0980d29146de6354e4a0b56158b50f4dbbe66b51a1ddab40c9f6b43afdb2871","vgm_sha256":"f74bff8aff09aaf65fee0378232c1e106e29818b7e3287c128d64e99eecc1e54","events_sha256":"1ee97aecb2a895af66aad31484e814f960dd38982e7e813c0c87833defe5cc22","rom_bytes":16384,"prom_bytes":512,"vgm_bytes":30668,"event_count":880,"source_trace_sha256":"3f5536b7d116679f3bca383809fdaf3adb24616b8ad74f82d5faa334a75d9428","original_CPU_program_unmodified":true,"original_K_methods_and_PROM_unchanged":true,"filter_C2_off":true,"physical_board_measured":false,"file":"gradius-nemesis-stage.k0z","bytes":16298,"sha256":"735e7c72f4c6331091be2cc98d1d478a6a1f35fc1784f649de538b63a5be7349"}};
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
const hash=async b=>hex(new Uint8Array(await crypto.subtle.digest('SHA-256',b)));
let state,ready,generation=0;
async function parse(input,index,rate){
 if(!(input instanceof ArrayBuffer)||input.byteLength>2000000||index!==0||!Number.isInteger(rate)||rate<8000||rate>192000)throw Error('Konami input or output bounds');
 let b=new Uint8Array(input);
 if(b[0]===31&&b[1]===139){let count=0,parts=[];const reader=new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();try{for(;;){const{value,done}=await reader.read();if(done)break;count+=value.length;if(count>2000000)throw Error('Konami expansion bounds');parts.push(value)}}finally{reader.releaseLock()}b=new Uint8Array(count);let at=0;for(const part of parts){b.set(part,at);at+=part.length}}
 if(b.length<24||String.fromCharCode(...b.subarray(0,4))!=='K0M\x1a')throw Error('Original Konami capsule header');
 const v=new DataView(b.buffer,b.byteOffset,b.byteLength),j=v.getUint32(4,true),r=v.getUint32(8,true),z=v.getUint32(12,true),q=v.getUint32(16,true),n=v.getUint32(20,true);
 if(j<1||j>16384||r<4096||r>65536||z<256||z>1000000||q!==512||n<1||n>50000||24+j+r+z+q+n*8!==b.length)throw Error('Konami capsule region bounds');
 const p=JSON.parse(new TextDecoder().decode(b.subarray(24,24+j))),profile=PROFILES[p.profile];let at=24+j;
 const rom=b.subarray(at,at+=r),vgm=b.subarray(at,at+=z),prom=b.subarray(at,at+=q),events=b.subarray(at);
 if(!Number.isInteger(p.profile)||!profile)throw Error('Unknown Konami profile');
 for(const key of['version','game_id','track_id','source_rate','source_cpu_clock','capture_fps','MAME_commit','K_clock','AY_clock','K_gain','start_sample','audio_frames','rom_bytes','vgm_bytes','prom_bytes','event_count','original_CPU_program_unmodified','original_K_methods_and_PROM_unchanged','filter_C2_off'])if(p[key]!==profile[key])throw Error('Unsupported Konami profile '+key);
 if(r!==profile.rom_bytes||z!==profile.vgm_bytes||q!==profile.prom_bytes||n!==profile.event_count)throw Error('Konami declared region differs');
 for(const[name,bytes]of[['rom',rom],['vgm',vgm],['prom',prom],['events',events]])if(p[name+'_sha256']!==profile[name+'_sha256']||await hash(bytes)!==profile[name+'_sha256'])throw Error('Original Konami '+name+' identity differs');
 const e=new DataView(events.buffer,events.byteOffset,events.byteLength),list=[];let previous=0;
 for(let i=0;i<n;i++){const at=e.getUint32(i*8,true),port=e.getUint8(i*8+4),value=e.getUint16(i*8+6,true);if(at<previous||at>p.start_sample+p.audio_frames||port>5||value>(port<2?4095:port<4?0:255)||e.getUint8(i*8+5)!==0)throw Error('Konami register timeline bounds');list.push({at,port,value});previous=at}
 return{...p,vgm,prom,events:list,rate};
}
function applyEvents(s){while(s.event<s.events.length&&s.events[s.event].at===s.sourcePosition){const e=s.events[s.event++];if(s.m._gremlin_k0_write(e.port,e.value))throw Error(s.m.UTF8ToString(s.m._gremlin_k0_error()))}}
function sourceBlock(s,limit){
 applyEvents(s);const next=s.event<s.events.length?s.events[s.event].at:Infinity,n=Math.min(4096,limit,next-s.sourcePosition);
 if(!Number.isInteger(n)||n<1)throw Error('Konami source timeline did not advance');
 if(s.m._gremlin_k0_render(n))throw Error(s.m.UTF8ToString(s.m._gremlin_k0_error()));s.v._gremlin_vgm_render(s.vout,n);
 const k=s.m.HEAPF32.subarray(s.m._gremlin_k0_audio()>>2,(s.m._gremlin_k0_audio()>>2)+n*2),ay=s.v.HEAPF32.subarray(s.vout>>2,(s.vout>>2)+n*2),out=new Float32Array(n*2);
 for(let i=0;i<out.length;i++)out[i]=ay[i]+k[i]*s.K_gain;
 s.sourcePosition+=n;return out;
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinKonamiMusic==='undefined')importScripts('vendor/k0/gremlin-k0.js');
 if(typeof GremlinLibVGM==='undefined')importScripts('vendor/libvgm/gremlin-libvgm.js');
 const m=await GremlinKonamiMusic({locateFile:n=>new URL('vendor/k0/'+n,self.location.href).href,print(){},printErr(){}});
 const v=await GremlinLibVGM({locateFile:n=>new URL('vendor/libvgm/'+n,self.location.href).href,print(){},printErr(){}});
 let ptr=m._malloc(p.prom.length);if(!ptr)throw Error('Konami PROM allocation');let fail;try{m.HEAPU8.set(p.prom,ptr);fail=m._gremlin_k0_reset(ptr,p.prom.length)}finally{m._free(ptr)}if(fail)throw Error(m.UTF8ToString(m._gremlin_k0_error()));
 ptr=v._malloc(p.vgm.length);if(!ptr)throw Error('Konami AY allocation');try{v.HEAPU8.set(p.vgm,ptr);fail=v._gremlin_vgm_load(ptr,p.vgm.length,48000)}finally{v._free(ptr)}if(fail)throw Error(v.UTF8ToString(v._gremlin_vgm_error()));
 const vout=v._malloc(4096*8);if(!vout)throw Error('Konami output allocation');
 const s={...p,m,v,vout,event:0,sourcePosition:0,position:0,queue:new Float32Array(0),queueBase:0};
 while(s.sourcePosition<p.start_sample)sourceBlock(s,p.start_sample-s.sourcePosition);
 if(token!==generation){m._gremlin_k0_close();v._gremlin_vgm_close();v._free(vout);return false}
 if(state){state.m._gremlin_k0_close();state.v._gremlin_vgm_close();state.v._free(state.vout)}state=s;
 const devices=JSON.parse(v.UTF8ToString(v._gremlin_vgm_devices()));devices.push({type:'original MAME K005289',clock_hz:p.K_clock,MAME_commit:p.MAME_commit,original_PROM_SHA256:p.prom_sha256,original_CPU_register_trace:true,original_counter_methods:true,source_resampling:'chip-clock arithmetic mean to48kHz',physical_board_measured:false});
 postMessage({type:'ready',decoder:'k0',devices});return true;
}
function sample(s,index,ch){
 if(index>=s.audio_frames)return 0;
 while(index>=s.queueBase+s.queue.length/2){const remaining=s.start_sample+s.audio_frames-s.sourcePosition;if(remaining<1)throw Error('Konami original trace exhausted');const a=sourceBlock(s,remaining),q=new Float32Array(s.queue.length+a.length);q.set(s.queue);q.set(a,s.queue.length);s.queue=q}
 return s.queue[(index-s.queueBase)*2+ch];
}
function render(message){
 const n=message.frames;if(!state||!Number.isInteger(n)||n<1||n>16384)throw Error('Konami output bounds');const s=state,out=new Float32Array(n*2);
 for(let i=0;i<n;i++,s.position++){const numerator=s.position*48000,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);out[i*2+ch]=a+(b-a)*rem/s.rate}}
 const next=Math.floor(s.position*48000/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,next-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard}
 for(const a of out)if(!Number.isFinite(a))throw Error('Nonfinite Konami PCM');postMessage({type:'pcm',pcm:out.buffer},[out.buffer]);
}
onmessage=({data:m})=>{if(m.type==='load'){const token=++generation;ready=load(m,token).catch(e=>{postMessage({type:'error',message:String(e.message||e)});return false})}else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}))};
