/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <cstdlib>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <system_error>
#include <thread>
#include <unordered_map>
#include <utility>

namespace {

constexpr jint kJniVersion = JNI_VERSION_1_8;

constexpr const char* kIllegalArgumentException = "java/lang/IllegalArgumentException";
constexpr const char* kNullPointerException = "java/lang/NullPointerException";
constexpr const char* kUnsatisfiedLinkError = "java/lang/UnsatisfiedLinkError";

class InvalidLoadFlags final : public std::invalid_argument {
 public:
  explicit InvalidLoadFlags(const std::string& message) : std::invalid_argument(message) {}
};

class JStringUtfChars {
 public:
  JStringUtfChars(JNIEnv* env, jstring value)
      : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}

  ~JStringUtfChars() {
    if (chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  JStringUtfChars(const JStringUtfChars&) = delete;
  JStringUtfChars& operator=(const JStringUtfChars&) = delete;

  const char* get() const {
    return chars_;
  }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_;
};

std::string canonicalizePath(const char* path) {
  if (path == nullptr || path[0] == '\0') {
    throw std::invalid_argument("Library path must not be empty");
  }

  errno = 0;
  std::unique_ptr<char, decltype(&std::free)> canonicalPath(::realpath(path, nullptr), &std::free);
  if (canonicalPath == nullptr) {
    const int errorNumber = errno;
    throw std::system_error(
        errorNumber,
        std::generic_category(),
        "Failed to resolve native library path '" + std::string(path) + "'");
  }
  return canonicalPath.get();
}

void* openLibrary(const std::string& path, int flags, const char* operation) {
  // Clear a stale error before dlopen. Capture the new error exactly once:
  // dlerror() clears its thread-local error state on every call.
  dlerror();
  void* handle = dlopen(path.c_str(), flags);
  if (handle == nullptr) {
    const char* error = dlerror();
    const std::string detail = error == nullptr ? "unknown dynamic loader error" : error;
    throw std::runtime_error(
        std::string(operation) + " native library '" + path + "': " + detail);
  }
  return handle;
}

void throwJavaException(JNIEnv* env, const char* exceptionClassName, const std::string& message) {
  jclass exceptionClass = env->FindClass(exceptionClassName);
  if (exceptionClass == nullptr) {
    env->ExceptionClear();
    exceptionClass = env->FindClass("java/lang/RuntimeException");
  }
  if (exceptionClass != nullptr) {
    env->ThrowNew(exceptionClass, message.c_str());
    env->DeleteLocalRef(exceptionClass);
  }
}

void validateLegacyLoadFlags(int flags) {
  constexpr int kSupportedFlags = RTLD_LAZY | RTLD_NOW | RTLD_LOCAL | RTLD_GLOBAL;
  const bool usesLazyBinding = (flags & RTLD_LAZY) != 0;
  const bool usesImmediateBinding = (flags & RTLD_NOW) != 0;
  const bool requestsLocalScope = (flags & RTLD_LOCAL) != 0;
  const bool requestsGlobalScope = (flags & RTLD_GLOBAL) != 0;
  if (flags < 0 || (flags & ~kSupportedFlags) != 0 || usesLazyBinding == usesImmediateBinding ||
      (requestsLocalScope && requestsGlobalScope)) {
    throw InvalidLoadFlags(
        "Legacy dlopen flags must contain exactly one binding mode and at most one symbol scope");
  }
}

} // namespace

namespace gluten {

/// Loads non-JNI native libraries and promotes libraries that the JVM has
/// already loaded. JNI libraries themselves must be loaded with System.load so
/// that the JVM owns their JNI_OnLoad/JNI_OnUnload lifecycle.
class NativeLibraryLoader {
 public:
  static NativeLibraryLoader& instance() {
    static NativeLibraryLoader loader;
    return loader;
  }

  void initialize() {
    initialized_.store(true, std::memory_order_release);
  }

  void onUnload() {
    initialized_.store(false, std::memory_order_release);
    // Intentionally do not dlclose any handle. Libraries loaded or promoted by
    // this helper have process lifetime, which avoids invalidating function
    // pointers held by backends, UDFs, or plugins during JVM shutdown.
  }

