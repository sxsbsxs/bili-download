#pragma once
#include <cstdint>
#include <string>
namespace bilidl {
struct Mp4Inspection { bool valid; std::string message; uint32_t trackCount; uint64_t sampleCount; };
Mp4Inspection InspectMp4(const std::string& path);
}  // namespace bilidl
