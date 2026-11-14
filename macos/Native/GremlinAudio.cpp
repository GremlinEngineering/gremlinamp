#include "GremlinAudio.h"
#include "gme.h"
#include "DecoderPort/Decoder.hpp"
#define STB_VORBIS_HEADER_ONLY
#include "../Vendor/stb_vorbis.c"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

struct GAStream {
    std::unique_ptr<GDDecoder> decoder;
    bool known = false;
    Music_Emu *gme = nullptr;
    stb_vorbis *ogg = nullptr;
    int rate = 44100;
    int64_t play = 120000, fade = 5000;
    std::atomic<int64_t> frames{0};
    std::atomic<bool> ended{false}, failed{false};
    std::mutex mutex;
    ~GAStream() { if(gme) gme_delete(gme); if(ogg) stb_vorbis_close(ogg); }
};
extern "C" GAStream *ga_open(const char *path, int song, int64_t play, int64_t fade, char *error, int error_size) {
    return ga_open_decoder("", path, song, play, fade, 0, 0, nullptr, nullptr, 0, nullptr, 0, error, error_size);
}
extern "C" GAStream *ga_open_decoder(const char *engine, const char *path, int song, int64_t play, int64_t fade,
    int preroll, int region, const char *const *paths, const char *const *names, int count,
    const int32_t *config, int config_count, char *error, int error_size) {
    auto s = std::make_unique<GAStream>();
    std::string ext(path); auto dot=ext.find_last_of('.'); ext=dot==std::string::npos?"":ext.substr(dot+1);
    std::transform(ext.begin(),ext.end(),ext.begin(),[](unsigned char c){return std::tolower(c);});
    const char *err=nullptr;
    s->known=play>=0;
    s->play=play>=0?std::clamp<int64_t>(play,1,1200000):120000;
    s->fade=std::clamp<int64_t>(fade,0,60000);
    try {
        if(engine && *engine && std::strcmp(engine,"gme") && std::strcmp(engine,"vorbis"))
            s->decoder=gd_open(engine,path,song,preroll,region,paths,names,count,config,config_count);
    } catch(const std::exception& e) {
        if(error&&error_size>0) std::snprintf(error,error_size,"%s",e.what());
        return nullptr;
    }
    if(s->decoder) {
        s->rate=s->decoder->rate;
        if(s->decoder->length>0) {
            s->play=(s->decoder->length*1000+s->rate-1)/s->rate;
            s->fade=0;s->known=true;
        }
    } else if(ext=="ogg" || ext=="oga") {
        int code=0; s->ogg=stb_vorbis_open_filename(path,&code,nullptr);
        if(!s->ogg) err="Не удалось открыть Ogg Vorbis";
        else { s->rate=stb_vorbis_get_info(s->ogg).sample_rate; s->play=(int64_t)std::llround(stb_vorbis_stream_length_in_seconds(s->ogg)*1000); s->fade=0; s->known=true; }
    } else {
        err=gme_open_file(path,&s->gme,s->rate);
        if(!err) { gme_clear_playlist(s->gme); gme_set_autoload_playback_limit(s->gme,0);
            if(song<0 || song>=gme_track_count(s->gme)) err="Неверный номер композиции";
            else err=gme_start_track(s->gme,song);
        }
    }
    if(err) { if(error&&error_size>0) std::snprintf(error,error_size,"%s",err); return nullptr; }
    return s.release();
}
extern "C" void ga_close(GAStream *s) { delete s; }
extern "C" int ga_rate(GAStream *s) { return s?s->rate:44100; }
extern "C" int64_t ga_duration(GAStream *s) { return s&&s->known?s->play+s->fade:-1; }
extern "C" int64_t ga_position(GAStream *s) { return s?s->frames.load()*1000/s->rate:0; }
extern "C" int ga_ended(GAStream *s) { return s&&s->ended.load(); }
extern "C" int ga_failed(GAStream *s) { return s&&s->failed.load(); }
extern "C" int ga_seek(GAStream *s, int64_t ms) {
    if(!s) return 0;
    std::lock_guard<std::mutex> lock(s->mutex);
    ms=std::max<int64_t>(0,std::min(ms,s->play+s->fade));
    const int64_t frame=ms*s->rate/1000;
    if(s->decoder) {
        bool ok=false;
        try {ok=s->decoder->seek(frame);} catch(...) {return 0;}
        s->frames=s->decoder->cursor;s->ended=s->decoder->ended();s->failed=false;
        return ok;
    }
    bool ok=s->gme?gme_seek(s->gme,(int)ms)==nullptr:stb_vorbis_seek(s->ogg,(unsigned int)(ms*s->rate/1000))!=0;
    if(ok) {s->frames=ms*s->rate/1000;s->ended=false;s->failed=false;} return ok;
}
extern "C" void ga_render(GAStream *s, float *out, int frames) {
    if(!out || frames<=0) return;
    std::fill(out,out+frames*2,0.f);
    if(!s || s->ended.load()) return;
    // A seek may be expensive. The audio callback emits silence while it runs.
    std::unique_lock<std::mutex> lock(s->mutex,std::try_to_lock);
    if(!lock.owns_lock()) return;
    int64_t endFrame=s->decoder&&s->decoder->length>0 ? s->decoder->length : (s->play+s->fade)*s->rate/1000;
    int64_t left=std::max<int64_t>(0,endFrame-s->frames.load());
    int count=(int)std::min<int64_t>(frames,left);
    if(s->ogg) {
        int got=stb_vorbis_get_samples_float_interleaved(s->ogg,2,out,count*2);
        s->frames+=got;
        if(got<count || count==0) s->ended=true;
    } else {
        short pcm[4096*2]; int done=0;
        while(done<count) {
            int n=std::min(2048,count-done);
            int got=n;
            try {
                if(s->decoder) got=s->decoder->render(pcm,n);
                else if(gme_play(s->gme,n*2,pcm)) got=-1;
            } catch(...) {got=-1;}
            if(got<0){s->failed=true;s->ended=true;break;}
            if(got==0){s->ended=true;break;}
            for(int i=0;i<got;i++) {
                double ms=(s->frames.load()+i)*1000.0/s->rate;
                float gain=ms<s->play?1.f:s->fade>0?(float)std::max(0.,1.-(ms-s->play)/s->fade):0.f;
                out[(done+i)*2]=pcm[i*2]/32768.f*gain;
                out[(done+i)*2+1]=pcm[i*2+1]/32768.f*gain;
            }
            s->frames+=got; done+=got;
            if(s->decoder?s->decoder->ended():gme_track_ended(s->gme)) {s->ended=true;break;}
        }
    }
    if(s->frames.load()>=endFrame) s->ended=true;
}
extern "C" int ga_render_planar(GAStream *s,float *left,int left_capacity,float *right,int right_capacity,int frames) {
    if(frames==0) return 0;
    if(!s || !left || !right || left==right || frames<0 || left_capacity<frames || right_capacity<frames) {
        if(s) s->failed=true;
        return -1;
    }
    // AVAudioEngine's standard Float32 stereo format has one buffer per channel.
    // Decode once, then split into the supplied channel buffers without allocation.
    constexpr int chunk_frames=1024;
    float stereo[chunk_frames*2];
    bool audible=false;
    for(int offset=0;offset<frames;) {
        const int count=std::min(chunk_frames,frames-offset);
        ga_render(s,stereo,count);
        for(int i=0;i<count;i++) {
            const float l=stereo[i*2],r=stereo[i*2+1];
            left[offset+i]=l;right[offset+i]=r;
            audible=audible || l!=0.f || r!=0.f;
        }
        offset+=count;
    }
    return s->failed.load() ? -1 : audible ? 1 : 0;
}
extern "C" int ga_track_count(const char *path) {
    Music_Emu *m=nullptr; if(gme_open_file(path,&m,44100)) return 0;
    gme_clear_playlist(m); int n=gme_track_count(m); gme_delete(m); return n;
}
extern "C" int ga_measure(const char *path,int song,int limit,int64_t *sound,int64_t *end) {
    Music_Emu *m=nullptr; if(gme_open_file(path,&m,22050)) return 0;
    gme_clear_playlist(m);gme_set_autoload_playback_limit(m,0);
    if(gme_start_track(m,song)) {gme_delete(m);return 0;}
    int64_t frames=0,last=0,ended=-1; short pcm[4410]; bool ok=true;
    while(frames*1000/22050<limit) {
        if(gme_play(m,4410,pcm)) {ok=false;break;} frames+=2205;
        double energy=0; for(short v:pcm) energy+=double(v)*v;
        if(energy/4410>256) last=frames*1000/22050;
        if(gme_track_ended(m)) {ended=frames*1000/22050;break;}
    }
    gme_delete(m); if(sound) *sound=last; if(end) *end=ended; return ok;
}
