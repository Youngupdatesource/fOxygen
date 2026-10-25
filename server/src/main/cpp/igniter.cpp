// Native port of frb.axeron.reignite.Igniter (v1.2): the plugin igniter and the server's
// death watchdog ("--stop-all"), built into libaxeron.so instead of a JVM in app_process.
//
// Behaviour, rules and output lines follow Igniter.kt one to one so that the two stay
// interchangeable: the plugin service, functions.sh and the server watchdog can run either.
//
//   libaxeron.so --igniter [true|false]    ignite (the argument is the developer-options flag)
//   libaxeron.so --igniter --stop-all      stop every plugin's processes, nothing else
//
// starter.cpp's main hands over to igniter_main before anything else (before its uid check).

// Environment: AXERONDIR (required), AXERONXBIN (optional). Needs `resetprop` in PATH, like the
// Kotlin version, and `busybox` for scripts that ask for it.

#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <cerrno>
#include <climits>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <algorithm>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

constexpr const char *VERSION = "v1.2";

// Env var every process of a plugin's service carries (AXERON_PLUGIN=<id>). Children inherit it
// even when they leave the service's session, so stopping can still find them.
constexpr const char *MARKER = "AXERON_PLUGIN";

// How long a service gets to exit on SIGTERM before what's left is SIGKILLed.
constexpr long STOP_GRACE_MS = 3000;

// How long post-fs-data.sh / uninstall.sh may block the igniter. A script still running after
// it is left running; the next plugin goes on.
constexpr int SCRIPT_TIMEOUT_S = 40;

// The shell that only starts a service (setsid ... &, then records the pid) is short-lived.
constexpr int LAUNCH_TIMEOUT_S = 10;

// Never treated as a plugin's process, even if it carries a leaked marker.
constexpr const char *SERVER_PROCESS = "axeron_server";

constexpr const char *BOOT_TAG = "log.tag.ignite.boot";

std::string AXERONDIR;
std::string PLUGINS_DIR;
std::string PLUGINS_UPDATE_DIR;
std::string AXERONXBIN;
std::string LOG_DIR;

// ===============================
// OUTPUT
// ===============================
void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    putchar('\n');
    fflush(stdout);
}

// ===============================
// FILE HELPERS
// ===============================
bool exists(const std::string &path) { return access(path.c_str(), F_OK) == 0; }

bool is_dir(const std::string &path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool lexists(const std::string &path) {
    struct stat st{};
    return lstat(path.c_str(), &st) == 0;
}

bool list_dir(const std::string &path, std::vector<std::string> &out) {
    DIR *d = opendir(path.c_str());
    if (!d) return false;
    while (dirent *e = readdir(d)) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        out.emplace_back(e->d_name);
    }
    closedir(d);
    return true;
}

bool read_file(const std::string &path, std::string &out) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[4096];
    for (;;) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            out.append(buf, static_cast<size_t>(n));
        } else if (n == 0) {
            break;
        } else if (errno != EINTR) {
            close(fd);
            return false;
        }
    }
    close(fd);
    return true;
}

void mkdirs(const std::string &path) {
    std::string cur;
    for (size_t i = 0; i <= path.size(); i++) {
        if (i == path.size() || path[i] == '/') {
            if (!cur.empty()) mkdir(cur.c_str(), 0755);
        }
        if (i < path.size()) cur += path[i];
    }
}

// Symlinks are removed, never followed.
void remove_all(const std::string &path) {
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        std::vector<std::string> names;
        list_dir(path, names);
        for (const auto &n : names) remove_all(path + "/" + n);
        rmdir(path.c_str());
    } else {
        unlink(path.c_str());
    }
}

std::string trim(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && isspace(static_cast<unsigned char>(s[b]))) b++;
    while (e > b && isspace(static_cast<unsigned char>(s[e - 1]))) e--;
    return s.substr(b, e - b);
}

bool starts_with(const std::string &s, const std::string &prefix) {
    return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}

std::vector<std::string> split_nul(const std::string &s) {
    std::vector<std::string> out;
    size_t start = 0;
    for (size_t i = 0; i <= s.size(); i++) {
        if (i == s.size() || s[i] == '\0') {
            out.emplace_back(s, start, i - start);
            start = i + 1;
        }
    }
    return out;
}

