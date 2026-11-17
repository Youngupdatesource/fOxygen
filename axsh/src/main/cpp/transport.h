#pragma once

#include <cstddef>
#include <cstdint>

namespace axsh {

int connect_server();
int connect_broker();
int listen_broker();
int spawn_bootstrap();
int run_daemon(const char* minutes);

bool write_with_fds(int fd, const void* data, size_t len, const int* fds, size_t nfds);
bool write_all(int fd, const void* data, size_t len);
bool read_all(int fd, void* data, size_t len);
bool write_u32(int fd, uint32_t value);

}  // namespace axsh
