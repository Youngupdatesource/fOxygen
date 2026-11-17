// Wire protocol between the native `axsh` client and whoever serves it: the server's own socket
// (@axeron.<hex>, AxshServer), an app's broker/daemon (@axsh.<uid>), or Bootstrap over a socketpair.
#pragma once

#include <cstdint>

namespace axsh {

inline constexpr char SERVER_NAME_FILE[] = ".axsh-server";
inline constexpr char BROKER_PREFIX[] = "axsh.";
inline constexpr char BOOTSTRAP_CLASS[] = "frb.axeron.axsh.Bootstrap";
inline constexpr char DAEMON_FLAG[] = "--axsh-daemon";

inline constexpr int PIPE_FDS = 3;
inline constexpr int PTY_FDS = 2;

inline constexpr const char* PTY_ENV[] = {"TERM", "COLORTERM", "LANG", "LC_"};

inline constexpr char MAGIC[4] = {'A', 'X', 'S', 'H'};
inline constexpr uint8_t VERSION = 1;

enum Flags : uint8_t {
    FLAG_PTY = 1u << 0,
    FLAG_LOGIN = 1u << 1,
};

enum Status : uint8_t {
    STATUS_OK = 0,
    STATUS_DENIED = 1,
    STATUS_ERROR = 2,
};

enum Frame : uint8_t {
    FRAME_EXIT = 0x02,
    FRAME_WINCH = 0x03,
};

}  // namespace axsh
