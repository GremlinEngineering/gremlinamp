/* Synthetic held YM2413 note. No third-party music in this regression. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
async function main(){
 const root=path.resolve(process.argv[2]),M=require(path.join(root,'libgme.js'));
 await new Promise((resolve,reject)=>{if(M.calledRun)resolve();else{M.onRuntimeInitialized=resolve;M.onAbort=reason=>reject(Error(String(reason)))}});
 const registers=Buffer.from('513010511080512015','hex'),waits=Buffer.from('61ffff'.repeat(12)+'66','hex'),bytes=Buffer.alloc(64+registers.length+waits.length);
 bytes.write('Vgm ');bytes.writeUInt32LE(bytes.length-4,4);bytes.writeUInt32LE(0x150,8);bytes.writeUInt32LE(3579545,0x10);bytes.writeUInt32LE(65535*12,0x18);registers.copy(bytes,64);waits.copy(bytes,64+registers.length);
 const input=M._malloc(bytes.length),reference=M._malloc(4),output=M._malloc(4096*4);let emu=0;
 const checked=error=>{if(error)throw Error(M.UTF8ToString(error))};
 try{
  M.HEAPU8.set(bytes,input);checked(M._gme_open_data(input,bytes.length,reference,22050));emu=M.HEAP32[reference>>2];M._gme_clear_playlist(emu);M._gme_ignore_silence(emu,1);checked(M._gme_start_track(emu,0));
  function rms(frames){let energy=0,count=0;while(frames){let n=Math.min(frames,4096);checked(M._gme_play(emu,n*2,output));for(let i=0;i<n*2;i++){const value=M.HEAP16[(output>>1)+i]/32768;energy+=value*value;count++}frames-=n}return Math.sqrt(energy/count)}
  const initial=rms(44100);assert(initial>0.001,'YM2413 must produce non-silent PCM');
  M._gme_mute_voices(emu,0xffff);rms(11025);const muted=rms(22050);assert(muted<initial*.01,'Mute must clear held channel outputs');
  M._gme_mute_voices(emu,0);checked(M._gme_start_track(emu,0));const restarted=rms(44100);assert(restarted>0.001,'Restart must produce audio');
  const result={passed:true,initial,muted,restarted,rate:22050,frames_per_call:4096};fs.writeFileSync(path.join(root,'YM2413_VALIDATION.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
 }finally{if(emu)M._gme_delete(emu);M._free(input);M._free(reference);M._free(output)}
}
main().catch(error=>{console.error(error);process.exitCode=1});
