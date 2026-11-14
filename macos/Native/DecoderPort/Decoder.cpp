#include "Decoder.hpp"
#include "jni.h"
#include <algorithm>
#include <array>
#include <dlfcn.h>
#include <fstream>
#include <stdexcept>
#include <vector>
#include <zlib.h>

namespace {
constexpr int maxInput = 256 * 1024 * 1024;
std::vector<jbyte> read(const char* path, bool gzip = true) {
    std::vector<jbyte> result;
    if (gzip) {
        auto f = gzopen(path, "rb");
        if (!f) throw std::runtime_error("Файл декодера недоступен");
        std::unique_ptr<gzFile_s, decltype(&gzclose)> handle(f, gzclose);
        std::array<jbyte,65536> chunk;
        int count;
        while ((count=gzread(f,chunk.data(),chunk.size()))>0) {
            if (result.size()+count>maxInput) throw std::runtime_error("Файл декодера слишком велик");
            result.insert(result.end(),chunk.data(),chunk.data()+count);
        }
        if (count<0) throw std::runtime_error("Повреждённый архив трека");
    } else {
        std::ifstream file(path,std::ios::binary|std::ios::ate);
        const auto n=file.tellg();
        if(n<1 || n>maxInput) throw std::runtime_error("Недопустимый размер файла");
        result.resize(n);
        file.seekg(0);
        if(!file.read(reinterpret_cast<char*>(result.data()),n)) throw std::runtime_error("Ошибка чтения трека");
    }
    if (result.empty()) throw std::runtime_error("Пустой файл декодера");
    return result;
}
struct Library {
    void* handle = nullptr;
    explicit Library(const std::string& name) {
        Dl_info here{};
        if (!dladdr(reinterpret_cast<const void*>(&read), &here) || !here.dli_fname)
            throw std::runtime_error("Не удалось найти каталог декодеров");
        std::string path(here.dli_fname);
        path.resize(path.find_last_of('/')+1);
        path += "libgremlinamp" + name + ".dylib";
        handle=dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) throw std::runtime_error(dlerror());
    }
    ~Library() { if(handle) dlclose(handle); }
    template<class T> T find(const std::string& name, bool needed = true) const {
        auto p = reinterpret_cast<T>(dlsym(handle,name.c_str()));
        if (!p && needed) throw std::runtime_error("Несовместимый декодер: "+name);
        return p;
    }
};

using Handle = void(JNIEnv*,jclass,jlong);
using Render = jint(JNIEnv*,jclass,jlong,jshortArray,jint);
using VoidRender = void(JNIEnv*,jclass,jlong,jshortArray,jint);
using Seek = jlong(JNIEnv*,jclass,jlong,jlong);
using VoidSeek = void(JNIEnv*,jclass,jlong,jint);
using Query = jlong(JNIEnv*,jclass,jlong);
using End = jboolean(JNIEnv*,jclass,jlong);
using Rate = jint(JNIEnv*,jclass,jlong);

