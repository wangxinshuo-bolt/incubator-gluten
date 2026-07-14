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

#include <gtest/gtest.h>

#include <atomic>
#include <thread>
#include <vector>

#include "compute/Runtime.h"
#include "memory/ColumnarBatchIterator.h"
#include "utils/Exception.h"

using namespace gluten;

namespace {

class TestColumnarBatchIterator final : public ColumnarBatchIterator {
 public:
  std::shared_ptr<ColumnarBatch> next() override {
    return nullptr;
  }
};

class TestRuntime final : public Runtime {
 public:
  explicit TestRuntime(const std::string& kind) : Runtime(kind, nullptr, nullptr, {}) {}
};

struct FactoryInvocation {
  JNIEnv* env{nullptr};
  jobject iterator{nullptr};
  Runtime* runtime{nullptr};
  int32_t iteratorIndex{-1};
};

FactoryInvocation invocation;

std::unique_ptr<ColumnarBatchIterator>
recordingFactory(JNIEnv* env, jobject iterator, Runtime* runtime, int32_t iteratorIndex) {
  invocation = {env, iterator, runtime, iteratorIndex};
  return std::make_unique<TestColumnarBatchIterator>();
}

std::unique_ptr<ColumnarBatchIterator> nullFactory(JNIEnv*, jobject, Runtime*, int32_t) {
  return nullptr;
}

std::atomic<int> firstFactoryInvocations{0};
std::atomic<int> secondFactoryInvocations{0};

std::unique_ptr<ColumnarBatchIterator> firstCountingFactory(JNIEnv*, jobject, Runtime*, int32_t) {
  ++firstFactoryInvocations;
  return std::make_unique<TestColumnarBatchIterator>();
}

std::unique_ptr<ColumnarBatchIterator> secondCountingFactory(JNIEnv*, jobject, Runtime*, int32_t) {
  ++secondFactoryInvocations;
  return std::make_unique<TestColumnarBatchIterator>();
}

} // namespace

TEST(BackendJniRegistry, DispatchesUsingRuntimeKind) {
  static int envMarker;
  static int iteratorMarker;
  auto* env = reinterpret_cast<JNIEnv*>(&envMarker);
  auto iterator = reinterpret_cast<jobject>(&iteratorMarker);
  TestRuntime runtime("backend-jni-registry-dispatch-test");

  registerInputIteratorFactory(runtime.kind(), recordingFactory);
  auto result = createBackendInputIterator(env, iterator, &runtime, 7);

  ASSERT_NE(result.get(), nullptr);
  EXPECT_EQ(invocation.env, env);
  EXPECT_EQ(invocation.iterator, iterator);
  EXPECT_EQ(invocation.runtime, &runtime);
  EXPECT_EQ(invocation.iteratorIndex, 7);
}

TEST(BackendJniRegistry, RejectsDuplicateFactory) {
  const std::string kind = "backend-jni-registry-duplicate-test";
  registerInputIteratorFactory(kind, recordingFactory);

  EXPECT_THROW(registerInputIteratorFactory(kind, recordingFactory), GlutenException);
}

TEST(BackendJniRegistry, RejectsNullFactory) {
  EXPECT_THROW(registerInputIteratorFactory("backend-jni-registry-null-test", nullptr), GlutenException);
}

TEST(BackendJniRegistry, RejectsMissingFactory) {
  TestRuntime runtime("backend-jni-registry-missing-test");

  EXPECT_THROW(createBackendInputIterator(nullptr, nullptr, &runtime, 0), GlutenException);
}

TEST(BackendJniRegistry, RejectsNullIterator) {
  TestRuntime runtime("backend-jni-registry-null-iterator-test");
  registerInputIteratorFactory(runtime.kind(), nullFactory);

  EXPECT_THROW(createBackendInputIterator(nullptr, nullptr, &runtime, 0), GlutenException);
}

TEST(BackendJniRegistry, RejectsNullRuntime) {
  EXPECT_THROW(createBackendInputIterator(nullptr, nullptr, nullptr, 0), GlutenException);
}

TEST(BackendJniRegistry, KeepsBackendFactoriesIndependent) {
  firstFactoryInvocations = 0;
  secondFactoryInvocations = 0;
  TestRuntime firstRuntime("backend-jni-registry-first-kind-test");
  TestRuntime secondRuntime("backend-jni-registry-second-kind-test");
  registerInputIteratorFactory(firstRuntime.kind(), firstCountingFactory);
  registerInputIteratorFactory(secondRuntime.kind(), secondCountingFactory);

  createBackendInputIterator(nullptr, nullptr, &firstRuntime, 0);
  createBackendInputIterator(nullptr, nullptr, &secondRuntime, 0);
  createBackendInputIterator(nullptr, nullptr, &firstRuntime, 1);

  EXPECT_EQ(firstFactoryInvocations, 2);
  EXPECT_EQ(secondFactoryInvocations, 1);
}

TEST(BackendJniRegistry, SupportsConcurrentDispatch) {
  constexpr int kThreadCount = 16;
  constexpr int kIterationsPerThread = 100;
  firstFactoryInvocations = 0;
  std::atomic<int> failures{0};
  TestRuntime runtime("backend-jni-registry-concurrent-test");
  registerInputIteratorFactory(runtime.kind(), firstCountingFactory);

  std::vector<std::thread> threads;
  threads.reserve(kThreadCount);
  for (int threadIndex = 0; threadIndex < kThreadCount; ++threadIndex) {
    threads.emplace_back([&] {
      try {
        for (int iteration = 0; iteration < kIterationsPerThread; ++iteration) {
          auto result = createBackendInputIterator(nullptr, nullptr, &runtime, iteration);
          if (result == nullptr) {
            ++failures;
          }
        }
      } catch (...) {
        ++failures;
      }
    });
  }
  for (auto& thread : threads) {
    thread.join();
  }

  EXPECT_EQ(failures, 0);
  EXPECT_EQ(firstFactoryInvocations, kThreadCount * kIterationsPerThread);
}