long now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

void sleep_ms(long ms) {
    timespec ts{ms / 1000, (ms % 1000) * 1000000L};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {
    }
}

// ===============================
// PROCESSES
// ===============================
using Argv = std::vector<std::string>;

// stdin is /dev/null; stdout and stderr go to out_path (truncated), or /dev/null; every other fd
// is closed, so nothing of the caller's pipes stays open in a service that outlives the igniter.
// Returns the pid, or -1 when the program could not be started (like an IOException).
pid_t spawn(const Argv &argv, const char *marker_value, const char *out_path) {
    std::vector<char *> args;
    for (const auto &a : argv) args.push_back(const_cast<char *>(a.c_str()));
    args.push_back(nullptr);

    int err[2];
    if (pipe2(err, O_CLOEXEC) != 0) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        close(err[0]);
        close(err[1]);
        return -1;
    }

    if (pid == 0) {
        signal(SIGPIPE, SIG_DFL);
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);

        int in = open("/dev/null", O_RDONLY);
        int out = open(out_path ? out_path : "/dev/null", O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (out < 0) out = open("/dev/null", O_WRONLY);
        if (in >= 0) dup2(in, 0);
        if (out >= 0) {
            dup2(out, 1);
            dup2(out, 2);
        }
        for (int fd = 3; fd < 256; fd++) {
            if (fd != err[1]) close(fd);
        }
        if (marker_value) setenv(MARKER, marker_value, 1);

        execvp(args[0], args.data());
        int e = errno;
        ssize_t ignored = write(err[1], &e, sizeof(e));
        (void) ignored;
        _exit(127);
    }

    close(err[1]);
    int e = 0;
    ssize_t n;
    do {
        n = read(err[0], &e, sizeof(e));
    } while (n < 0 && errno == EINTR);
    close(err[0]);
    if (n > 0) {
        int status;
        waitpid(pid, &status, 0);
        return -1;
    }
    return pid;
}

// True when the child exited within the timeout (and is reaped).
bool wait_for(pid_t pid, int seconds) {
    long deadline = now_ms() + seconds * 1000L;
    for (;;) {
        int status;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid || (r < 0 && errno != EINTR)) return true;
        if (now_ms() >= deadline) return false;
        sleep_ms(20);
    }
}

// First line of the program's stdout; empty when it can't run. Never blocks on stderr.
std::string capture_line(const Argv &argv) {
    std::vector<char *> args;
    for (const auto &a : argv) args.push_back(const_cast<char *>(a.c_str()));
    args.push_back(nullptr);

    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return "";
    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return "";
    }
    if (pid == 0) {
        signal(SIGPIPE, SIG_DFL);
        int in = open("/dev/null", O_RDONLY);
        int nul = open("/dev/null", O_WRONLY);
        if (in >= 0) dup2(in, 0);
        dup2(fds[1], 1);
        if (nul >= 0) dup2(nul, 2);
        execvp(args[0], args.data());
        _exit(127);
    }
    close(fds[1]);
    std::string out;
    char buf[256];
    for (;;) {
        ssize_t n = read(fds[0], buf, sizeof(buf));
        if (n > 0) {
            out.append(buf, static_cast<size_t>(n));
        } else if (n == 0 || errno != EINTR) {
            break;
        }
    }
    close(fds[0]);
    int status;
    waitpid(pid, &status, 0);

    size_t nl = out.find('\n');
    if (nl != std::string::npos) out.resize(nl);
    if (!out.empty() && out.back() == '\r') out.pop_back();
    return out;
}

// ===============================
// SYSTEM PROP (resetprop)
// ===============================
std::string prop_get(const std::string &key) { return capture_line({"resetprop", key}); }

void prop_set(const std::string &key, const std::string &value) {
    pid_t pid = spawn({"resetprop", key, value}, nullptr, nullptr);
    if (pid > 0) wait_for(pid, LAUNCH_TIMEOUT_S);
}

// ===============================
// TOOLS (system sh / setsid in preference to busybox's)
// ===============================
bool is_busybox(const std::string &path) {
    char resolved[PATH_MAX];
    if (!realpath(path.c_str(), resolved)) return false;
    const char *base = strrchr(resolved, '/');
    base = base ? base + 1 : resolved;
    return starts_with(base, "busybox");
}

