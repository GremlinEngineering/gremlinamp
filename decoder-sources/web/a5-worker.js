/* GPL-2.0-or-later. Original Atari5200 full-machine music capsules.
   Executes pinned A5200, original ROM/BIOS and ordinary input timelines. */
let state,ready,generation=0;
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
const hash=async b=>hex(new Uint8Array(await crypto.subtle.digest('SHA-256',b)));
async function parse(input,index,rate){
 if(!(input instanceof ArrayBuffer)||input.byteLength>20000000)throw Error('Atari5200 capsule input bounds');
 let b=new Uint8Array(input);
 if(b[0]===31&&b[1]===139){let n=0,parts=[];const reader=new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();try{for(;;){const{value,done}=await reader.read();if(done)break;n+=value.length;if(n>20000000)throw Error('Atari5200 capsule expansion bounds');parts.push(value)}}finally{reader.releaseLock()}b=new Uint8Array(n);let at=0;for(const p of parts){b.set(p,at);at+=p.length}}
 if(b.length<16||String.fromCharCode(...b.subarray(0,4))!=='A5M\x1a')throw Error('Original Atari5200 capsule header');
 const v=new DataView(b.buffer,b.byteOffset,b.byteLength),j=v.getUint32(4,true),r=v.getUint32(8,true),z=v.getUint32(12,true);
 if(!j||j>65536||r<4096||r>262144||16+j+r+z!==b.length)throw Error('Atari5200 capsule region bounds');
 const p=JSON.parse(new TextDecoder().decode(b.subarray(16,16+j))),rom=b.subarray(16+j,16+j+r),bios=b.subarray(16+j+r);
 const profiles={"1":{"game":"game-ra-44242","rom":"469209803f3c2d9119be2ef4347d25dcb94e6c30930e107cd93a5b94bde16b8c","bytes":8192,"bios":"06b250f18983d058c0f156ce7ee88ae48b6eaf11e6f10f21dccf6ac7ffb6a6af"},"2":{"game":"game-ra-8930","rom":"5097b4763eefd8650781698e36b68bc0c9ae0863916312e6fa4364e4da104080","bytes":16384,"bios":"06b250f18983d058c0f156ce7ee88ae48b6eaf11e6f10f21dccf6ac7ffb6a6af"}};
 const profile=profiles[p.profile];
 if(!Number.isInteger(p.profile)||!profile||p.version!==1||p.game_id!==profile.game||r!==profile.bytes||z!==2048||p.core_commit!=='40c6f2f1ad4a3145b328d5baaf010fae6c7e752b'||p.source_rate!==44100||!Number.isFinite(p.source_fps)||Math.abs(p.source_fps-60)>1e-9)throw Error('Unsupported original Atari5200 profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(index)||index<0||!Array.isArray(p.tracks)||!p.tracks.length||p.tracks.length>64||index>=p.tracks.length)throw Error('Atari5200 rate or selector bounds');
 if(p.rom_sha256!==profile.rom||await hash(rom)!==profile.rom||p.bios_sha256!==profile.bios||await hash(bios)!==profile.bios)throw Error('Original Atari5200 ROM/BIOS identity differs');
 for(const t of p.tracks){for(const key of['prefix','play']){const events=t[key];if(!Array.isArray(events)||!events.length||events.length>1024)throw Error('Atari5200 input script bounds');let frames=0;for(const e of events){if(!Number.isInteger(e.frames)||e.frames<1||e.frames>60000||!Number.isInteger(e.mask)||e.mask<0||e.mask>65535)throw Error('Atari5200 original input event bounds');frames+=e.frames}if(frames>60000)throw Error('Atari5200 input duration bounds');if(key==='play'&&(!Number.isInteger(t.audio_frames)||t.audio_frames<frames*500||t.audio_frames>frames*2048))throw Error('Atari5200 audio duration bounds')}}
 return{...p,...p.tracks[index],rom,bios,rate,index};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);if(typeof GremlinAtari5200Music==='undefined')importScripts('vendor/a5/gremlin-a5.js');
 const m=await GremlinAtari5200Music({locateFile:n=>new URL('vendor/a5/'+n,self.location.href).href,print(){},printErr(){}});if(p.bios.length)m.FS.writeFile('/5200.rom',p.bios);
 const ptr=m._malloc(p.rom.length);if(!ptr)throw Error('Atari5200 allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_a5_load(ptr,p.rom.length);m._free(ptr);if(failed)throw Error(m.UTF8ToString(m._gremlin_a5_error()));
 if(m._gremlin_a5_source_rate()!==p.source_rate||Math.abs(m._gremlin_a5_source_fps()-p.source_fps)>1e-9)throw Error('Original Atari5200 clock profile differs');m._gremlin_a5_capture_video(0);
 for(const e of p.prefix)for(let i=0;i<e.frames;i++)if(m._gremlin_a5_step(e.mask))throw Error('Atari5200 original prefix failed');
 if(token!==generation){m._gremlin_a5_close();return false}if(state)state.m._gremlin_a5_close();state={m,...p,position:0,queue:new Float32Array(0),queueBase:0,event:0,eventFrames:0};
 postMessage({type:'ready',decoder:'a5',devices:[{type:'original_atari5200_pokey',core:'A5200',commit:p.core_commit,ROM_SHA256:p.rom_sha256,BIOS_SHA256:p.bios_sha256,original_program_and_notes:true,ordinary_inputs_only:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){const e=s.play[s.event];if(!e)throw Error('Atari5200 source window exhausted');if(s.m._gremlin_a5_step(e.mask))throw Error('Atari5200 original frame failed');const n=s.m._gremlin_a5_audio_frames();if(n<1||n>2048)throw Error('Atari5200 source audio bounds');const out=new Float32Array(s.queue.length+n*2);out.set(s.queue);const at=s.m._gremlin_a5_audio()>>1;for(let i=0;i<n*2;i++)out[s.queue.length+i]=s.m.HEAP16[at+i]/32768;s.queue=out;if(++s.eventFrames===e.frames){s.event++;s.eventFrames=0}}
function sample(s,index,ch){if(index>=s.audio_frames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch]}
function render(message){const n=message.frames;if(!state||!Number.isInteger(n)||n<1||n>16384)throw Error('Atari5200 output bounds');const s=state,out=new Float32Array(n*2);for(let i=0;i<n;i++,s.position++){const numerator=s.position*44100,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);out[2*i+ch]=a+(b-a)*rem/s.rate}}const next=Math.floor(s.position*44100/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,next-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard}postMessage({type:'pcm',pcm:out.buffer},[out.buffer])}
onmessage=({data:m})=>{if(m.type==='load'){const token=++generation;ready=load(m,token).catch(e=>{postMessage({type:'error',message:String(e.message||e)});return false})}else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}))};
