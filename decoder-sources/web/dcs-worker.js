/* BSD-3-Clause. Original arcade DCS ROM programs on the strict ADSP2105
 * interpreter. No decoded songs, authored notes or native DSP speedups. */
let state,ready,generation=0;
const profiles={
 1:{game:'game-ra-14978',banks:'002237fe2e9bb44466e617af56695fd2f5ba2cbaf32764b92a85ae9ac75d3648',chips:6,chip_bytes:524288,commands:[1,2]},
 2:{game:'game-ra-14983',banks:'5af074edceb05e0f22d5d117c01b3bf76d57b8dbd9fe645bddadc3f0b48a9f45',chips:4,chip_bytes:1048576,commands:[5,6]},
 3:{game:'game-ra-14654',banks:'6e15909d38bcaff028bc074436d1ddf6b12c5597f9ec67d87cce3c57f9a83466',chips:4,chip_bytes:1048576,commands:[5,6]}
};
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function parse(input,index,rate){
 if(!(input instanceof ArrayBuffer)||input.byteLength>12000000)throw Error('DCS input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){
  const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let n=0;
  try{for(;;){const{value,done}=await reader.read();if(done)break;n+=value.length;if(n>12000000)throw Error('DCS expansion bounds');parts.push(value)}}finally{reader.releaseLock()}
  bytes=new Uint8Array(n);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length}
 }
 if(bytes.length<16||String.fromCharCode(...bytes.subarray(0,4))!=='DCM\x1a')throw Error('Original DCS capsule header');
 const v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),j=v.getUint32(4,true),r=v.getUint32(8,true);
 if(!j||j>65536||r<524288||r>8388608||v.getUint32(12,true)!==0||16+j+r!==bytes.length)throw Error('DCS capsule region bounds');
 const p=JSON.parse(new TextDecoder().decode(bytes.subarray(16,16+j))),profile=profiles[p.profile],banks=bytes.subarray(16+j);
 if(!Number.isInteger(p.profile)||!profile||p.version!==1||p.game_id!==profile.game||p.upstream_commit!=='3d2220f58406756293b9552b7df0b264969b7061'||p.board!=='arcade_dcs2k_uart'||p.decoder!=='strict_original_ADSP2105'||p.source_rate!==31250||p.chips!==profile.chips||p.chip_bytes!==profile.chip_bytes||r!==p.chips*p.chip_bytes||p.volume!==224)throw Error('Unsupported original arcade DCS profile');
 if(p.banks_sha256!==profile.banks||hex(new Uint8Array(await crypto.subtle.digest('SHA-256',banks)))!==profile.banks)throw Error('Original DCS bank identity differs');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(index)||index<0||!Array.isArray(p.tracks)||p.tracks.length!==profile.commands.length||index>=p.tracks.length)throw Error('DCS rate or selector bounds');
 for(let i=0;i<p.tracks.length;i++){const t=p.tracks[i];if(t.command!==profile.commands[i]||t.audio_frames!==1968750)throw Error('DCS original selection differs')}
 return{...p,...p.tracks[index],banks,rate,index};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinDCSMusic==='undefined')importScripts('vendor/dcs/gremlin-dcs.js');
 const m=await GremlinDCSMusic({locateFile:n=>new URL('vendor/dcs/'+n,self.location.href).href,print(){},printErr(){}}),ptr=m._malloc(p.banks.length);
 if(!ptr)throw Error('DCS bank allocation failed');m.HEAPU8.set(p.banks,ptr);
 const failed=m._gremlin_dcs_load(ptr,p.banks.length,p.chips,p.chip_bytes);m._free(ptr);
 if(failed||m._gremlin_dcs_select(p.command,p.volume)){const error=m.UTF8ToString(m._gremlin_dcs_error());m._gremlin_dcs_close();throw Error(error||'Original DCS program failed')}
 if(token!==generation){m._gremlin_dcs_close();return false}
 if(state)state.m._gremlin_dcs_close();state={m,...p,position:0,generated:0,queue:new Float32Array(0),queueBase:0};
 postMessage({type:'ready',decoder:'dcs',devices:[{type:'arcade_dcs_adsp2105',core:'DCSExplorer strict ADSP2105 ROM interpreter',commit:p.upstream_commit,ROM_banks_SHA256:p.banks_sha256,source_rate:31250,original_command:p.command,original_hardware_volume:p.volume,ADSP_speedups:false,original_ROM_program_and_streams:true,arcade_latch_adapter:'DM3400..37ff',rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const n=Math.min(4096,s.audio_frames-s.generated);if(n<1)throw Error('DCS original source window exhausted');
 if(s.m._gremlin_dcs_render(n))throw Error(s.m.UTF8ToString(s.m._gremlin_dcs_error()));
 const out=new Float32Array(s.queue.length+n*2);out.set(s.queue);const at=s.m._gremlin_dcs_audio()>>1;
 for(let i=0;i<n*2;i++)out[s.queue.length+i]=s.m.HEAP16[at+i]/32768;s.queue=out;s.generated+=n;
}
function sample(s,index,ch){if(index>=s.audio_frames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch]}
function render(message){
 const n=message.frames;if(!state||!Number.isInteger(n)||n<1||n>16384)throw Error('DCS output bounds');
 const s=state,out=new Float32Array(n*2);
 for(let i=0;i<n;i++,s.position++){const numerator=s.position*31250,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);out[2*i+ch]=a+(b-a)*rem/s.rate}}
 const next=Math.floor(s.position*31250/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,next-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard}
 postMessage({type:'pcm',pcm:out.buffer},[out.buffer]);
}
onmessage=({data:m})=>{
 if(m.type==='load'){const token=++generation;ready=load(m,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false})}
 else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