// "" when the system has no such applet of its own.
std::string tool_system(const std::string &applet) {
    static std::unordered_map<std::string, std::string> cache;
    auto it = cache.find(applet);
    if (it != cache.end()) return it->second;
    std::string found;
    for (const char *dir : {"/system/bin", "/system/xbin", "/vendor/bin"}) {
        std::string path = std::string(dir) + "/" + applet;
        if (access(path.c_str(), X_OK) == 0 && !is_busybox(path)) {
            found = path;
            break;
        }
    }
    return cache.emplace(applet, found).first->second;
}

std::string tool_word(const std::string &applet) {
    const std::string sys = tool_system(applet);
    return sys.empty() ? "busybox " + applet : sys;
}

// ===============================
// /proc
// ===============================
struct ProcStat {
    char state;
    int ppid, pgrp, session;
};

// Fields of /proc/<pid>/stat; the name field can hold spaces and ')'.
bool read_stat(int pid, ProcStat &st) {
    std::string raw;
    if (!read_file("/proc/" + std::to_string(pid) + "/stat", raw)) return false;
    size_t close_paren = raw.rfind(')');
    if (close_paren == std::string::npos || close_paren + 2 >= raw.size()) return false;
    char state;
    int ppid, pgrp, session;
    if (sscanf(raw.c_str() + close_paren + 2, "%c %d %d %d", &state, &ppid, &pgrp, &session) != 4)
        return false;
    st = {state, ppid, pgrp, session};
    return true;
}

std::vector<std::string> cmdline(int pid) {
    std::string raw;
    if (!read_file("/proc/" + std::to_string(pid) + "/cmdline", raw)) return {};
    return split_nul(raw);
}

bool is_server(int pid) {
    auto args = cmdline(pid);
    return !args.empty() && args[0] == SERVER_PROCESS;
}

// Which env entries count as a plugin's marker: exactly AXERON_PLUGIN=<name>, or any plugin's.
struct MarkerRule {
    std::string exact;  // AXERON_PLUGIN=<name>
    bool any;           // every AXERON_PLUGIN=...

    bool matches(const std::string &entry) const {
        if (any) return starts_with(entry, std::string(MARKER) + "=");
        return entry == exact;
    }
};

// Unreadable envs (other uids) count as no.
bool has_marker(int pid, const MarkerRule &rule) {
    std::string raw;
    if (!read_file("/proc/" + std::to_string(pid) + "/environ", raw)) return false;
    for (const auto &entry : split_nul(raw)) {
        if (rule.matches(entry)) return true;
    }
    return false;
}

// The igniter and every process above it: stopping a plugin must never kill its caller.
std::unordered_set<int> self_and_ancestors() {
    std::unordered_set<int> chain;
    int pid = getpid();
    while (pid > 1 && chain.insert(pid).second) {
        ProcStat st;
        if (!read_stat(pid, st)) break;
        pid = st.ppid;
    }
    return chain;
}

// Live processes carrying the marker, plus every member of a session or process group led by
// one of them. The second part catches children that cleared their env but stayed with their
// daemon (e.g. crond's jobs). The igniter, its ancestors and the server are never included, nor
// taken as leaders, even with a leaked marker.
std::vector<int> plugin_pids(const std::vector<int> &extra_leaders, const MarkerRule &rule) {
    auto protected_pids = self_and_ancestors();

    std::vector<std::pair<int, ProcStat>> live;
    std::vector<std::string> names;
    list_dir("/proc", names);
    for (const auto &n : names) {
        char *end = nullptr;
        long v = strtol(n.c_str(), &end, 10);
        if (end == n.c_str() || *end != '\0' || v <= 0) continue;
        int pid = static_cast<int>(v);
        if (protected_pids.count(pid)) continue;
        ProcStat st;
        if (!read_stat(pid, st)) continue;
        if (st.state == 'Z') continue;  // already dead, waiting to be reaped
        live.emplace_back(pid, st);
    }

    std::unordered_set<int> marked;
    for (const auto &p : live) {
        if (has_marker(p.first, rule) && !is_server(p.first)) marked.insert(p.first);
    }
    for (int leader : extra_leaders) {
        if (!protected_pids.count(leader) && !is_server(leader)) marked.insert(leader);
    }

    std::vector<int> out;
    for (const auto &p : live) {
        if (marked.count(p.first) || marked.count(p.second.session) || marked.count(p.second.pgrp))
            out.push_back(p.first);
    }
    return out;
}

