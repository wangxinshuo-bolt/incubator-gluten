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

#include "jni/BackendJniRegistry.h"

#include <mutex>
#include <unordered_map>

#include "compute/Runtime.h"
#include "memory/ColumnarBatchIterator.h"
#include "utils/Exception.h"

namespace gluten {
namespace {

struct InputIteratorFactoryRegistry {
  std::mutex mutex;
  std::unordered_map<std::string, InputIteratorFactory> factories;
};

InputIteratorFactoryRegistry& inputIteratorFactoryRegistry() {
  // Backend factory addresses remain valid because backend DSOs have process lifetime.
  static auto* registry = new InputIteratorFactoryRegistry();
  return *registry;
}

} // namespace

void registerInputIteratorFactory(const std::string& backendKind, InputIteratorFactory factory) {
  GLUTEN_CHECK(!backendKind.empty(), "Cannot register an input iterator factory with an empty backend kind");
  GLUTEN_CHECK(factory != nullptr, "Cannot register a null input iterator factory for backend " + backendKind);

  auto& registry = inputIteratorFactoryRegistry();
  std::lock_guard<std::mutex> lock(registry.mutex);
  const bool inserted = registry.factories.emplace(backendKind, factory).second;
  GLUTEN_CHECK(inserted, "An input iterator factory is already registered for backend " + backendKind);
}

std::unique_ptr<ColumnarBatchIterator>
createBackendInputIterator(JNIEnv* env, jobject jColumnarBatchIterator, Runtime* runtime, int32_t iteratorIndex) {
  GLUTEN_CHECK(runtime != nullptr, "Cannot create an input iterator without a runtime");
  const auto backendKind = runtime->kind();

  InputIteratorFactory factory = nullptr;
  {
    auto& registry = inputIteratorFactoryRegistry();
    std::lock_guard<std::mutex> lock(registry.mutex);
    const auto it = registry.factories.find(backendKind);
    GLUTEN_CHECK(it != registry.factories.end(), "No input iterator factory registered for backend " + backendKind);
    factory = it->second;
  }

  GLUTEN_CHECK(factory != nullptr, "Null input iterator factory registered for backend " + backendKind);
  auto iterator = factory(env, jColumnarBatchIterator, runtime, iteratorIndex);
  GLUTEN_CHECK(iterator != nullptr, "Input iterator factory returned null for backend " + backendKind);
  return iterator;
}

} // namespace gluten
