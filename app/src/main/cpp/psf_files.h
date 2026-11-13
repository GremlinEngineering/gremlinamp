#pragma once

#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace PsfFiles {
using InputFiles = std::unordered_map<std::string, std::vector<uint8_t>>;
constexpr size_t maxInput = 256 * 1024 * 1024;
struct MemoryFile {
    const std::vector<uint8_t>& bytes;
    size_t position = 0;
};

inline void* openFile(void* context, const char* name) {
    auto& files = *static_cast<InputFiles*>(context);
    auto file = files.find(name);
    return file == files.end() ? nullptr : new (std::nothrow) MemoryFile{file->second};
}

inline size_t readFile(void* output, size_t size, size_t count, void* handle) {
    auto& file = *static_cast<MemoryFile*>(handle);
    if (size == 0) return 0;
    count = std::min(count, (file.bytes.size() - file.position) / size);
    if (count == 0) return 0;
    std::memcpy(output, file.bytes.data() + file.position, count * size);
    file.position += count * size;
    return count;
}

inline int seekFile(void* handle, int64_t offset, int origin) {
    auto& file = *static_cast<MemoryFile*>(handle);
    int64_t base = origin == SEEK_SET ? 0 : origin == SEEK_CUR ? file.position : file.bytes.size();
    if (offset < -base || offset > int64_t(file.bytes.size()) - base) return -1;
    file.position = base + offset;
    return 0;
}

inline int closeFile(void* handle) {
    delete static_cast<MemoryFile*>(handle);
    return 0;
}

inline long tellFile(void* handle) { return long(static_cast<MemoryFile*>(handle)->position); }

struct UtfChars {
    JNIEnv* env;
    jstring value;
    const char* chars;
    UtfChars(JNIEnv* e, jstring v) : env(e), value(v), chars(e->GetStringUTFChars(v, nullptr)) {}
    ~UtfChars() { if (chars) env->ReleaseStringUTFChars(value, chars); }
};


inline bool copy(JNIEnv* env, jobjectArray names, jobjectArray sources, InputFiles& files, std::string& entry) {
    jsize count = env->GetArrayLength(names);
    if (count == 0 || count != env->GetArrayLength(sources)) throw std::runtime_error("Invalid PSF dependencies");
    size_t total = 0;
    for (jsize index = 0; index < count; ++index) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, index));
        auto source = static_cast<jbyteArray>(env->GetObjectArrayElement(sources, index));
        if (env->ExceptionCheck()) return false;
        jsize bytes = env->GetArrayLength(source);
        if (size_t(bytes) > maxInput - total) throw std::runtime_error("PSF files are too large");
        total += bytes;
        {
            UtfChars chars(env, name);
            if (!chars.chars) return false;
            if (index == 0) entry = chars.chars;
            auto [item, inserted] = files.try_emplace(chars.chars, bytes);
            if (!inserted) throw std::runtime_error("Duplicate PSF dependency");
            env->GetByteArrayRegion(source, 0, bytes, reinterpret_cast<jbyte*>(item->second.data()));
        }
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(source);
        if (env->ExceptionCheck()) return false;
    }
    return true;
}
}