// The prop is read once per plugin: a stop scans every 100 ms, and each read runs resetprop.
std::unordered_map<std::string, int> legacy_pids;

// A service started by an igniter older than the marker carries none; all that names it is the
// pid in its log.tag.service.<name> prop, the leader of the session setsid made for it. The prop
// outlives its process and pids get reused, so the pid only counts while it is alive, still leads
// its session, and runs this plugin's own files.
std::vector<int> legacy_leaders(const std::string &name) {
    auto it = legacy_pids.find(name);
    if (it == legacy_pids.end()) {
        std::string raw = trim(prop_get("log.tag.service." + name));
        char *end = nullptr;
        long v = strtol(raw.c_str(), &end, 10);
        int pid = (!raw.empty() && end && *end == '\0') ? static_cast<int>(v) : -1;
        it = legacy_pids.emplace(name, pid).first;
    }
    int pid = it->second;
    if (pid <= 1) return {};
    ProcStat st;
    if (!read_stat(pid, st)) return {};
    if (st.state == 'Z' || st.session != pid) return {};
    std::string plugin_dir = PLUGINS_DIR + "/" + name + "/";
    for (const auto &arg : cmdline(pid)) {
        if (starts_with(arg, plugin_dir)) return {pid};
    }
    return {};
}

// nullptr: every plugin.
std::vector<int> scan(const std::string *name) {
    if (name) {
        return plugin_pids(legacy_leaders(*name), {std::string(MARKER) + "=" + *name, false});
    }
    std::vector<int> legacy;
    std::vector<std::string> plugins;
    list_dir(PLUGINS_DIR, plugins);
    for (const auto &p : plugins) {
        for (int pid : legacy_leaders(p)) legacy.push_back(pid);
    }
    return plugin_pids(legacy, {"", true});
}

// Which plugins have live processes, in ONE pass over /proc. A scan per plugin reads the stat,
// environ and cmdline of every process on the device for each plugin; with 20 plugins on a phone
// with ~1000 processes that is tens of thousands of file reads for an answer that one pass gives.
// A process belongs to a plugin like in plugin_pids: it carries the plugin's marker, or its
// session or process group is led by a process that does. The igniter, its ancestors and the
// server are never counted.
std::unordered_map<std::string, std::vector<int>> running_by_plugin() {
    auto protected_pids = self_and_ancestors();

    std::vector<std::pair<int, ProcStat>> live;
    std::vector<std::string> names;
    list_dir("/proc", names);
    for (const auto &n : names) {
        char *end = nullptr;
        long v = strtol(n.c_str(), &end, 10);
        if (end == n.c_str() || *end != '\0' || v <= 0) continue;
        int pid = static_cast<int>(v);
        if (protected_pids.count(pid)) continue;
        ProcStat st;
        if (!read_stat(pid, st)) continue;
        if (st.state == 'Z') continue;
        live.emplace_back(pid, st);
    }

    // pid -> the plugin its marker names.
    const std::string prefix = std::string(MARKER) + "=";
    std::unordered_map<int, std::string> owner;
    for (const auto &p : live) {
        std::string raw;
        if (!read_file("/proc/" + std::to_string(p.first) + "/environ", raw)) continue;
        for (const auto &entry : split_nul(raw)) {
            if (!starts_with(entry, prefix)) continue;
            if (!is_server(p.first)) owner[p.first] = entry.substr(prefix.size());
            break;
        }
    }

    std::unordered_map<std::string, std::vector<int>> by_plugin;
    for (const auto &p : live) {
        // Up to three plugins can claim a process (itself, its session, its group): all of them
        // do, exactly as a scan per plugin would.
        const int keys[3] = {p.first, p.second.session, p.second.pgrp};
        const std::string *seen[3] = {nullptr, nullptr, nullptr};
        for (int i = 0; i < 3; i++) {
            auto it = owner.find(keys[i]);
            if (it == owner.end()) continue;
            bool dup = false;
            for (int j = 0; j < i; j++) dup = dup || (seen[j] && *seen[j] == it->second);
            if (dup) continue;
            seen[i] = &it->second;
            by_plugin[it->second].push_back(p.first);
        }
    }
    return by_plugin;
}

