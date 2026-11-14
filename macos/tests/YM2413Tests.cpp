#include "../Vendor/game-music-emu/gme/gme.h"
#include "../Native/GremlinAudio.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

static std::vector<short> render(const char *path,bool muted=false) {
    Music_Emu *emu=nullptr;
    const char *error=gme_open_file(path,&emu,44100);
    if(error) {std::cerr<<error<<'\n';std::abort();}
    assert(!gme_start_track(emu,0));
    if(muted) gme_mute_voices(emu,0x7fffffff);
    std::vector<short> pcm(44100*2*3);
    assert(!gme_play(emu,static_cast<int>(pcm.size()),pcm.data()));
    gme_delete(emu);return pcm;
}

int main(int argc,char **argv) {
    assert(argc==3);
    const auto vgm=render(argv[1]);
    const auto vgz=render(argv[2]);
    int peak=0;double energy=0;
    for(short s:vgm) {peak=std::max(peak,std::abs(int(s)));energy+=double(s)*s;}
    const double rms=std::sqrt(energy/vgm.size());
    assert(peak>100&&rms>20);
    assert(vgm==vgz);
    assert(render(argv[1])==vgm);
    const auto muted=render(argv[1],true);
    assert(std::all_of(muted.begin()+44100,muted.end(),[](short s){return s==0;}));
    assert(std::any_of(vgm.begin(),vgm.begin()+88200,[](short s){return s!=0;}));

    // Exercise the actual Float32 planar bridge used by the Mac source node.
    for(int file=1;file<=2;file++) {
        char error[512]={};auto *stream=ga_open(argv[file],0,3000,0,error,sizeof error);assert(stream);
        float left[257],right[257];double l=0,r=0;
        for(int n=0;n<200;n++) {
            assert(ga_render_planar(stream,left,257,right,257,257)>=0);
            for(int i=0;i<257;i++) {l+=left[i]*left[i];r+=right[i]*right[i];}
        }
        assert(l>0.001&&r>0.001);ga_close(stream);
    }
    std::cout<<"YM2413: sound, VGM/VGZ parity, restart, mute, early audio and Mac stereo bridge: passed; peak="<<peak<<", RMS="<<rms<<'\n';
}
