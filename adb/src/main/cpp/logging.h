#ifndef LOGGING_H
#define LOGGING_H

#include <cerrno>
#include <cstring>
#include "android/log.h"

#ifndef LOG_TAG
#define LOG_TAG    "Axeron"
#endif

#if defined(DEBUG) && !defined(NO_LOG)
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGV(...)  __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define PLOGE(fmt, args...) LOGE(fmt " failed with %d: %s", ##args, errno, strerror(errno))
#else
#define LOGD(...)
#define LOGV(...)
#define LOGI(...)
#define LOGW(...)
#define LOGE(...)
#define PLOGE(fmt, args...)
#endif

#endif // LOGGING_H
