/*
 * jni_wrapper.h
 *
 * RAII wrapper for JNI operations to improve error handling and resource management.
 * Provides automatic cleanup and exception safety for JNI resources.
 */

#ifndef JNI_WRAPPER_H
#define JNI_WRAPPER_H

#include <jni.h>
#include <string>
#include <memory>

namespace jni {

/**
 * RAII wrapper for JNIEnv string operations.
 * Automatically releases string resources when out of scope.
 */
class JStringWrapper {
public:
    JStringWrapper(JNIEnv* env, jstring jstr) : env_(env), jstr_(jstr), cstr_(nullptr) {
        if (jstr_) {
            cstr_ = env_->GetStringUTFChars(jstr_, nullptr);
        }
    }
    
    ~JStringWrapper() {
        if (cstr_ && jstr_) {
            env_->ReleaseStringUTFChars(jstr_, cstr_);
        }
    }
    
    // Non-copyable, movable
    JStringWrapper(const JStringWrapper&) = delete;
    JStringWrapper& operator=(const JStringWrapper&) = delete;
    JStringWrapper(JStringWrapper&& other) noexcept 
        : env_(other.env_), jstr_(other.jstr_), cstr_(other.cstr_) {
        other.env_ = nullptr;
        other.jstr_ = nullptr;
        other.cstr_ = nullptr;
    }
    
    const char* get() const { return cstr_; }
    bool is_valid() const { return cstr_ != nullptr; }
    
private:
    JNIEnv* env_;
    jstring jstr_;
    const char* cstr_;
};

/**
 * RAII wrapper for jfloatArray operations.
 * Automatically releases array resources when out of scope.
 */
class JFloatArrayWrapper {
public:
    JFloatArrayWrapper(JNIEnv* env, jfloatArray jarray) 
        : env_(env), jarray_(jarray), data_(nullptr), size_(0) {
        if (jarray_) {
            data_ = env_->GetFloatArrayElements(jarray_, nullptr);
            size_ = env_->GetArrayLength(jarray_);
        }
    }
    
    ~JFloatArrayWrapper() {
        if (data_ && jarray_) {
            env_->ReleaseFloatArrayElements(jarray_, data_, JNI_ABORT);
        }
    }
    
    // Non-copyable, movable
    JFloatArrayWrapper(const JFloatArrayWrapper&) = delete;
    JFloatArrayWrapper& operator=(const JFloatArrayWrapper&) = delete;
    JFloatArrayWrapper(JFloatArrayWrapper&& other) noexcept
        : env_(other.env_), jarray_(other.jarray_), data_(other.data_), size_(other.size_) {
        other.env_ = nullptr;
        other.jarray_ = nullptr;
        other.data_ = nullptr;
        other.size_ = 0;
    }
    
    jfloat* get() { return data_; }
    jsize size() const { return size_; }
    bool is_valid() const { return data_ != nullptr; }
    
private:
    JNIEnv* env_;
    jfloatArray jarray_;
    jfloat* data_;
    jsize size_;
};

/**
 * Exception handling utilities for JNI operations.
 */
class ExceptionChecker {
public:
    static bool has_exception(JNIEnv* env) {
        return env->ExceptionCheck();
    }
    
    static void check_and_throw(JNIEnv* env, const char* context) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            // In a real implementation, you might want to throw a C++ exception
            // or return an error code that can be handled by the caller
            throw std::runtime_error(std::string("JNI Exception in ") + context);
        }
    }
    
    static void throw_new(JNIEnv* env, const char* exception_class, const char* message) {
        jclass clazz = env->FindClass(exception_class);
        if (clazz) {
            env->ThrowNew(clazz, message);
            env->DeleteLocalRef(clazz);
        }
    }
};

} // namespace jni

#endif // JNI_WRAPPER_H
