#include "mp4_inspector.h"
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace bilidl {
namespace {
constexpr uint64_t kMaxSampleCount = 50000000ULL;
constexpr uint32_t kMaxTableEntries = 1000000U;
constexpr size_t kMaxChildBoxes = 100000U;
struct Box { std::string type; uint64_t start = 0; uint64_t payload = 0; uint64_t end = 0; };
struct MdatRange { uint64_t start; uint64_t end; };
struct StscEntry { uint32_t firstChunk; uint32_t samplesPerChunk; uint32_t sampleDescriptionIndex; };

uint32_t readU32(const unsigned char* p) {
    return (static_cast<uint32_t>(p[0]) << 24U) | (static_cast<uint32_t>(p[1]) << 16U) |
           (static_cast<uint32_t>(p[2]) << 8U) | static_cast<uint32_t>(p[3]);
}
uint64_t readU64(const unsigned char* p) {
    return (static_cast<uint64_t>(readU32(p)) << 32U) | readU32(p + 4);
}
bool readExact(int fd, uint64_t offset, void* destination, size_t length) {
    auto* out = static_cast<unsigned char*>(destination);
    size_t done = 0;
    while (done < length) {
        const ssize_t count = pread(fd, out + done, length - done, static_cast<off_t>(offset + done));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        done += static_cast<size_t>(count);
    }
    return true;
}
bool readBox(int fd, uint64_t position, uint64_t parentEnd, Box* out, std::string* error) {
    if (parentEnd < position || parentEnd - position < 8) {
        *error = "box header is truncated"; return false;
    }
    unsigned char header[16] = {};
    if (!readExact(fd, position, header, 8)) {
        *error = "cannot read box header"; return false;
    }
    uint64_t size = readU32(header);
    uint64_t headerSize = 8;
    if (size == 1) {
        if (parentEnd - position < 16 || !readExact(fd, position + 8, header + 8, 8)) {
            *error = "extended box header is truncated"; return false;
        }
        size = readU64(header + 8); headerSize = 16;
    } else if (size == 0) {
        size = parentEnd - position;
    }
    if (size < headerSize || size > parentEnd - position) {
        *error = "box size is out of bounds"; return false;
    }
    out->type.assign(reinterpret_cast<const char*>(header + 4), 4);
    out->start = position; out->payload = position + headerSize; out->end = position + size;
    return true;
}
bool readChildren(int fd, uint64_t start, uint64_t end, std::vector<Box>* boxes, std::string* error) {
    uint64_t position = start;
    boxes->clear();
    while (position < end) {
        if (boxes->size() >= kMaxChildBoxes) { *error = "too many child boxes"; return false; }
        Box child;
        if (!readBox(fd, position, end, &child, error)) return false;
        if (child.end <= position) { *error = "child box did not advance"; return false; }
        boxes->push_back(child); position = child.end;
    }
    return position == end;
}
const Box* findBox(const std::vector<Box>& boxes, const char* type) {
    const Box* found = nullptr;
    for (const Box& box : boxes) {
        if (box.type == type) {
            if (found != nullptr) return nullptr;
            found = &box;
        }
    }
    return found;
}
bool readTableHeader(int fd, const Box& box, uint32_t* count, uint64_t* entriesStart,
                     uint32_t width, std::string* error) {
    if (box.end - box.payload < 8) { *error = box.type + " header is truncated"; return false; }
    unsigned char header[8] = {};
    if (!readExact(fd, box.payload, header, 8)) { *error = "cannot read " + box.type; return false; }
    *count = readU32(header + 4);
    if (*count > kMaxTableEntries) { *error = box.type + " has too many entries"; return false; }
    const uint64_t expected = 8ULL + static_cast<uint64_t>(*count) * width;
    if (expected != box.end - box.payload) { *error = box.type + " table length mismatch"; return false; }
    *entriesStart = box.payload + 8;
    return true;
}
bool mdatContains(const std::vector<MdatRange>& ranges, uint64_t offset, uint64_t length) {
    for (const MdatRange& range : ranges) {
        if (offset >= range.start && offset <= range.end && length <= range.end - offset) return true;
    }
    return false;
}
bool validateStsd(int fd, const Box& stsd, uint32_t* descriptions, std::string* error) {
    if (stsd.end - stsd.payload < 8) { *error = "stsd table is truncated"; return false; }
    unsigned char header[8] = {};
    if (!readExact(fd, stsd.payload, header, 8)) { *error = "cannot read stsd"; return false; }
    *descriptions = readU32(header + 4);
    if (*descriptions == 0 || *descriptions > 1024) { *error = "invalid stsd entry count"; return false; }
    std::vector<Box> entries;
    if (!readChildren(fd, stsd.payload + 8, stsd.end, &entries, error)) return false;
    if (entries.size() != *descriptions) { *error = "stsd entry count mismatch"; return false; }
    return true;
}

bool validateTrack(int fd, const Box& trak, const std::vector<MdatRange>& mdats,
                   uint64_t* sampleTotal, bool* mediaTrack, std::string* error) {
    std::vector<Box> trakBoxes;
    if (!readChildren(fd, trak.payload, trak.end, &trakBoxes, error)) return false;
    const Box* mdia = findBox(trakBoxes, "mdia");
    if (mdia == nullptr) { *error = "trak has no unique mdia"; return false; }
    std::vector<Box> mdiaBoxes;
    if (!readChildren(fd, mdia->payload, mdia->end, &mdiaBoxes, error)) return false;
    const Box* hdlr = findBox(mdiaBoxes, "hdlr");
    const Box* minf = findBox(mdiaBoxes, "minf");
    if (hdlr == nullptr || minf == nullptr || hdlr->end - hdlr->payload < 12) {
        *error = "media track is missing hdlr/minf"; return false;
    }
    unsigned char handler[4] = {};
    if (!readExact(fd, hdlr->payload + 8, handler, 4)) { *error = "cannot read track handler"; return false; }
    const std::string handlerType(reinterpret_cast<const char*>(handler), 4);
    *mediaTrack = handlerType == "vide" || handlerType == "soun";
    if (!*mediaTrack) { *sampleTotal = 0; return true; }

    std::vector<Box> minfBoxes;
    if (!readChildren(fd, minf->payload, minf->end, &minfBoxes, error)) return false;
    const Box* stbl = findBox(minfBoxes, "stbl");
    if (stbl == nullptr) { *error = "audio/video track has no stbl"; return false; }
    std::vector<Box> stblBoxes;
    if (!readChildren(fd, stbl->payload, stbl->end, &stblBoxes, error)) return false;

    const Box* stsd = findBox(stblBoxes, "stsd");
    const Box* stts = findBox(stblBoxes, "stts");
    const Box* stsc = findBox(stblBoxes, "stsc");
    const Box* stsz = findBox(stblBoxes, "stsz");
    const Box* stco = findBox(stblBoxes, "stco");
    const Box* co64 = findBox(stblBoxes, "co64");
    if (stsd == nullptr || stts == nullptr || stsc == nullptr || stsz == nullptr ||
        ((stco == nullptr) == (co64 == nullptr))) {
        *error = "required sample table is missing (stsd/stts/stsc/stsz/stco)";
        return false;
    }
    uint32_t descriptionCount = 0;
    if (!validateStsd(fd, *stsd, &descriptionCount, error)) return false;

    if (stsz->end - stsz->payload < 12) { *error = "stsz is truncated"; return false; }
    unsigned char sizeHeader[12] = {};
    if (!readExact(fd, stsz->payload, sizeHeader, 12)) { *error = "cannot read stsz"; return false; }
    const uint32_t constantSize = readU32(sizeHeader + 4);
    const uint32_t sampleCount = readU32(sizeHeader + 8);
    if (sampleCount == 0 || sampleCount > kMaxSampleCount) { *error = "invalid sample count"; return false; }
    const uint64_t expectedSize = 12ULL + (constantSize == 0 ? static_cast<uint64_t>(sampleCount) * 4ULL : 0ULL);
    if (expectedSize != stsz->end - stsz->payload) { *error = "stsz length mismatch"; return false; }

    const Box* chunkTable = stco != nullptr ? stco : co64;
    const uint32_t offsetWidth = stco != nullptr ? 4U : 8U;
    uint32_t chunkCount = 0; uint64_t chunkEntries = 0;
    if (!readTableHeader(fd, *chunkTable, &chunkCount, &chunkEntries, offsetWidth, error)) return false;
    if (chunkCount == 0) { *error = "track contains no chunks"; return false; }

    uint32_t stscCount = 0; uint64_t stscEntries = 0;
    if (!readTableHeader(fd, *stsc, &stscCount, &stscEntries, 12, error)) return false;
    if (stscCount == 0) { *error = "stsc is empty"; return false; }
    std::vector<StscEntry> mappings;
    mappings.reserve(stscCount);
    for (uint32_t i = 0; i < stscCount; ++i) {
        unsigned char bytes[12] = {};
        if (!readExact(fd, stscEntries + static_cast<uint64_t>(i) * 12ULL, bytes, 12)) {
            *error = "cannot read stsc entry"; return false;
        }
        StscEntry mapping{readU32(bytes), readU32(bytes + 4), readU32(bytes + 8)};
        if (mapping.firstChunk == 0 || mapping.samplesPerChunk == 0 ||
            mapping.sampleDescriptionIndex == 0 || mapping.sampleDescriptionIndex > descriptionCount ||
            (i == 0 && mapping.firstChunk != 1) ||
            (!mappings.empty() && mapping.firstChunk <= mappings.back().firstChunk) ||
            mapping.firstChunk > chunkCount) {
            *error = "invalid stsc chunk mapping"; return false;
        }
        mappings.push_back(mapping);
    }

    uint32_t sttsCount = 0; uint64_t sttsEntries = 0;
    if (!readTableHeader(fd, *stts, &sttsCount, &sttsEntries, 8, error)) return false;
    if (sttsCount == 0) { *error = "stts is empty"; return false; }
    uint64_t timedSamples = 0;
    for (uint32_t i = 0; i < sttsCount; ++i) {
        unsigned char entry[8] = {};
        if (!readExact(fd, sttsEntries + static_cast<uint64_t>(i) * 8ULL, entry, 8)) {
            *error = "cannot read stts entry"; return false;
        }
        const uint32_t count = readU32(entry);
        const uint32_t delta = readU32(entry + 4);
        if (count == 0 || delta == 0 || timedSamples > std::numeric_limits<uint64_t>::max() - count) {
            *error = "invalid sample count or duration in stts"; return false;
        }
        timedSamples += count;
    }
    if (timedSamples != sampleCount) { *error = "stts and stsz sample counts disagree"; return false; }

    const Box* ctts = findBox(stblBoxes, "ctts");
    if (ctts != nullptr) {
        uint32_t count = 0; uint64_t entries = 0;
        if (!readTableHeader(fd, *ctts, &count, &entries, 8, error)) return false;
        uint64_t samples = 0;
        for (uint32_t i = 0; i < count; ++i) {
            unsigned char entry[8] = {};
            if (!readExact(fd, entries + static_cast<uint64_t>(i) * 8ULL, entry, 8)) {
                *error = "cannot read ctts entry"; return false;
            }
            const uint32_t n = readU32(entry);
            if (n == 0 || samples > std::numeric_limits<uint64_t>::max() - n) {
                *error = "invalid ctts sample count"; return false;
            }
            samples += n;
        }
        if (samples != sampleCount) { *error = "ctts and stsz sample counts disagree"; return false; }
    }

    const Box* stss = findBox(stblBoxes, "stss");
    if (stss != nullptr) {
        uint32_t count = 0; uint64_t entries = 0;
        if (!readTableHeader(fd, *stss, &count, &entries, 4, error)) return false;
        uint32_t previous = 0;
        for (uint32_t i = 0; i < count; ++i) {
            unsigned char entry[4] = {};
            if (!readExact(fd, entries + static_cast<uint64_t>(i) * 4ULL, entry, 4)) {
                *error = "cannot read stss entry"; return false;
            }
            const uint32_t number = readU32(entry);
            if (number == 0 || number > sampleCount || number <= previous) {
                *error = "stss contains invalid sync sample index"; return false;
            }
            previous = number;
        }
    }

    uint64_t sampleIndex = 0;
    size_t mappingIndex = 0;
    for (uint32_t chunk = 1; chunk <= chunkCount; ++chunk) {
        while (mappingIndex + 1 < mappings.size() && mappings[mappingIndex + 1].firstChunk <= chunk) {
            ++mappingIndex;
        }
        const uint32_t samplesInChunk = mappings[mappingIndex].samplesPerChunk;
        if (sampleIndex + samplesInChunk > sampleCount) {
            *error = "stsc describes more samples than stsz"; return false;
        }
        unsigned char offsetBytes[8] = {};
        if (!readExact(fd, chunkEntries + static_cast<uint64_t>(chunk - 1) * offsetWidth,
                       offsetBytes, offsetWidth)) {
            *error = "cannot read chunk offset"; return false;
        }
        const uint64_t chunkOffset = offsetWidth == 4 ? readU32(offsetBytes) : readU64(offsetBytes);
        uint64_t chunkBytes = 0;
        for (uint32_t n = 0; n < samplesInChunk; ++n) {
            uint32_t size = constantSize;
            if (constantSize == 0) {
                unsigned char sizeBytes[4] = {};
                if (!readExact(fd, stsz->payload + 12 + sampleIndex * 4ULL, sizeBytes, 4)) {
                    *error = "cannot read sample size"; return false;
                }
                size = readU32(sizeBytes);
            }
            if (size == 0 || chunkBytes > std::numeric_limits<uint64_t>::max() - size) {
                *error = "zero-sized sample or size overflow"; return false;
            }
            chunkBytes += size; ++sampleIndex;
        }
        if (!mdatContains(mdats, chunkOffset, chunkBytes)) {
            *error = "chunk offset points outside mdat media payload"; return false;
        }
    }
    if (sampleIndex != sampleCount) { *error = "stsc and stsz sample counts disagree"; return false; }
    *sampleTotal = sampleCount;
    return true;
}
}  // namespace

Mp4Inspection InspectMp4(const std::string& path) {
    Mp4Inspection result{false, "unknown MP4 validation error", 0, 0};
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) { result.message = "cannot open media file"; return result; }
    struct stat info {};
    if (fstat(fd, &info) != 0 || info.st_size < 24) {
        close(fd); result.message = "file is too small or size is unavailable"; return result;
    }
    std::vector<Box> top;
    std::string error;
    if (!readChildren(fd, 0, static_cast<uint64_t>(info.st_size), &top, &error)) {
        close(fd); result.message = "top-level box error: " + error; return result;
    }
    const Box* ftyp = findBox(top, "ftyp");
    const Box* moov = findBox(top, "moov");
    if (ftyp == nullptr || moov == nullptr) {
        close(fd); result.message = "missing unique ftyp or moov box"; return result;
    }
    if (ftyp->end - ftyp->payload < 8 || (ftyp->end - ftyp->payload - 8) % 4 != 0) {
        close(fd); result.message = "invalid ftyp brand table"; return result;
    }
    std::vector<MdatRange> mdats;
    for (const Box& box : top) {
        if (box.type == "mdat") {
            if (box.end <= box.payload) {
                close(fd); result.message = "mdat contains no media payload"; return result;
            }
            mdats.push_back({box.payload, box.end});
        }
    }
    if (mdats.empty()) { close(fd); result.message = "missing mdat media data"; return result; }

    std::vector<Box> moovBoxes;
    if (!readChildren(fd, moov->payload, moov->end, &moovBoxes, &error)) {
        close(fd); result.message = "moov box error: " + error; return result;
    }
    if (findBox(moovBoxes, "mvhd") == nullptr) {
        close(fd); result.message = "moov is missing mvhd"; return result;
    }

    uint32_t tracks = 0;
    uint64_t samples = 0;
    for (const Box& child : moovBoxes) {
        if (child.type != "trak") continue;
        uint64_t trackSamples = 0;
        bool mediaTrack = false;
        if (!validateTrack(fd, child, mdats, &trackSamples, &mediaTrack, &error)) {
            close(fd); result.message = "track validation failed: " + error; return result;
        }
        if (mediaTrack) { ++tracks; samples += trackSamples; }
    }
    close(fd);
    if (tracks == 0 || samples == 0) {
        result.message = "moov contains no usable audio/video samples"; return result;
    }
    result.valid = true; result.trackCount = tracks; result.sampleCount = samples;
    result.message = "OK: tracks=" + std::to_string(tracks) + " samples=" + std::to_string(samples);
    return result;
}
}  // namespace bilidl