std::string join_ints(const std::vector<int> &v, const char *sep) {
    std::string out;
    for (size_t i = 0; i < v.size(); i++) {
        if (i) out += sep;
        out += std::to_string(v[i]);
    }
    return out;
}

void send_signal(int pid, int sig) { kill(pid, sig); }  // already gone or not ours: ignored

// The part of `current` that is in `known`, or is a child or session/group member of one of
// them, repeated until nothing new joins; `known` grows with what is kept.
std::vector<int> confined(const std::vector<int> &current, std::unordered_set<int> &known) {
    std::unordered_set<int> keep;
    for (int pid : current) {
        if (known.count(pid)) keep.insert(pid);
    }
    bool grew;
    do {
        grew = false;
        for (int pid : current) {
            if (keep.count(pid)) continue;
            ProcStat st;
            if (!read_stat(pid, st)) continue;
            if (known.count(st.ppid) || known.count(st.session) || known.count(st.pgrp)) {
                keep.insert(pid);
                known.insert(pid);
                grew = true;
            }
        }
    } while (grew);
    return {keep.begin(), keep.end()};
}

// SIGTERM to every process, a grace period to exit, then SIGKILL to whatever is left, re-scanned
// so children forked meanwhile are caught too. `name` null: all plugins, confined to what was
// running when it began (a new server may already be starting plugins meanwhile).
void stop_processes(const char *label, const std::string *name, bool confine) {
    std::vector<int> pids = scan(name);
    if (pids.empty()) return;
    std::unordered_set<int> known(pids.begin(), pids.end());
    say(" - stopping %s: %s", label, join_ints(pids, " ").c_str());
    for (int pid : pids) send_signal(pid, SIGTERM);

    long deadline = now_ms() + STOP_GRACE_MS;
    while (now_ms() < deadline) {
        sleep_ms(100);
        pids = confine ? confined(scan(name), known) : scan(name);
        if (pids.empty()) return;
    }

    say(" - killing %s: %s", label, join_ints(pids, " ").c_str());
    for (int pid : pids) send_signal(pid, SIGKILL);
}

void stop_service(const std::string &name) {
    std::string label = "service " + name;
    stop_processes(label.c_str(), &name, false);
}

// ===============================
// IGNITED TAGS
// ===============================
std::string ignited_tag(const std::string &name) { return "log.tag.ignited." + name; }

bool is_ignited(const std::string &name) { return prop_get(ignited_tag(name)) == "1"; }

void mark_ignited(const std::string &name) { prop_set(ignited_tag(name), "1"); }

void clear_ignited(const std::string &name) { prop_set(ignited_tag(name), "0"); }

// An ignite already completed since boot, and no stop-all happened after it.
bool is_boot_ignited() { return prop_get(BOOT_TAG) == "1"; }

void mark_boot_ignited() { prop_set(BOOT_TAG, "1"); }

void clear_boot_ignited() { prop_set(BOOT_TAG, "0"); }

// ===============================
// LINK BIN
// ===============================
void link_bin(const std::string &bin) {
    if (!is_dir(bin)) return;

    mkdirs(AXERONXBIN);
    std::vector<std::string> names;
    list_dir(bin, names);
    for (const auto &n : names) {
        std::string src = bin + "/" + n;
        std::string dst = AXERONXBIN + "/" + n;

        // lstat: a dangling link left by a removed plugin is replaced too.
        if (lexists(dst) && unlink(dst.c_str()) != 0) {
            say(" - Failed to remove: %s", dst.c_str());
        }
        if (symlink(src.c_str(), dst.c_str()) == 0) {
            say(" - Linked : %s", dst.c_str());
        } else {
            say(" - Failed Linking : %s", src.c_str());
        }
    }
}

