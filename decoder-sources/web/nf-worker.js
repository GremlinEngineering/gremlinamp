/* GPL-2.0-or-later. Original NES/FDS full-machine music capsules.
   Executes pinned FCEUmm, original ROM/BIOS and ordinary input timelines. */
let state,ready,generation=0;
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
const hash=async b=>hex(new Uint8Array(await crypto.subtle.digest('SHA-256',b)));
async function parse(input,index,rate){
 if(!(input instanceof ArrayBuffer)||input.byteLength>20000000)throw Error('NES capsule input bounds');
 let b=new Uint8Array(input);
 if(b[0]===31&&b[1]===139){let n=0,parts=[];const reader=new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();try{for(;;){const{value,done}=await reader.read();if(done)break;n+=value.length;if(n>20000000)throw Error('NES capsule expansion bounds');parts.push(value)}}finally{reader.releaseLock()}b=new Uint8Array(n);let at=0;for(const p of parts){b.set(p,at);at+=p.length}}
 if(b.length<16||String.fromCharCode(...b.subarray(0,4))!=='NFM\x1a')throw Error('Original NES capsule header');
 const v=new DataView(b.buffer,b.byteOffset,b.byteLength),j=v.getUint32(4,true),r=v.getUint32(8,true),z=v.getUint32(12,true);
 if(!j||j>65536||r<8192||r>16777216||16+j+r+z!==b.length)throw Error('NES capsule region bounds');
 const p=JSON.parse(new TextDecoder().decode(b.subarray(16,16+j))),rom=b.subarray(16+j,16+j+r),bios=b.subarray(16+j+r);
 const profiles=Object.assign({1:{game:'game-ra-38392',rom:'ffe10099c0075e330fee0f04709e9c9b1ad78526633d9587746b7083ededc63a',bytes:131000,bios:'99c18490ed9002d9c6d999b9d8d15be5c051bdfa7cc7e73318053c9a994b0178',fds:true}},{"2":{"game":"game-ra-29640","rom":"8e23aec1a496521215f5d2246ce13789ee6d2d8b5698ab3b2131192df0f80324","bytes":40976,"bios":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","fds":false,"system":"vs"},"3":{"game":"game-ra-29619","rom":"1b2d38c9e41b4dc5c272f1cf2952810deb8d287360d470eee502cc854831419a","bytes":49168,"bios":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","fds":false,"system":"vs"},"4":{"game":"game-ra-5098","rom":"ec453e7e3cc1d49d11d5860ced7f9e1235e2c7d33927bd6073b631754dc08dbb","bytes":40976,"bios":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","fds":false,"system":"nes"}});
 const profile=profiles[p.profile];
 if(!profile||p.version!==1||p.game_id!==profile.game||r!==profile.bytes||p.fds!==profile.fds||z!==(profile.fds?8192:0)||p.core_commit!=='e0f3086a8104f8fbbe49a2615b10401504a9533f'||p.source_rate!==48000||Math.abs(p.source_fps-60.099826520671044)>1e-9)throw Error('Unsupported original NES profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(index)||index<0||!Array.isArray(p.tracks)||!p.tracks.length||p.tracks.length>64||index>=p.tracks.length)throw Error('NES rate or selector bounds');
 if(p.rom_sha256!==profile.rom||await hash(rom)!==profile.rom||p.bios_sha256!==profile.bios||await hash(bios)!==profile.bios)throw Error('Original NES ROM/BIOS identity differs');
 for(const t of p.tracks){for(const key of['prefix','play']){const events=t[key];if(!Array.isArray(events)||!events.length||events.length>1024)throw Error('NES input script bounds');let frames=0;for(const e of events){if(!Number.isInteger(e.frames)||e.frames<1||e.frames>60000||!Number.isInteger(e.mask)||e.mask<0||e.mask>65535)throw Error('NES original input event bounds');frames+=e.frames}if(frames>60000)throw Error('NES input duration bounds');if(key==='play'&&(!Number.isInteger(t.audio_frames)||t.audio_frames<frames*500||t.audio_frames>frames*2048))throw Error('NES audio duration bounds')}}
 return{...p,...p.tracks[index],rom,bios,rate,index,hardware_system:profile.system??(profile.fds?'fds':'vs')};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);if(typeof GremlinNESMusic==='undefined')importScripts('vendor/nf/gremlin-nf.js');
 const m=await GremlinNESMusic({locateFile:n=>new URL('vendor/nf/'+n,self.location.href).href,print(){},printErr(){}});if(p.bios.length)m.FS.writeFile('/disksys.rom',p.bios);
 const ptr=m._malloc(p.rom.length);if(!ptr)throw Error('NES allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_nes_load(ptr,p.rom.length,p.fds?1:0);m._free(ptr);if(failed)throw Error(m.UTF8ToString(m._gremlin_nes_error()));
 if(m._gremlin_nes_source_rate()!==p.source_rate||Math.abs(m._gremlin_nes_source_fps()-p.source_fps)>1e-9)throw Error('Original NES clock profile differs');m._gremlin_nes_capture_video(0);
 for(const e of p.prefix)for(let i=0;i<e.frames;i++)if(m._gremlin_nes_step(e.mask))throw Error('NES original prefix failed');
 if(token!==generation){m._gremlin_nes_close();return false}if(state)state.m._gremlin_nes_close();state={m,...p,position:0,queue:new Float32Array(0),queueBase:0,event:0,eventFrames:0};
 postMessage({type:'ready',decoder:'nf',devices:[{type:p.fds?'original_famicom_disk_system':p.hardware_system==='nes'?'original_nes_system':'original_vs_system',core:'FCEUmm',commit:p.core_commit,ROM_SHA256:p.rom_sha256,BIOS_SHA256:p.bios_sha256,original_program_and_notes:true,ordinary_inputs_only:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){const e=s.play[s.event];if(!e)throw Error('NES source window exhausted');if(s.m._gremlin_nes_step(e.mask))throw Error('NES original frame failed');const n=s.m._gremlin_nes_audio_frames();if(n<1||n>2048)throw Error('NES source audio bounds');const out=new Float32Array(s.queue.length+n*2);out.set(s.queue);const at=s.m._gremlin_nes_audio()>>1;for(let i=0;i<n*2;i++)out[s.queue.length+i]=s.m.HEAP16[at+i]/32768;s.queue=out;if(++s.eventFrames===e.frames){s.event++;s.eventFrames=0}}
function sample(s,index,ch){if(index>=s.audio_frames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch]}
function render(message){const n=message.frames;if(!state||!Number.isInteger(n)||n<1||n>16384)throw Error('NES output bounds');const s=state,out=new Float32Array(n*2);for(let i=0;i<n;i++,s.position++){const numerator=s.position*48000,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);out[2*i+ch]=a+(b-a)*rem/s.rate}}const next=Math.floor(s.position*48000/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,next-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard}postMessage({type:'pcm',pcm:out.buffer},[out.buffer])}
onmessage=({data:m})=>{if(m.type==='load'){const token=++generation;ready=load(m,token).catch(e=>{postMessage({type:'error',message:String(e.message||e)});return false})}else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}))};
