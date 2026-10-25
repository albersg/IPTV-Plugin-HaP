// Pure checks for the "localfile:" source kind. Every case here fails against the old code, which
// had no notion of a source that is not a URL to download.

#include <gtest/gtest.h>

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>

#include "httpaceproxycpp/local_source.hpp"

namespace {

namespace fs = std::filesystem;
using httpaceproxy::is_local_source;
using httpaceproxy::kLocalSourceMaxBytes;
using httpaceproxy::local_source_path;
using httpaceproxy::read_local_source;

class LocalSourceTest : public ::testing::Test {
protected:
    void SetUp() override {
        dir_ = fs::temp_directory_path() / "hap_local_source_tests";
        std::error_code ignored;
        fs::remove_all(dir_, ignored);
        fs::create_directories(dir_);
    }

    void TearDown() override {
        std::error_code ignored;
        fs::remove_all(dir_, ignored);
    }

    std::string write(const std::string& name, const std::string& contents) {
        fs::path path = dir_ / name;
        std::ofstream out(path, std::ios::binary);
        out.write(contents.data(), static_cast<std::streamsize>(contents.size()));
        out.close();
        return path.string();
    }

    fs::path dir_;
};

TEST_F(LocalSourceTest, RecognisesTheScheme) {
    EXPECT_TRUE(is_local_source("localfile:/data/user/0/app/files/lists/a.m3u"));
    EXPECT_TRUE(is_local_source("LOCALFILE:/data/a.m3u"));
    EXPECT_FALSE(is_local_source("http://example.com/a.m3u"));
    EXPECT_FALSE(is_local_source("https://example.com/a.m3u"));
    EXPECT_FALSE(is_local_source("acestream://0123456789012345678901234567890123456789"));
    EXPECT_FALSE(is_local_source("local"));
    EXPECT_FALSE(is_local_source(""));
}

TEST_F(LocalSourceTest, RejectsLookalikePrefixes) {
    // A remote URL that merely starts with the same letters must not be read from disk.
    EXPECT_FALSE(is_local_source("localfileserver.example.com/a.m3u"));
    EXPECT_FALSE(is_local_source("local:/data/a.m3u"));
}

TEST_F(LocalSourceTest, ExtractsThePath) {
    EXPECT_EQ(local_source_path("localfile:/data/files/lists/a.m3u"), "/data/files/lists/a.m3u");
    EXPECT_EQ(local_source_path("LOCALFILE:/x.m3u"), "/x.m3u");
    EXPECT_EQ(local_source_path("http://example.com/a.m3u"), "");
}

TEST_F(LocalSourceTest, ReadsTheFile) {
    const std::string path = write("ok.m3u", "#EXTM3U\n#EXTINF:-1,Canal\nacestream://abc\n");
    EXPECT_EQ(read_local_source(path), "#EXTM3U\n#EXTINF:-1,Canal\nacestream://abc\n");
}

TEST_F(LocalSourceTest, FailsOnAMissingFile) {
    EXPECT_THROW(read_local_source((dir_ / "nope.m3u").string()), std::runtime_error);
}

TEST_F(LocalSourceTest, FailsOnAnEmptyPath) {
    EXPECT_THROW(read_local_source(""), std::runtime_error);
}

TEST_F(LocalSourceTest, FailsPastTheLimit) {
    // Exactly at the limit is allowed; one byte more is not, which is the case that used to be
    // silently truncated by the fetch path.
    const std::string atLimit = write("limit.m3u", std::string(64, 'x'));
    EXPECT_EQ(read_local_source(atLimit, 64).size(), 64u);

    const std::string overLimit = write("over.m3u", std::string(65, 'x'));
    EXPECT_THROW(read_local_source(overLimit, 64), std::runtime_error);
}

TEST_F(LocalSourceTest, DefaultLimitMatchesTheValidator) {
    EXPECT_EQ(kLocalSourceMaxBytes, 10u * 1024u * 1024u);
}

}  // namespace