void unlink_bin(const std::string &bin) {
    if (!exists(bin)) return;
    std::vector<std::string> names;
    list_dir(bin, names);
    for (const auto &n : names) {
        std::string src = bin + "/" + n;
        std::string dst = AXERONXBIN + "/" + n;

        char target[PATH_MAX];
        ssize_t len = readlink(dst.c_str(), target, sizeof(target) - 1);
        if (len < 0) continue;  // missing, or not a link of ours
        target[len] = '\0';
        if (src == target) {
            say(" - Unlinked : %s", dst.c_str());
            unlink(dst.c_str());
        }
    }
}

// ===============================
// SCRIPTS AND SERVICE
// ===============================
bool is_standalone(const std::string &script) {
    std::string raw;
    if (!read_file(script, raw)) return false;
    size_t start = 0;
    while (start <= raw.size()) {
        size_t nl = raw.find('\n', start);
        std::string line = raw.substr(start, nl == std::string::npos ? std::string::npos : nl - start);
        if (trim(line) == "ASH_STANDALONE=1") return true;
        if (nl == std::string::npos) break;
        start = nl + 1;
    }
    return false;
}

// Each flag is its own argv entry: "sh -o standalone" as one entry is looked up as an applet
// name. Without ASH_STANDALONE the system's own sh is preferred over busybox's.
std::vector<Argv> shell_commands(bool standalone, const std::string &script) {
    if (standalone) return {{"busybox", "sh", "-o", "standalone", script}};
    std::vector<Argv> out;
    const std::string sys = tool_system("sh");
    if (!sys.empty()) out.push_back({sys, script});
    out.push_back({"busybox", "sh", script});
    return out;
}

// A plugin's blocking script (post-fs-data.sh, uninstall.sh) with its marker, so anything it
// leaves running can be stopped later. Output goes to a log file: an unread pipe fills up and
// hangs the script. Waits at most SCRIPT_TIMEOUT_S.
void run_script(const std::string &name, const std::string &script, const std::string &kind) {
    std::string log = LOG_DIR + "/" + name + "." + kind + ".log";
    const char *out = (is_dir(LOG_DIR) || (mkdirs(LOG_DIR), is_dir(LOG_DIR))) ? log.c_str() : nullptr;
    say(" - Running %s.sh of %s (up to %ds)", kind.c_str(), name.c_str(), SCRIPT_TIMEOUT_S);
    for (const auto &command : shell_commands(is_standalone(script), script)) {
        pid_t pid = spawn(command, name.c_str(), out);
        if (pid < 0) continue;  // this shell could not be started: the next candidate is tried
        if (!wait_for(pid, SCRIPT_TIMEOUT_S)) {
            say(" - %s of %s still running after %ds, moving on", kind.c_str(), name.c_str(),
                SCRIPT_TIMEOUT_S);
        }
        return;
    }
    say(" - failed to run %s of %s", kind.c_str(), name.c_str());
}

// Runs `script` in a shell that carries the plugin's marker in its env.
void launch_shell(const std::string &script, const std::string &name) {
    std::vector<Argv> candidates;
    const std::string sys = tool_system("sh");
    if (!sys.empty()) candidates.push_back({sys, "-c", script});
    candidates.push_back({"busybox", "sh", "-c", script});
    for (const auto &command : candidates) {
        pid_t pid = spawn(command, name.c_str(), nullptr);
        if (pid < 0) continue;
        // Short-lived: it backgrounds the service and records its pid. Waited for, so the pid
        // prop is set before the next plugin looks at it.
        wait_for(pid, LAUNCH_TIMEOUT_S);
        return;
    }
    say("ERROR: startService %s", name.c_str());
}

void start_service(const std::string &name, const std::string &service) {
    bool standalone = is_standalone(service);

    std::string shell = tool_word("sh");
    std::string setsid_cmd = tool_word("setsid");
    std::string inner = standalone ? "busybox sh -o standalone" : shell;

    // The plugin id reaches the shell through the env (AXERON_PLUGIN), never pasted into the
    // script text. stdin is /dev/null so nothing waits on a pipe the igniter closes when it exits.
    std::string script = setsid_cmd + " " + shell + " -c '\n  exec " + inner + " \"" + service +
                         "\"\n' >/dev/null 2>&1 </dev/null &\n"
                         "pid=$!\n"
                         "resetprop \"log.tag.service.$" + std::string(MARKER) + "\" \"$pid\"\n";

    say(" - startService %s", name.c_str());
    launch_shell(script, name);
}

