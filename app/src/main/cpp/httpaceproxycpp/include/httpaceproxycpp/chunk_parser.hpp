#pragma once

#include <cstddef>
#include <string>

namespace httpace {

// Largest body chunk accepted from an upstream HTTP response. Real chunked encoders
// emit 4-64 KiB; the cap exists so a hostile size cannot reach the caller's
// `chunk_size + 2` addition and wrap it to a small number.
inline constexpr std::size_t kMaxChunkBytes = 16u * 1024u * 1024u;

// Parses a chunked-transfer size line, with or without a `;extension` suffix.
// Returns false for anything that is not a plain hexadecimal size within the cap.
bool parse_chunk_size(const std::string& line, std::size_t& size);

} // namespace httpace