struct Frontend : GDDecoder {
    Library library;
    std::string prefix;
    jlong handle=0;
    Handle *close=nullptr,*restart=nullptr;
    Render *readBlock=nullptr;
    VoidRender *readFull=nullptr;
    Seek *jump=nullptr;
    VoidSeek *jumpPlus=nullptr;
    End *end=nullptr;
    bool eof=false;
    explicit Frontend(const std::string& libraryName, const std::string& klass)
        : library(libraryName), prefix("gd_"+klass+"_") {
        close=api<Handle>("close");
        end=api<End>("ended",false);
        restart=api<Handle>("restart",false);
    }
    ~Frontend() override { if(handle) {JNIEnv env;close(&env,nullptr,handle);} }
    template<class T> T* api(const std::string& member,bool needed=true) const {return library.find<T*>(prefix+member,needed);}
    int render(short* out,int frames) override {
        if(eof) return 0;
        JNIEnv env;
        Borrowed<jshort> buffer{out,frames*2};
        std::array<short,4096*2> discard{};
        if(!out) buffer.data=discard.data();
        int n=frames;
        if(readFull) readFull(&env,nullptr,handle,&buffer,frames);
        else n=readBlock(&env,nullptr,handle,&buffer,frames);
        if(env.ExceptionCheck() || n<0 || n>frames) return -1;
        cursor+=n;eof=n==0;
        return n;
    }
    bool ended() const override { JNIEnv env;return eof || (end && end(&env,nullptr,handle)); }
    bool seek(int64_t frame) override {
        JNIEnv env;
        eof=false;
        if(jumpPlus) {jumpPlus(&env,nullptr,handle,jint(frame*1000/rate));cursor=frame;}
        else if(jump) cursor=jump(&env,nullptr,handle,frame);
        else if(frame<cursor) {
            if(!restart) return false;
            restart(&env,nullptr,handle);cursor=0;
        }
        if(env.ExceptionCheck() || cursor<0 || cursor>frame) return false;
        while(cursor<frame && !ended()) {
            if(render(nullptr,int(std::min<int64_t>(2048,frame-cursor)))<0) return false;
        }
        return cursor==frame || ended();
    }
};
struct Machine : GDDecoder {
    Library library{""};
    using Open = jlong(JNIEnv*,jclass,jstring,jbyteArray,jint,jboolean,jstring);
    using Step = jint(JNIEnv*,jclass,jlong,jint,jshortArray);
    Open* open=library.find<Open*>("gd_NativeMachine_openCore");
    Step* step=library.find<Step*>("gd_NativeMachine_step");
    Handle* close=library.find<Handle*>("gd_NativeMachine_closeCore");
    std::string name, directory;
    std::vector<jbyte> rom;
    std::vector<int32_t> preroll, events;
    bool fds;
    jlong handle=0;
    size_t event=0;
    int stepIndex=0, next=0, available=0;
    std::array<short,8192> block{};
    Machine(std::string n,std::string dir,std::vector<jbyte> bytes,const int32_t* cfg,int count)
        : name(std::move(n)),directory(std::move(dir)),rom(std::move(bytes)) {
        if(count<8 || count>4100 || cfg[3]<2 || cfg[3]>count-6 || cfg[3]%2 || count%2 ||
            cfg[0]<8000 || cfg[0]>384000 || cfg[1]<1) throw std::runtime_error("Неверный сценарий консоли");
        rate=cfg[0];length=cfg[1];fds=cfg[2]!=0;
        preroll.assign(cfg+4,cfg+4+cfg[3]);events.assign(cfg+4+cfg[3],cfg+count);
    }
    ~Machine() override { JNIEnv env;if(handle) close(&env,nullptr,handle); }
    void reset() {
        JNIEnv env;
        if(handle) close(&env,nullptr,handle);
        handle=0;
        Borrowed<jbyte> data{rom.data(),jint(rom.size())};
        handle=open(&env,nullptr,name.c_str(),&data,rate,fds,directory.c_str());
        if(!handle || env.ExceptionCheck()) throw std::runtime_error(env.error.empty()?"Ошибка ядра консоли":env.error);
        cursor=0;event=0;stepIndex=next=available=0;
        for(size_t i=0;i<preroll.size();i+=2) for(int j=0;j<preroll[i];j++) {
            step(&env,nullptr,handle,preroll[i+1],nullptr);
            if(env.ExceptionCheck()) throw std::runtime_error(env.error);
        }
    }
    int render(short* output,int frames) override {
        JNIEnv env;
        frames=int(std::min<int64_t>(frames,length-cursor));
        int done=0;
        while(done<frames) {
            while(next==available) {
                if(event>=events.size()) return -1;
                Borrowed<jshort> pcm{block.data(),jint(block.size())};
                available=step(&env,nullptr,handle,events[event+1],&pcm);
                if(env.ExceptionCheck() || available<0 || size_t(available)>block.size()/2) return -1;
                next=0;
                if(++stepIndex==events[event]) {event+=2;stepIndex=0;}
            }
            int n=std::min(frames-done,available-next);
            if(output) std::copy_n(block.data()+2*next,2*n,output+2*done);
            next+=n;done+=n;
        }
        cursor+=done;
        return done;
    }
    bool ended() const override {return cursor>=length;}
    bool seek(int64_t position) override {
        auto goal=std::clamp<int64_t>(position,0,length);
        if(goal<cursor) reset();
        while(cursor<goal) if(render(nullptr,int(std::min<int64_t>(2048,goal-cursor)))<=0) return false;
        return true;
    }
};

}