void apply_setprop(const std::string &name, const std::string &sprop) {
    std::string tag = "log.tag.props." + name;
    if (prop_get(tag) != "-1") {
        say(" - Applying system.prop of %s", name.c_str());
        pid_t pid = spawn({"resetprop", "-f", sprop}, nullptr, nullptr);
        if (pid > 0) wait_for(pid, LAUNCH_TIMEOUT_S);
        prop_set(tag, "1");
    }
}

void post_fs_data(const std::string &name, const std::string &fs_data) {
    std::string tag = "log.tag.fs." + name;
    if (prop_get(tag) != "1") {
        run_script(name, fs_data, "post-fs-data");
        prop_set(tag, "1");
    }
}

void start_plugin(const std::string &name, const std::string &dir) {
    std::string bin = dir + "/system/bin";
    std::string fs_data = dir + "/post-fs-data.sh";
    std::string sprop = dir + "/system.prop";
    std::string service = dir + "/service.sh";

    if (exists(bin)) link_bin(bin);
    if (exists(fs_data)) post_fs_data(name, fs_data);
    if (exists(sprop)) apply_setprop(name, sprop);
    if (exists(service)) start_service(name, service);
}

// ===============================
// STOP / UNINSTALL
// ===============================
void stop_plugin(const std::string &name, const std::string &bin) {
    clear_ignited(name);
    prop_set("log.tag.fs." + name, "0");
    prop_set("log.tag.props." + name, "0");

    stop_service(name);
    prop_set("log.tag.service." + name, "-1");

    unlink_bin(bin);
}

void uninstall_plugin(const std::string &name, const std::string &uninstall, const std::string &bin) {
    if (exists(uninstall)) {
        run_script(name, uninstall, "uninstall");
        // uninstall.sh may itself have started something; it goes with the plugin.
        stop_service(name);
    }
    unlink_bin(bin);
    remove_all(PLUGINS_DIR + "/" + name);
}

// Stops every plugin's processes at once; the server's death watchdog runs this.
void stop_all_plugins() {
    stop_processes("all plugins", nullptr, true);
    // Every plugin goes back to "not ignited", also the ones without a process, so the next
    // ignite starts them all again.
    std::vector<std::string> plugins;
    list_dir(PLUGINS_DIR, plugins);
    for (const auto &p : plugins) clear_ignited(p);
    clear_boot_ignited();
}

// ===============================
// MAIN LOOP
// ===============================
// Ids of the plugins with a staged change: plugins_update/<id>/ holding update_install,
// update_remove, update_enable or update_disable. Same rule as the "Ignite" label in the UI.
std::unordered_set<std::string> staged_plugins() {
    std::unordered_set<std::string> out;
    std::vector<std::string> names;
    list_dir(PLUGINS_UPDATE_DIR, names);
    for (const auto &n : names) {
        std::string dir = PLUGINS_UPDATE_DIR + "/" + n;
        if (!is_dir(dir)) continue;
        std::vector<std::string> inside;
        list_dir(dir, inside);
        if (!inside.empty()) out.insert(n);
    }
    return out;
}

