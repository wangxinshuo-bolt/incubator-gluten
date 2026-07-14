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

#pragma once

#include <jni.h>

#include <cstdint>
#include <memory>
#include <string>

namespace gluten {

class ColumnarBatchIterator;
class Runtime;

using InputIteratorFactory = std::unique_ptr<ColumnarBatchIterator> (*)(JNIEnv*, jobject, Runtime*, int32_t);

/// Registers a backend-owned input iterator factory for the lifetime of the process.
/// Backend libraries must remain loaded after registering their factory.
void registerInputIteratorFactory(const std::string& backendKind, InputIteratorFactory factory);

/// Creates an input iterator using the factory registered for runtime->kind().
std::unique_ptr<ColumnarBatchIterator>
createBackendInputIterator(JNIEnv* env, jobject jColumnarBatchIterator, Runtime* runtime, int32_t iteratorIndex);

} // namespace gluten