std::unique_ptr<GDDecoder> gd_open(const char* key,const char* path,int song,int preroll,int region,
    const char* const* paths,const char* const* names,int count,const int32_t* config,int configCount) {
    std::string engine(key);
    if(engine.rfind("machine:",0)==0) {
        auto s=std::make_unique<Machine>(engine.substr(8),count>0?paths[0]:"",read(path,false),config,configCount);
        s->reset();
        return s;
    }
    auto bytes=engine=="ssf" || engine=="psf" || engine=="dsf" || engine=="usf" || engine=="gsf"
        ? std::vector<jbyte>() : read(path);
    Borrowed<jbyte> data{bytes.data(),jint(bytes.size())};
    if (engine=="nsf") {
        if (bytes.size()>=128 && std::memcmp(bytes.data(),"NESM\x1a",5)==0 &&
            (bytes[123]&4) && uint8_t(bytes[9])<128) engine="gme-plus";
        else return nullptr;
    }
    if(engine=="stm") engine="openmpt";
    if(engine=="vgm") engine="libvgm";
    const auto board = engine.rfind("board:",0)==0;
    std::string name, klass;
    if(engine=="libvgm"){klass="NativeVgm";}
    else if(engine=="openmpt"){name="-openmpt";klass="NativeModule";}
    else if(engine=="gme-plus"){name="-gme-plus";klass="GmePlus";}
    else if(engine=="sid" || engine=="xa"){name="-streams";klass="NativeStream";}
    else if(engine=="gsf"){name="-gsf";klass="NativeGsf";}
    else if(engine=="psf" || engine=="dsf" || engine=="usf" || engine=="ssf"){name="-psf";klass="NativePsf";}
    else if(board){klass="NativeBoard";}
    else throw std::runtime_error("Неизвестный декодер: "+engine);
    auto s=std::make_unique<Frontend>(name,klass);
    JNIEnv env;
    if(engine=="libvgm") {
        s->handle=s->api<jlong(JNIEnv*,jclass,jbyteArray,jint)>("open")(&env,nullptr,&data,preroll);
        s->jump=s->api<Seek>("seek");
    } else if(engine=="gme-plus" || engine=="openmpt") {
        s->handle=s->api<jlong(JNIEnv*,jclass,jbyteArray,jint)>("open")(&env,nullptr,&data,song);
        if(engine=="gme-plus") s->jumpPlus=s->api<VoidSeek>("seek");
        else s->jump=s->api<Seek>("seekFloor");
    } else if(engine=="sid" || engine=="xa") {
        s->handle=s->api<jlong(JNIEnv*,jclass,jboolean,jbyteArray,jint)>("open")(&env,nullptr,engine=="xa",&data,song);
        s->jump=s->api<Seek>("seek",false);
        if(engine=="sid") s->jump=nullptr;
    } else if(board) {
        Borrowed<jint> cfg{const_cast<jint*>(config),configCount};
        s->handle=s->api<jlong(JNIEnv*,jclass,jstring,jbyteArray,jint,jint,jintArray)>("open")(
            &env,nullptr,engine.c_str()+6,&data,song,region,&cfg);
    } else {
        std::vector<std::vector<jbyte>> files;
        std::vector<Borrowed<jbyte>> buffers;
        std::vector<jobject> strings, sources;
        if(engine!="ssf") {
            if(count<=0 || count>128 || !paths || !names) throw std::runtime_error("Нет файлов декодера PSF");
            size_t total=0;
            for(int i=0;i<count;i++) {
                files.push_back(read(paths[i],false));
                total+=files.back().size();
                if(total>maxInput) throw std::runtime_error("Файлы PSF превышают лимит");
            }
            for(int i=0;i<count;i++) buffers.push_back({files[i].data(),jint(files[i].size())});
            for(int i=0;i<count;i++) {strings.push_back(const_cast<char*>(names[i]));sources.push_back(&buffers[i]);}
        }
        Borrowed<jobject> n{strings.data(),jint(strings.size())},d{sources.data(),jint(sources.size())};
        if(engine=="gsf") s->handle=s->api<jlong(JNIEnv*,jclass,jobjectArray,jobjectArray)>("open")(&env,nullptr,&n,&d);
        else {
            int mode=engine=="dsf"?1:engine=="usf"?2:engine=="ssf"?3:0;
            s->handle=s->api<jlong(JNIEnv*,jclass,jint,jobjectArray,jobjectArray,jstring)>("open")(
                &env,nullptr,mode,&n,&d,engine=="ssf"?path:nullptr);
        }
    }
    if(!s->handle || env.ExceptionCheck()) throw std::runtime_error(env.error.empty() ? "Декодер не открыл трек" : env.error);
    if(engine=="gme-plus" || engine=="gsf") s->readFull=s->api<VoidRender>("render");
    else s->readBlock=s->api<Render>("render");
    if(auto query=s->api<Query>("duration",false)) s->length=query(&env,nullptr,s->handle);
    if(auto query=s->api<Rate>("rate",false)) s->rate=query(&env,nullptr,s->handle);
    if(s->rate<8000 || s->rate>384000) throw std::runtime_error("Неверная частота декодера");
    return s;
}

extern "C" unsigned char* ga_unpack_file(const char* path,int* length) {
    if(!length) return nullptr;
    try {
        auto bytes=read(path);
        auto* out=static_cast<unsigned char*>(malloc(bytes.size()));
        if(!out) return nullptr;
        std::memcpy(out,bytes.data(),bytes.size());
        *length=int(bytes.size());
        return out;
    } catch(...) { return nullptr; }
}
extern "C" uint32_t ga_crc32(const unsigned char* bytes,int length) {
    return crc32(0,bytes,length);
}
