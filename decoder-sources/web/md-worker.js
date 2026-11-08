/* GPL-2.0-or-later. Original Mega Drive/32X music capsule transport.
   Full pinned original machine executes original game/sound hardware; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>8000000)fail('Mega Drive/32X capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>8000000)fail('Mega Drive/32X capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==77||b[1]!==68||b[2]!==77||b[3]!==26)fail('Invalid MDM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,4,5,6].includes(profile)||u(12)!==44100||u(16)!==60||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original Mega Drive/32X profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>64)fail('Mega Drive/32X rate or subsong bounds');
 if(romBytes!==({1:4194304,2:4194304,3:4194304,4:1048576,5:3407872,6:5242880})[profile]||scriptsAt!==128+tracks*64||scriptBytes>262144||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('Mega Drive/32X capsule region bounds');
 if(hex(b.subarray(96,116))!=='1890c2932234c9d30f4cd3851d02228baae8f09e'||b.subarray(116,128).some(x=>x))fail('Mega Drive/32X source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('Mega Drive/32X original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected={"1": "2cb563914f6187f0f97588c46ccb112c7de2194b346a33992f5c69ca7611a65b", "2": "788fd87e5e2478eb37226b6231b7e1a7e7c3ba45bce020748d77ef2951a20feb", "3": "75dd7e0c182a759a7e5beabbca7581ff11336c416fa4711804cf6f148fbc45c0", "4": "3c92fa1cd7d8ad869122f8729d7f3d1da9557975b4b6b81dffd6085916a20e33", "5": "3da827d319a60b6180e327c37e1fe5d448746c1644cce01ada1f2788d1e182f4", "6": "e7b0b329df927998a2a401be65bfcb63e89448ab13020ee7e720b0042afe8430"}[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original Mega Drive/32X cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>1024||at%8||at+count*8>scriptBytes||!total||total>limit)fail('Mega Drive/32X original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0xffff)fail('Invalid Mega Drive/32X original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('Mega Drive/32X input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid Mega Drive/32X selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(!audioFrames||audioFrames!==playFrames*735)fail('Mega Drive/32X audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,30000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinMegaDriveMusic==='undefined')importScripts('vendor/md/gremlin-md.js');
 const m=await GremlinMegaDriveMusic({locateFile:n=>new URL('vendor/md/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('Mega Drive/32X memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_md_load(ptr,p.rom.length);m._free(ptr);if(!failed&&(m._gremlin_md_source_rate()!==44100||m._gremlin_md_source_fps()!==60))fail('Original Mega Drive/32X clock profile differs');if(failed)fail(m.UTF8ToString(m._gremlin_md_error())||'Original Mega Drive/32X load failed');m._gremlin_md_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_md_step(event.mask))fail('Original Mega Drive/32X prefix failed');if(m._gremlin_md_audio_frames()>4096)fail('Original Mega Drive/32X frame profile differs');}
 if(m._gremlin_md_hardware_flags()!==({1:2,2:2,4:0,5:0,6:0})[p.profile])fail('Original cartridge hardware profile differs after boot');
 if(token!==generation){m._gremlin_md_close();return false;}
 if(state)state.m._gremlin_md_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'md',devices:[{type:'megadrive_32x_cpu_fm_psg_pwm',core:'original PicoDrive',source_revision:'1890c2932234c9d30f4cd3851d02228baae8f09e',source_rom_sha256:p.sha,original_CPU_APU_and_cartridge:true,source_audio_rate:44100,original_input_scripts:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('Mega Drive/32X selected source window exhausted');if(s.m._gremlin_md_step(event.mask))fail('Original Mega Drive/32X music frame failed');const n=s.m._gremlin_md_audio_frames();if(n>4096)fail('Original Mega Drive/32X frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_md_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('Mega Drive/32X output frame bounds');if(!state)fail('Mega Drive/32X music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*44100,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*44100/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
