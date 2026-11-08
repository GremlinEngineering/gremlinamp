/* Original CPU register traces; pinned BSD-3-Clause MAME discrete circuits. */
const PROFILES={"1":{"game_id":"game-ra-15721","track_id":"game-start","circuit":0,"rom_sha256":"0b8262ea028fa5c0c78ed06174762559226bff3bffed322ef9d613195c9deb5b","events_sha256":"44b54be231fbf8a9193fcdf5a2e0382fda679d0922a05e727c520f89f4a54f0b","start_sample":316270,"audio_frames":160794,"rom_bytes":10240,"event_count":121},"2":{"game_id":"game-ra-15606","track_id":"game-start","circuit":1,"rom_sha256":"0721158138ca4e62b858e405d8623c38b6e1d6d78d72fdb41f703c2163cdc661","events_sha256":"5dd451afd7fb1ae31ca697e547bc465e0caf3fb3e58e8c352e16c31891eb3eb6","start_sample":316271,"audio_frames":160793,"rom_bytes":11264,"event_count":121},"3":{"game_id":"game-ra-15604","track_id":"credit-cue","circuit":1,"rom_sha256":"8cba52e8cd4b69743242675ebf77b8aaf12d0781dbb9653ca477ab6221cbeac3","events_sha256":"623ed42d22aca73062edcb97b83ab181460c51e7bfd52087f55900896255273c","start_sample":242607,"audio_frames":19200,"rom_bytes":32768,"event_count":6},"4":{"game_id":"game-ra-15604","track_id":"stage-start","circuit":1,"rom_sha256":"8cba52e8cd4b69743242675ebf77b8aaf12d0781dbb9653ca477ab6221cbeac3","events_sha256":"347c7b960d3be16bcc01395a1d07dd1c93559326925aa937b929654a0eecb066","start_sample":318608,"audio_frames":284800,"rom_bytes":32768,"event_count":19},"5":{"game_id":"game-ra-12139","track_id":"game-start","circuit":0,"rom_sha256":"d2618302d06493a71fffb3669c12892fe730a200cca992c99944bc6ddcb0d39c","events_sha256":"781ccbb88479d16f11b3cf96cad5b6d9e2a7277b2d8eb3c73219ab6789303a02","start_sample":543466,"audio_frames":151198,"rom_bytes":10240,"event_count":121}};
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
const hash=async b=>hex(new Uint8Array(await crypto.subtle.digest('SHA-256',b)));
let state,ready,generation=0;
async function parse(input,index,rate){
 if(!(input instanceof ArrayBuffer)||input.byteLength>2000000||index!==0||!Number.isInteger(rate)||rate<8000||rate>192000)throw Error('Galaxian input or output bounds');
 let b=new Uint8Array(input);
 if(b[0]===31&&b[1]===139){let count=0,parts=[];const reader=new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();try{for(;;){const{value,done}=await reader.read();if(done)break;count+=value.length;if(count>2000000)throw Error('Galaxian expansion bounds');parts.push(value)}}finally{reader.releaseLock()}b=new Uint8Array(count);let at=0;for(const part of parts){b.set(part,at);at+=part.length}}
 if(b.length<16||String.fromCharCode(...b.subarray(0,4))!=='G0M\x1a')throw Error('Original Galaxian capsule header');
 const v=new DataView(b.buffer,b.byteOffset,b.byteLength),j=v.getUint32(4,true),r=v.getUint32(8,true),n=v.getUint32(12,true);
 if(j<1||j>16384||r<4096||r>65536||n<1||n>50000||16+j+r+n*8!==b.length)throw Error('Galaxian capsule region bounds');
 const p=JSON.parse(new TextDecoder().decode(b.subarray(16,16+j))),profile=PROFILES[p.profile],rom=b.subarray(16+j,16+j+r),events=b.subarray(16+j+r);
 if(!Number.isInteger(p.profile)||!profile||p.version!==1||p.game_id!==profile.game_id||p.track_id!==profile.track_id||p.circuit!==profile.circuit||p.source_rate!==48000||p.source_cpu_clock!==3072000||p.capture_fps!==60||p.MAME_commit!=='40d0f1125befade5edc08ec5699442ca3a229c01'||p.playback_model!=='original_register_trace_and_discrete_circuit'||p.capture_CPU_program_unmodified!==true||r!==profile.rom_bytes||n!==profile.event_count||p.start_sample!==profile.start_sample||p.audio_frames!==profile.audio_frames)throw Error('Unsupported original Galaxian profile');
 if(p.rom_sha256!==profile.rom_sha256||await hash(rom)!==profile.rom_sha256||p.events_sha256!==profile.events_sha256||await hash(events)!==profile.events_sha256)throw Error('Original Galaxian ROM/register identity differs');
 const e=new DataView(events.buffer,events.byteOffset,events.byteLength),list=[];let previous=0;
 for(let i=0;i<n;i++){const at=e.getUint32(i*8,true),port=e.getUint8(i*8+4),value=e.getUint8(i*8+5);if(at<previous||at>p.start_sample+p.audio_frames||port>2||(port&&value>1)||e.getUint16(i*8+6,true)!==0)throw Error('Galaxian register timeline bounds');list.push({at,port,value});previous=at}
 return{...p,events:list,rate};
}
function applyEvents(s){while(s.event<s.events.length&&s.events[s.event].at===s.sourcePosition){const e=s.events[s.event++];if(s.m._gremlin_g0_write(e.port,e.value))throw Error(s.m.UTF8ToString(s.m._gremlin_g0_error()))}}
function sourceBlock(s,limit){
 applyEvents(s);const next=s.event<s.events.length?s.events[s.event].at:Infinity,n=Math.min(4096,limit,next-s.sourcePosition);
 if(!Number.isInteger(n)||n<1)throw Error('Galaxian source timeline did not advance');
 if(s.m._gremlin_g0_render(n))throw Error(s.m.UTF8ToString(s.m._gremlin_g0_error()));
 const out=s.m.HEAPF32.subarray(s.m._gremlin_g0_audio()>>2,(s.m._gremlin_g0_audio()>>2)+n*2).slice();s.sourcePosition+=n;return out;
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);if(typeof GremlinGalaxianMusic==='undefined')importScripts('vendor/g0/gremlin-g0.js');
 const m=await GremlinGalaxianMusic({locateFile:n=>new URL('vendor/g0/'+n,self.location.href).href,print(){},printErr(){}});
 if(m._gremlin_g0_reset(p.circuit))throw Error(m.UTF8ToString(m._gremlin_g0_error()));
 const s={...p,m,event:0,sourcePosition:0,position:0,queue:new Float32Array(0),queueBase:0};
 while(s.sourcePosition<p.start_sample)sourceBlock(s,p.start_sample-s.sourcePosition);
 if(token!==generation){m._gremlin_g0_close();return false}if(state)state.m._gremlin_g0_close();state=s;
 postMessage({type:'ready',decoder:'g0',devices:[{type:p.circuit?'MoonCresta original discrete circuit':'Galaxian original discrete circuit',MAME_commit:p.MAME_commit,ROM_SHA256:p.rom_sha256,original_CPU_register_trace:true,original_circuit_node_math:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function sample(s,index,ch){
 if(index>=s.audio_frames)return 0;
 while(index>=s.queueBase+s.queue.length/2){const remaining=s.start_sample+s.audio_frames-s.sourcePosition;if(remaining<1)throw Error('Galaxian original trace exhausted');const a=sourceBlock(s,remaining),q=new Float32Array(s.queue.length+a.length);q.set(s.queue);q.set(a,s.queue.length);s.queue=q}
 return s.queue[(index-s.queueBase)*2+ch];
}
function render(message){
 const n=message.frames;if(!state||!Number.isInteger(n)||n<1||n>16384)throw Error('Galaxian output bounds');
 const s=state,out=new Float32Array(n*2);
 for(let i=0;i<n;i++,s.position++){const numerator=s.position*48000,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);out[i*2+ch]=a+(b-a)*rem/s.rate}}
 const next=Math.floor(s.position*48000/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,next-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard}
 for(const a of out)if(!Number.isFinite(a))throw Error('Nonfinite Galaxian PCM');postMessage({type:'pcm',pcm:out.buffer},[out.buffer]);
}
onmessage=({data:m})=>{if(m.type==='load'){const token=++generation;ready=load(m,token).catch(e=>{postMessage({type:'error',message:String(e.message||e)});return false})}else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}))};
