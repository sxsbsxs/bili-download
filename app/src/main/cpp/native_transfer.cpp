#include "mp4_inspector.h"
#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <string>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_example_bilidl_NativeTransfer_writeAt(JNIEnv* env, jclass, jint fd, jlong offset, jbyteArray bytes) {
    if (fd < 0 || offset < 0 || bytes == nullptr) return -EINVAL;
    const jsize length = env->GetArrayLength(bytes);
    if (length == 0) return 0;
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (data == nullptr) return -ENOMEM;
    jsize written = 0;
    while (written < length) {
        const ssize_t count = pwrite(fd, data + written, static_cast<size_t>(length - written),
                                     static_cast<off_t>(offset + written));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            const int error = (count == 0) ? EIO : errno;
            env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
            return -error;
        }
        written += static_cast<jsize>(count);
    }
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return written;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bilidl_NativeTransfer_sync(JNIEnv*, jclass, jint fd) {
    if (fd < 0) return JNI_FALSE;
    return fsync(fd) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bilidl_NativeTransfer_inspectMp4(JNIEnv* env, jclass, jstring pathString) {
    if (pathString == nullptr) return env->NewStringUTF("INVALID: path is null");
    const char* path = env->GetStringUTFChars(pathString, nullptr);
    if (path == nullptr) return env->NewStringUTF("INVALID: cannot read path");
    const bilidl::Mp4Inspection inspection = bilidl::InspectMp4(path);
    env->ReleaseStringUTFChars(pathString, path);
    return env->NewStringUTF(inspection.message.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bilidl_NativeTransfer_validateMp4(JNIEnv* env, jclass, jstring pathString) {
    if (pathString == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(pathString, nullptr);
    if (path == nullptr) return JNI_FALSE;
    const bool valid = bilidl::InspectMp4(path).valid;
    env->ReleaseStringUTFChars(pathString, path);
    return valid ? JNI_TRUE : JNI_FALSE;
}
