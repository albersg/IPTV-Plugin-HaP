// Regression tests for the chunked-transfer size parser.
//
// A security audit of this branch found that an unbounded strtoull fed a caller loop
// of the form `while (pending.size() < chunk_size + 2)`. A size near SIZE_MAX wrapped
// that addition to 1, skipped the fill loop and passed a huge length to the chunk
// callback. The parser now rejects ERANGE and anything above kMaxChunkBytes; these
// tests pin both the bound and the ordinary formatting cases.

#include "httpaceproxycpp/chunk_parser.hpp"
#include "httpaceproxycpp/util.hpp"

#include <gtest/gtest.h>

#include <cstddef>
#include <limits>
#include <string>

namespace {

bool parse(const std::string& line, std::size_t& out) {
    return httpace::parse_chunk_size(line, out);
}

} // namespace

TEST(ChunkParser, ReadsPlainHexSize) {
    std::size_t size = 0;
    ASSERT_TRUE(parse("40", size));
    EXPECT_EQ(size, 64u);
}

TEST(ChunkParser, ReadsUppercaseHex) {
    std::size_t size = 0;
    ASSERT_TRUE(parse("A", size));
    EXPECT_EQ(size, 10u);
}

TEST(ChunkParser, IgnoresChunkExtension) {
    std::size_t size = 0;
    ASSERT_TRUE(parse("40;ext=value", size));
    EXPECT_EQ(size, 64u);
}

TEST(ChunkParser, ToleratesSurroundingWhitespace) {
    std::size_t size = 0;
    ASSERT_TRUE(parse("  40  ", size));
    EXPECT_EQ(size, 64u);
}

TEST(ChunkParser, FinalChunkIsZeroAndValid) {
    std::size_t size = 123;
    ASSERT_TRUE(parse("0", size));
    EXPECT_EQ(size, 0u);
}

TEST(ChunkParser, RejectsNonHex) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("zz", size));
}

TEST(ChunkParser, RejectsEmpty) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("", size));
}

TEST(ChunkParser, RejectsTrailingGarbage) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("40x", size));
}

// The audit's payload: the largest value strtoull can saturate at. Adding 2 to it wraps.
TEST(ChunkParser, RejectsSaturatedSizeThatWouldWrapTheAddition) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("ffffffffffffffff", size));
}

TEST(ChunkParser, RejectsInt64MaxAsASize) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("7fffffffffffffff", size));
}

TEST(ChunkParser, AcceptsExactlyTheCap) {
    std::size_t size = 0;
    ASSERT_TRUE(parse("1000000", size));   // 16 MiB
    EXPECT_EQ(size, httpace::kMaxChunkBytes);
}

TEST(ChunkParser, RejectsOneByteOverTheCap) {
    std::size_t size = 0;
    EXPECT_FALSE(parse("1000001", size));
}
