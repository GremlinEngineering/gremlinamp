#include <jni.h>
#include "game-music-emu/gme/gme.h"
static void error(JNIEnv* e, const char* s) { if(s)e->ThrowNew(e->FindClass("java/io/IOException"),s); }
extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_Gme_open(JNIEnv* e,jclass,jstring path,jint song){
 const char* p=e->GetStringUTFChars(path,0); Music_Emu* m=nullptr;
 const char* err=gme_open_file(p,&m,44100); e->ReleaseStringUTFChars(path,p);
 if(err){error(e,err);return 0;} gme_clear_playlist(m);gme_set_autoload_playback_limit(m,0);
 if(song<0 || song>=gme_track_count(m)){gme_delete(m);error(e,"Invalid subsong");return 0;}
 err=gme_start_track(m,song); if(err){gme_delete(m);error(e,err);return 0;}
 return reinterpret_cast<jlong>(m);
}
extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_Gme_render(JNIEnv* e,jclass,jlong h,jshortArray out){
 auto m=reinterpret_cast<Music_Emu*>(h); if(!m)return;
 jshort* p=e->GetShortArrayElements(out,0); const char* err=gme_play(m,e->GetArrayLength(out),p);e->ReleaseShortArrayElements(out,p,0);error(e,err);
}
extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_Gme_close(JNIEnv*,jclass,jlong h){if(h)gme_delete(reinterpret_cast<Music_Emu*>(h));}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_Gme_seek(JNIEnv* e,jclass,jlong h,jint ms){if(h)error(e,gme_seek(reinterpret_cast<Music_Emu*>(h),ms));}
extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_Gme_ended(JNIEnv*,jclass,jlong h){return h&&gme_track_ended(reinterpret_cast<Music_Emu*>(h));}
extern "C" JNIEXPORT jlongArray JNICALL Java_ru_oopssre_gremlinamp_Gme_measure(JNIEnv* e,jclass,jstring path,jint song,jint limit){
 const char* p=e->GetStringUTFChars(path,0);Music_Emu* m=nullptr;const char* err=gme_open_file(p,&m,22050);e->ReleaseStringUTFChars(path,p);
 if(err){error(e,err);return nullptr;}gme_set_autoload_playback_limit(m,0);gme_clear_playlist(m);
 err=gme_start_track(m,song);if(err){gme_delete(m);error(e,err);return nullptr;}
 short pcm[4410];long frames=0,last=0;bool ended=false;
 while(frames*1000/22050<limit){err=gme_play(m,4410,pcm);if(err)break;frames+=2205;double energy=0;for(short s:pcm)energy+=double(s)*s;if(energy/4410>256)last=frames*1000/22050;if(gme_track_ended(m)){ended=true;break;}}
 gme_delete(m);if(err){error(e,err);return nullptr;}jlong values[3]={last,ended?frames*1000/22050:-1,limit};jlongArray result=e->NewLongArray(3);e->SetLongArrayRegion(result,0,3,values);return result;
}