  void loadLibrary(const char* path, int flags) {
    ensureInitialized();
    validateLegacyLoadFlags(flags);
    const std::string canonicalPath = canonicalizePath(path);
    const bool requiresGlobalScope = (flags & RTLD_GLOBAL) != 0;
    const bool requiresImmediateBinding = (flags & RTLD_NOW) != 0;

    std::shared_ptr<LibraryEntry> entry;
    while (true) {
      std::unique_lock<std::mutex> lock(mutex_);
      auto it = libraries_.find(canonicalPath);
      if (it == libraries_.end()) {
        entry = std::make_shared<LibraryEntry>(LibraryState::LOADING, std::this_thread::get_id());
        libraries_.emplace(canonicalPath, entry);
        lock.unlock();
        completeLoad(canonicalPath, flags, requiresGlobalScope, requiresImmediateBinding, entry);
        return;
      }

      entry = it->second;
      switch (entry->state) {
        case LibraryState::LOADING:
        case LibraryState::PROMOTING:
          rejectReentrantOperation(canonicalPath, entry);
          entry->stateChanged.wait(lock, [&entry] { return !entry->operationInProgress(); });
          continue;
        case LibraryState::LOADED_GLOBAL:
        case LibraryState::LOADED_LOCAL: {
          const bool alreadyGlobal = entry->state == LibraryState::LOADED_GLOBAL;
          const bool targetGlobal = alreadyGlobal || requiresGlobalScope;
          if ((!requiresGlobalScope || alreadyGlobal) && (!requiresImmediateBinding || entry->loadedNow)) {
            return;
          }
          entry->state = LibraryState::PROMOTING;
          entry->operationOwner = std::this_thread::get_id();
          lock.unlock();
          completeUpgrade(canonicalPath, targetGlobal, entry);
          return;
        }
        case LibraryState::FAILED:
          rethrowFailure(lock, entry);
      }
    }
  }

  void promoteLibrary(const char* path) {
    ensureInitialized();
    const std::string canonicalPath = canonicalizePath(path);

    std::shared_ptr<LibraryEntry> entry;
    while (true) {
      std::unique_lock<std::mutex> lock(mutex_);
      auto it = libraries_.find(canonicalPath);
      if (it == libraries_.end()) {
        entry = std::make_shared<LibraryEntry>(LibraryState::PROMOTING, std::this_thread::get_id());
        libraries_.emplace(canonicalPath, entry);
        lock.unlock();
        completeUpgrade(canonicalPath, true, entry);
        return;
      }

      entry = it->second;
      switch (entry->state) {
        case LibraryState::LOADING:
        case LibraryState::PROMOTING:
          rejectReentrantOperation(canonicalPath, entry);
          entry->stateChanged.wait(lock, [&entry] { return !entry->operationInProgress(); });
          continue;
        case LibraryState::LOADED_GLOBAL:
          if (entry->loadedNow) {
            return;
          }
          entry->state = LibraryState::PROMOTING;
          entry->operationOwner = std::this_thread::get_id();
          lock.unlock();
          completeUpgrade(canonicalPath, true, entry);
          return;
        case LibraryState::LOADED_LOCAL:
          entry->state = LibraryState::PROMOTING;
          entry->operationOwner = std::this_thread::get_id();
          lock.unlock();
          completeUpgrade(canonicalPath, true, entry);
          return;
        case LibraryState::FAILED:
          rethrowFailure(lock, entry);
      }
    }
  }

 private:
  enum class LibraryState {
    LOADING,
    LOADED_LOCAL,
    PROMOTING,
    LOADED_GLOBAL,
    FAILED,
  };

  struct LibraryEntry {
    LibraryEntry(LibraryState initialState, std::thread::id initialOwner)
        : state(initialState), operationOwner(initialOwner) {}

    bool operationInProgress() const {
      return state == LibraryState::LOADING || state == LibraryState::PROMOTING;
    }

    LibraryState state;
    std::thread::id operationOwner;
    bool loadedNow{false};
    void* loadHandle{nullptr};
    void* localNowUpgradeHandle{nullptr};
    void* globalUpgradeHandle{nullptr};
    std::exception_ptr failure;
    std::condition_variable stateChanged;
  };

  NativeLibraryLoader() = default;

  void ensureInitialized() const {
    if (!initialized_.load(std::memory_order_acquire)) {
      throw std::logic_error("NativeLibraryLoader is not initialized");
    }
  }

  void completeLoad(
      const std::string& canonicalPath,
      int flags,
      bool loadedGlobally,
      bool loadedImmediately,
      const std::shared_ptr<LibraryEntry>& entry) {
    try {
      // Never invoke the dynamic loader while holding mutex_. A dependency's
      // initializer may reenter this loader on the same or another thread.
      void* handle = openLibrary(canonicalPath, flags, "Failed to load");
      {
        std::lock_guard<std::mutex> lock(mutex_);
        entry->loadHandle = handle;
        entry->loadedNow = loadedImmediately;
        entry->operationOwner = {};
        entry->state = loadedGlobally ? LibraryState::LOADED_GLOBAL : LibraryState::LOADED_LOCAL;
      }
      entry->stateChanged.notify_all();
    } catch (...) {
      recordFailure(entry, std::current_exception());
      throw;
    }
  }

