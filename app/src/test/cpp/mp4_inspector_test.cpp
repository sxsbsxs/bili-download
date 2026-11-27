#include "mp4_inspector.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>
#include <vector>

namespace {
using Bytes = std::vector<unsigned char>;

void appendU32(Bytes* out, uint32_t value) {
    out->push_back(static_cast<unsigned char>((value >> 24U) & 0xffU));
    out->push_back(static_cast<unsigned char>((value >> 16U) & 0xffU));
    out->push_back(static_cast<unsigned char>((value >> 8U) & 0xffU));
    out->push_back(static_cast<unsigned char>(value & 0xffU));
}
void appendText(Bytes* out, const std::string& value) {
    out->insert(out->end(), value.begin(), value.end());
}
Bytes makeBox(const char* type, const Bytes& payload) {
    Bytes result;
    appendU32(&result, static_cast<uint32_t>(payload.size() + 8));
    appendText(&result, std::string(type, 4));
    result.insert(result.end(), payload.begin(), payload.end());
    return result;
}
void append(Bytes* target, const Bytes& value) {
    target->insert(target->end(), value.begin(), value.end());
}
Bytes fullBox() { return Bytes{0, 0, 0, 0}; }

Bytes makeStbl(uint32_t chunkOffset, bool mismatchTiming = false) {
    Bytes stsd = fullBox();
    appendU32(&stsd, 1);
    append(&stsd, makeBox("avc1", Bytes{}));
    Bytes stts = fullBox();
    appendU32(&stts, 1);
    appendU32(&stts, mismatchTiming ? 2 : 1);
    appendU32(&stts, 1000);
    Bytes stsc = fullBox();
    appendU32(&stsc, 1);
    appendU32(&stsc, 1);
    appendU32(&stsc, 1);
    appendU32(&stsc, 1);
    Bytes stsz = fullBox();
    appendU32(&stsz, 4);  // Constant sample size.
    appendU32(&stsz, 1);
    Bytes stco = fullBox();
    appendU32(&stco, 1);
    appendU32(&stco, chunkOffset);

    Bytes payload;
    append(&payload, makeBox("stsd", stsd));
    append(&payload, makeBox("stts", stts));
    append(&payload, makeBox("stsc", stsc));
    append(&payload, makeBox("stsz", stsz));
    append(&payload, makeBox("stco", stco));
    return makeBox("stbl", payload);
}

Bytes makeFile(bool validOffset, bool mismatchTiming = false) {
    Bytes ftypPayload;
    appendText(&ftypPayload, "isom");
    appendU32(&ftypPayload, 0);
    appendText(&ftypPayload, "isom");
    const Bytes ftyp = makeBox("ftyp", ftypPayload);

    Bytes hdlrPayload = fullBox();
    appendU32(&hdlrPayload, 0);
    appendText(&hdlrPayload, "vide");
    appendU32(&hdlrPayload, 0);
    appendU32(&hdlrPayload, 0);
    appendU32(&hdlrPayload, 0);
    Bytes minfPayload;
    append(&minfPayload, makeStbl(0, mismatchTiming));
    Bytes mdiaPayload;
    append(&mdiaPayload, makeBox("hdlr", hdlrPayload));
    append(&mdiaPayload, makeBox("minf", minfPayload));
    Bytes trakPayload;
    append(&trakPayload, makeBox("mdia", mdiaPayload));
    Bytes moovPayload;
    append(&moovPayload, makeBox("mvhd", Bytes(16, 0)));
    const size_t trackBoxStart = moovPayload.size();
    append(&moovPayload, makeBox("trak", trakPayload));
    Bytes moov = makeBox("moov", moovPayload);

    const uint32_t mdatPayloadOffset = static_cast<uint32_t>(
        ftyp.size() + moov.size() + 8);
    const uint32_t chosenOffset = validOffset ? mdatPayloadOffset : mdatPayloadOffset + 1000;
    // Rebuild with the final absolute chunk offset. The box lengths stay unchanged.
    minfPayload.clear();
    append(&minfPayload, makeStbl(chosenOffset, mismatchTiming));
    mdiaPayload.clear();
    append(&mdiaPayload, makeBox("hdlr", hdlrPayload));
    append(&mdiaPayload, makeBox("minf", minfPayload));
    trakPayload.clear();
    append(&trakPayload, makeBox("mdia", mdiaPayload));
    moovPayload.clear();
    append(&moovPayload, makeBox("mvhd", Bytes(16, 0)));
    append(&moovPayload, makeBox("trak", trakPayload));
    moov = makeBox("moov", moovPayload);
    (void)trackBoxStart;

    Bytes file;
    append(&file, ftyp);
    append(&file, moov);
    append(&file, makeBox("mdat", Bytes{0x11, 0x22, 0x33, 0x44}));
    return file;
}

std::string writeTemp(const Bytes& bytes) {
    char pattern[] = "/tmp/bilidl-mp4-test-XXXXXX";
    const int fd = mkstemp(pattern);
    assert(fd >= 0);
    size_t offset = 0;
    while (offset < bytes.size()) {
        const ssize_t count = write(fd, bytes.data() + offset, bytes.size() - offset);
        assert(count > 0);
        offset += static_cast<size_t>(count);
    }
    close(fd);
    return pattern;
}
}  // namespace

int main() {
    const std::string validPath = writeTemp(makeFile(true));
    const bilidl::Mp4Inspection valid = bilidl::InspectMp4(validPath);
    assert(valid.valid);
    assert(valid.trackCount == 1);
    assert(valid.sampleCount == 1);
    unlink(validPath.c_str());

    const std::string badOffsetPath = writeTemp(makeFile(false));
    const bilidl::Mp4Inspection badOffset = bilidl::InspectMp4(badOffsetPath);
    assert(!badOffset.valid);
    assert(badOffset.message.find("outside mdat") != std::string::npos);
    unlink(badOffsetPath.c_str());

    const std::string badTimingPath = writeTemp(makeFile(true, true));
    const bilidl::Mp4Inspection badTiming = bilidl::InspectMp4(badTimingPath);
    assert(!badTiming.valid);
    assert(badTiming.message.find("stts and stsz") != std::string::npos);
    unlink(badTimingPath.c_str());

    Bytes truncated = makeFile(true);
    truncated.pop_back();
    const std::string truncatedPath = writeTemp(truncated);
    const bilidl::Mp4Inspection truncatedResult = bilidl::InspectMp4(truncatedPath);
    assert(!truncatedResult.valid);
    unlink(truncatedPath.c_str());

    std::puts("Native MP4 inspector tests passed");
    return 0;
}
