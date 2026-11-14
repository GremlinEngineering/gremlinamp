#include "../Native/GremlinAudio.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <thread>
#include <vector>
static void planar(const char *path) {
    char error[512]={};
    for(int count:{1,3,127,257,1024,4096,8193}) {
        GAStream *a=ga_open(path,0,1000,100,error,sizeof error);
        GAStream *b=ga_open(path,0,1000,100,error,sizeof error);assert(a&&b);
        std::vector<float> expected(count*2),left(count+2,12345.f),right(count+2,12345.f);
        ga_render(a,expected.data(),count);
        const int signal=ga_render_planar(b,left.data()+1,count,right.data()+1,count,count);
        assert(signal>=0);
        assert(left.front()==12345.f&&left.back()==12345.f);
        assert(right.front()==12345.f&&right.back()==12345.f);
        bool audible=false;
        for(int i=0;i<count;i++) {
            assert(left[i+1]==expected[i*2]);assert(right[i+1]==expected[i*2+1]);
            assert(std::isfinite(left[i+1])&&std::isfinite(right[i+1]));
            audible=audible||left[i+1]!=0.f||right[i+1]!=0.f;
        }
        assert(signal==(audible?1:0));assert(ga_position(a)==ga_position(b));
        ga_close(a);ga_close(b);
    }
    GAStream *s=ga_open(path,0,1000,100,error,sizeof error);assert(s);
    float left[8],right[8];std::fill(left,left+8,123.f);std::fill(right,right+8,456.f);
    assert(ga_render_planar(s,left,4,right,8,8)==-1);
    assert(ga_position(s)==0);for(float v:left)assert(v==123.f);for(float v:right)assert(v==456.f);
    assert(ga_seek(s,0));assert(ga_render_planar(s,left,8,left,8,8)==-1);
    assert(ga_seek(s,0));assert(ga_render_planar(s,nullptr,8,right,8,8)==-1);
    assert(ga_render_planar(s,nullptr,0,nullptr,0,0)==0);
    ga_close(s);
    for(int i=0;i<200;i++) {
        s=ga_open(path,0,1000,100,error,sizeof error);assert(s);
        assert(ga_render_planar(s,left,8,right,8,8)>=0);ga_close(s);
    }
}
int main(int argc,char **argv) {
    assert(argc==4);char error[512]={};
    assert(ga_track_count(argv[1])==2);
    assert(!ga_open(argv[1],2,1000,100,error,sizeof error));
    GAStream *n=ga_open(argv[1],0,1000,100,error,sizeof error);assert(n);
    std::vector<float> pcm(4410*2);double energy=0;
    ga_render(n,pcm.data(),4410);for(float v:pcm){assert(std::isfinite(v));energy+=v*v;}assert(energy>0);
    assert(ga_position(n)==100);assert(ga_duration(n)==1100);
    assert(ga_seek(n,700));assert(ga_position(n)==700);
    for(int i=0;i<8;i++)ga_render(n,pcm.data(),4410);
    assert(ga_ended(n));assert(ga_position(n)==1100);
    ga_render(n,pcm.data(),4410);for(float v:pcm)assert(v==0.f);
    assert(ga_seek(n,0));assert(!ga_ended(n));
    std::thread seeker([&]{for(int i=0;i<30;i++)assert(ga_seek(n,(i*13)%900));});
    for(int i=0;i<100;i++)ga_render(n,pcm.data(),4410);seeker.join();ga_close(n);
    int64_t sound=-1,end=-2;assert(ga_measure(argv[1],0,500,&sound,&end));assert(sound>0);assert(end==-1);
    GAStream *v=ga_open(argv[3],0,1000,100,error,sizeof error);assert(v);ga_render(v,pcm.data(),4410);assert(!ga_failed(v));ga_close(v);
    GAStream *o=ga_open(argv[2],0,0,0,error,sizeof error);assert(o);assert(ga_rate(o)==48000);
    assert(std::abs(ga_duration(o)-400)<3);ga_render(o,pcm.data(),4410);
    energy=0;for(float v:pcm)energy+=v*v;assert(energy>0);assert(ga_seek(o,200));
    for(int i=0;i<10;i++)ga_render(o,pcm.data(),4410);
    assert(ga_ended(o));assert(ga_position(o)<=402);ga_close(o);
    assert(!ga_open("/missing/file.mp3",0,100,0,error,sizeof error));
    planar(argv[1]);planar(argv[2]);planar(argv[3]);
    std::cout<<"Native: NSF subsongs, PCM, fade/end, seek, concurrent seek, measurement, VGZ, OGG 48 kHz: passed\n";
    std::cout<<"Planar output: stereo channels, silence flag, odd buffer sizes, bounds and stream lifetime: passed\n";
}