  void completeUpgrade(
      const std::string& canonicalPath,
      bool targetGlobal,
      const std::shared_ptr<LibraryEntry>& entry) {
    try {
      // Scope and binding upgrades are dlopen operations as well and must stay
      // outside mutex_.
      void* handle = openUpgradedLibrary(canonicalPath, targetGlobal);
      {
        std::lock_guard<std::mutex> lock(mutex_);
        if (targetGlobal) {
          entry->globalUpgradeHandle = handle;
        } else {
          entry->localNowUpgradeHandle = handle;
        }
        entry->loadedNow = true;
        entry->operationOwner = {};
        entry->state = targetGlobal ? LibraryState::LOADED_GLOBAL : LibraryState::LOADED_LOCAL;
      }
      entry->stateChanged.notify_all();
    } catch (...) {
      recordFailure(entry, std::current_exception());
      throw;
    }
  }

  static void* openUpgradedLibrary(const std::string& canonicalPath, bool targetGlobal) {
#ifdef RTLD_NOLOAD
    int upgradeFlags = RTLD_NOLOAD | RTLD_NOW;
    upgradeFlags |= targetGlobal ? RTLD_GLOBAL : RTLD_LOCAL;
    return openLibrary(canonicalPath, upgradeFlags, "Failed to upgrade");
#else
    throw std::runtime_error(
        "Upgrading an already-loaded native library is not supported on this platform");
#endif
  }

  void recordFailure(const std::shared_ptr<LibraryEntry>& entry, std::exception_ptr failure) {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      entry->failure = std::move(failure);
      entry->operationOwner = {};
      entry->state = LibraryState::FAILED;
    }
    entry->stateChanged.notify_all();
  }

  [[noreturn]] static void rethrowFailure(
      std::unique_lock<std::mutex>& lock,
      const std::shared_ptr<LibraryEntry>& entry) {
    std::exception_ptr failure = entry->failure;
    lock.unlock();
    if (failure != nullptr) {
      std::rethrow_exception(failure);
    }
    throw std::logic_error("Native library operation failed without an exception");
  }

  static void rejectReentrantOperation(
      const std::string& canonicalPath,
      const std::shared_ptr<LibraryEntry>& entry) {
    if (entry->operationOwner == std::this_thread::get_id()) {
      throw std::logic_error("Reentrant native library operation for '" + canonicalPath + "'");
    }
  }

  std::atomic<bool> initialized_{false};
  std::mutex mutex_;
  std::unordered_map<std::string, std::shared_ptr<LibraryEntry>> libraries_;
};

} // namespace gluten

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  if (vm == nullptr) {
    return JNI_ERR;
  }

  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), kJniVersion) != JNI_OK) {
    return JNI_ERR;
  }

  gluten::NativeLibraryLoader::instance().initialize();
  return kJniVersion;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
  gluten::NativeLibraryLoader::instance().onUnload();
}

JNIEXPORT jboolean JNICALL Java_org_apache_gluten_jni_BoltJniLibLoader_nativeLoadLibrary(
    JNIEnv* env,
    jclass,
    jstring path,
    jint rtldFlags) {
  if (path == nullptr) {
    throwJavaException(env, kNullPointerException, "Native library path must not be null");
    return JNI_FALSE;
  }

  JStringUtfChars pathChars(env, path);
  if (pathChars.get() == nullptr) {
    // GetStringUTFChars has already raised the appropriate JVM exception.
    return JNI_FALSE;
  }

  try {
    gluten::NativeLibraryLoader::instance().loadLibrary(pathChars.get(), rtldFlags);
    return JNI_TRUE;
  } catch (const InvalidLoadFlags& exception) {
    throwJavaException(env, kIllegalArgumentException, exception.what());
    return JNI_FALSE;
  } catch (const std::exception& exception) {
    throwJavaException(env, kUnsatisfiedLinkError, exception.what());
    return JNI_FALSE;
  } catch (...) {
    throwJavaException(env, kUnsatisfiedLinkError, "Unknown error while loading native library");
    return JNI_FALSE;
  }
}

JNIEXPORT void JNICALL Java_org_apache_gluten_jni_BoltJniLibLoader_nativePromoteLibrary(
    JNIEnv* env,
    jclass,
    jstring path) {
  if (path == nullptr) {
    throwJavaException(env, kNullPointerException, "Native library path must not be null");
    return;
  }

  JStringUtfChars pathChars(env, path);
  if (pathChars.get() == nullptr) {
    // GetStringUTFChars has already raised the appropriate JVM exception.
    return;
  }

  try {
    gluten::NativeLibraryLoader::instance().promoteLibrary(pathChars.get());
  } catch (const std::exception& exception) {
    throwJavaException(env, kUnsatisfiedLinkError, exception.what());
  } catch (...) {
    throwJavaException(env, kUnsatisfiedLinkError, "Unknown error while promoting native library");
  }
}

} // extern "C"
