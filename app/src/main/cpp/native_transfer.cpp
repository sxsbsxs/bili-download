#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace {

uint32_t readU32(const unsigned char* value) {
    return (static_cast<uint32_t>(value[0]) << 24U) |
           (static_cast<uint32_t>(value[1]) << 16U) |
           (static_cast<uint32_t>(value[2]) << 8U) |
           static_cast<uint32_t>(value[3]);
}

uint64_t readU64(const unsigned char* value) {
    return (static_cast<uint64_t>(readU32(value)) << 32U) |
           static_cast<uint64_t>(readU32(value + 4));
}

bool hasType(const unsigned char* header, const char* type) {
    return std::memcmp(header + 4, type, 4) == 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_bilidl_NativeTransfer_writeAt(
        JNIEnv* env, jclass, jint fd, jlong offset, jbyteArray bytes) {
    if (fd < 0 || offset < 0 || bytes == nullptr) return -EINVAL;

    const jsize length = env->GetArrayLength(bytes);
    if (length == 0) return 0;

    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (data == nullptr) return -ENOMEM;

    jsize written = 0;
    while (written < length) {
        const ssize_t count = pwrite(
                fd,
                data + written,
                static_cast<size_t>(length - written),
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bilidl_NativeTransfer_validateMp4(
        JNIEnv* env, jclass, jstring pathString) {
    if (pathString == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(pathString, nullptr);
    if (path == nullptr) return JNI_FALSE;

    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(pathString, path);
    if (fd < 0) return JNI_FALSE;

    struct stat fileInfo {};
    if (fstat(fd, &fileInfo) != 0 || fileInfo.st_size < 16) {
        close(fd);
        return JNI_FALSE;
    }

    const uint64_t fileSize = static_cast<uint64_t>(fileInfo.st_size);
    uint64_t offset = 0;
    bool hasFtyp = false;
    bool hasMoov = false;
    bool hasMdat = false;
    unsigned char header[16] {};

    while (offset + 8 <= fileSize) {
        const ssize_t readCount = pread(fd, header, 8, static_cast<off_t>(offset));
        if (readCount != 8) break;

        uint64_t boxSize = readU32(header);
        const bool extended = boxSize == 1;
        const bool toEnd = boxSize == 0;
        uint64_t headerSize = 8;

        if (extended) {
            if (offset + 16 > fileSize ||
                pread(fd, header + 8, 8, static_cast<off_t>(offset + 8)) != 8) {
                break;
            }
            boxSize = readU64(header + 8);
            headerSize = 16;
        } else if (toEnd) {
            boxSize = fileSize - offset;
        }

        if (boxSize < headerSize || boxSize > fileSize - offset) break;

        hasFtyp = hasFtyp || hasType(header, "ftyp");
        hasMoov = hasMoov || hasType(header, "moov");
        hasMdat = hasMdat || hasType(header, "mdat");
        offset += boxSize;
    }

    close(fd);
    return (hasFtyp && hasMoov && hasMdat && offset == fileSize)
            ? JNI_TRUE : JNI_FALSE;
}