void clean_update_dir() {
    std::vector<std::string> names;
    list_dir(PLUGINS_UPDATE_DIR, names);
    for (const auto &n : names) {
        std::string dir = PLUGINS_UPDATE_DIR + "/" + n;
        struct stat st{};
        if (stat(dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) remove_all(dir);
    }
}

// A plugin's live processes: from the snapshot, else (a service an igniter older than the marker
// started carries none) from the pid in its log.tag.service prop, which costs one resetprop and
// is only asked for a plugin the snapshot found nothing for.
std::vector<int> live_pids(const std::unordered_map<std::string, std::vector<int>> &running,
                           const std::string &name) {
    auto it = running.find(name);
    if (it != running.end()) return it->second;
    if (legacy_leaders(name).empty()) return {};
    return scan(&name);
}

void main_loop(const std::unordered_set<std::string> &staged) {
    std::vector<std::string> plugins;
    {
        std::vector<std::string> names;
        list_dir(PLUGINS_DIR, names);
        for (const auto &n : names) {
            if (is_dir(PLUGINS_DIR + "/" + n)) plugins.push_back(n);
        }
    }

    // Plugins the user ignited by hand: a pending update/remove marker, or a staged change
    // (plugins_update/<id>) such as enable/disable.
    std::unordered_set<std::string> targets;
    for (const auto &name : plugins) {
        std::string dir = PLUGINS_DIR + "/" + name;
        if (exists(dir + "/update") || exists(dir + "/remove") || staged.count(name))
            targets.insert(name);
    }

    bool pending = !targets.empty();

    // Taken once: starting or stopping one plugin does not change whether another is running.
    auto running = running_by_plugin();

    // A manual ignite only touches the plugins that asked for it. It is told apart from the
    // first ignite after boot (or after the server took every plugin down), which must start
    // everything, by the boot tag.
    bool manual = pending && is_boot_ignited();
    if (manual) {
        std::string list;
        for (const auto &t : targets) {
            if (!list.empty()) list += ", ";
            list += t;
        }
        say("- Manual ignite: %s", list.c_str());
    }

    for (const auto &name : plugins) {
        if (manual && !targets.count(name)) {
            // Not asked for: leave it exactly as it is, running or not.
            continue;
        }

        std::string dir = PLUGINS_DIR + "/" + name;
        std::string bin = dir + "/system/bin";
        std::string uninstall = dir + "/uninstall.sh";
        std::string disable = dir + "/disable";
        std::string remove = dir + "/remove";
        std::string update = dir + "/update";

        if (exists(remove)) {
            say("- Disable and uninstalling %s", name.c_str());
            stop_plugin(name, bin);
            uninstall_plugin(name, uninstall, bin);
            continue;
        }

        if (exists(disable)) {
            say("- Disable %s", name.c_str());
            stop_plugin(name, bin);
            continue;
        }

        if (exists(update)) {
            say("- Updating %s", name.c_str());
            stop_plugin(name, bin);
            if (unlink(update.c_str()) == 0) {
                say(" - Update %s Complete", name.c_str());
            } else {
                say(" - Failed to update %s", name.c_str());
            }
        } else if (!targets.count(name) && is_ignited(name)) {
            // Same rule as 1.1: a plugin ignited since boot is left alone even when it has no
            // process (one-shot service.sh, system.prop / bin only). Asked first: this one prop
            // read settles most plugins, a running one is already tagged and a one-shot one has
            // no process to look for.
            say("- %s is already ignited, skip.", name.c_str());
            continue;
        } else if (const std::vector<int> alive = live_pids(running, name); !alive.empty()) {
            // Running, but never tagged (started before the tag existed, or the tag was cleared).
            say("- %s:%s is already running, skip.", name.c_str(), join_ints(alive, ",").c_str());
            mark_ignited(name);
            continue;
        }

        say("- Starting %s", name.c_str());
        start_plugin(name, dir);
        mark_ignited(name);
    }
}

}  // namespace

// argv[0] is the "--igniter" switch itself, argv[1] the igniter's own argument.
int igniter_main(int argc, char **argv) {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);  // the reader may be gone; children get SIG_DFL back (spawn)

    const char *dir = getenv("AXERONDIR");
    if (!dir || !*dir) {
        fprintf(stderr, "AXERONDIR is not set\n");
        return 1;
    }
    AXERONDIR = dir;
    PLUGINS_DIR = AXERONDIR + "/plugins";
    PLUGINS_UPDATE_DIR = AXERONDIR + "/plugins_update";
    LOG_DIR = AXERONDIR + "/logs";
    const char *xbin = getenv("AXERONXBIN");
    AXERONXBIN = (xbin && *xbin) ? xbin : AXERONDIR + "/xbin";

    if (argc > 1 && !strcmp(argv[1], "--stop-all")) {
        prctl(PR_SET_NAME, "axeron_stopper");
        stop_all_plugins();
        return 0;
    }

    prctl(PR_SET_NAME, "axeron_igniter");
    say("AXERON Plugin Manager (%s)", VERSION);

    // Must be read before clean_update_dir(): the folders in plugins_update/ are the only record
    // of which plugins the user ignited by hand (install, uninstall, enable, disable).
    auto staged = staged_plugins();
    clean_update_dir();
    main_loop(staged);
    mark_boot_ignited();

    say("- Completed");
    return 0;
}
