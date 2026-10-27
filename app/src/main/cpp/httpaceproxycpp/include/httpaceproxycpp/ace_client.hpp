#pragma once

#include "httpaceproxycpp/config.hpp"
#include "httpaceproxycpp/json.hpp"
#include "httpaceproxycpp/prebuffer_watchdog.hpp"

#include <atomic>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

namespace httpace {

class AceError : public std::runtime_error {
public:
    explicit AceError(const std::string& message) : std::runtime_error(message) {}
};

struct AceStartParams {
    std::string url;
    std::string infohash;
    std::string file_index;
    bool stream = false;
    std::map<std::string, std::string> fields;
};

class AceClient {
public:
    explicit AceClient(Config config, std::string title = "idleAce");
    ~AceClient();

    AceClient(const AceClient&) = delete;
    AceClient& operator=(const AceClient&) = delete;

    void authenticate();
    Json load_async(const std::map<std::string, std::string>& params);
    Json content_info(const std::map<std::string, std::string>& params);
    AceStartParams start_broadcast(const std::map<std::string, std::string>& params);
    std::map<std::string, std::string> status(int timeout_seconds = 1);
    void stop_broadcast();
    void shutdown();
    bool alive() const { return running_; }

    /**
     * Ends a session that has been prebuffering without progress for {@code seconds}.
     *
     * <p>0 disables it. It runs inside the reader thread, which is the only thread awake while the
     * engine sends nothing: a session with no data gives the proxy's own reader nothing to observe.
     * Ending the session is all it does; the bounded retry decides what happens next.
     */
    void set_silence_watchdog(int seconds);

private:
    void connect_socket();
    void reader_loop();
    void write_line(const std::string& line);
    std::vector<std::string> wait_for(const std::string& command, int timeout_seconds);
    void push_message(const std::vector<std::string>& tokens);
    static std::map<std::string, std::string> parse_key_values(const std::vector<std::string>& tokens, std::size_t start);
    static std::string command_loadasync(const std::map<std::string, std::string>& params);
    static std::string command_start(const std::map<std::string, std::string>& params);

    Config config_;
    std::string title_;
    int fd_ = -1;
    std::atomic<bool> running_{false};
    std::thread reader_;
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::map<std::string, std::deque<std::vector<std::string>>> messages_;
    std::atomic<int> silent_seconds_{0};
    std::atomic<std::int64_t> last_progress_ms_{0};
    std::atomic<bool> prebuffering_{false};
    std::atomic<bool> watchdog_tripped_{false};
};

} // namespace httpace
