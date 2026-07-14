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
#include "JniError.h"

gluten::JniErrorState* gluten::getJniErrorState() {
  static JniErrorState jniErrorState;
  return &jniErrorState;
}

void gluten::JniErrorState::ensureInitialized(JNIEnv* env) {
  std::lock_guard<std::mutex> lockGuard(mtx_);
  if (closed_) {
    throw gluten::GlutenException("Cannot initialize JniErrorState after it has been closed");
  }
  if (initialized_) {
    return;
  }
  initialize(env);
  initialized_ = true;
}

void gluten::JniErrorState::assertInitialized() {
  std::lock_guard<std::mutex> lockGuard(mtx_);
  if (!initialized_ || closed_) {
    throw gluten::GlutenException("Fatal: JniErrorState::Initialize(...) was not called before using the utility");
  }
}

jclass gluten::JniErrorState::runtimeExceptionClass() {
  assertInitialized();
  return runtimeExceptionClass_;
}

jclass gluten::JniErrorState::illegalAccessExceptionClass() {
  assertInitialized();
  return illegalAccessExceptionClass_;
}

jclass gluten::JniErrorState::glutenExceptionClass() {
  assertInitialized();
  return glutenExceptionClass_;
}

void gluten::JniErrorState::initialize(JNIEnv* env) {
  try {
    glutenExceptionClass_ = createGlobalClassReferenceOrError(env, "Lorg/apache/gluten/exception/GlutenException;");
    ioExceptionClass_ = createGlobalClassReferenceOrError(env, "Ljava/io/IOException;");
    runtimeExceptionClass_ = createGlobalClassReferenceOrError(env, "Ljava/lang/RuntimeException;");
    unsupportedOperationExceptionClass_ =
        createGlobalClassReferenceOrError(env, "Ljava/lang/UnsupportedOperationException;");
    illegalAccessExceptionClass_ = createGlobalClassReferenceOrError(env, "Ljava/lang/IllegalAccessException;");
    illegalArgumentExceptionClass_ = createGlobalClassReferenceOrError(env, "Ljava/lang/IllegalArgumentException;");
    JavaVM* vm;
    if (env->GetJavaVM(&vm) != JNI_OK) {
      throw gluten::GlutenException("Unable to get JavaVM instance");
    }
    vm_ = vm;
  } catch (...) {
    const jclass classes[] = {
        glutenExceptionClass_,
        ioExceptionClass_,
        runtimeExceptionClass_,
        unsupportedOperationExceptionClass_,
        illegalAccessExceptionClass_,
        illegalArgumentExceptionClass_};
    for (const auto clazz : classes) {
      if (clazz != nullptr) {
        env->DeleteGlobalRef(clazz);
      }
    }
    glutenExceptionClass_ = nullptr;
    ioExceptionClass_ = nullptr;
    runtimeExceptionClass_ = nullptr;
    unsupportedOperationExceptionClass_ = nullptr;
    illegalAccessExceptionClass_ = nullptr;
    illegalArgumentExceptionClass_ = nullptr;
    vm_ = nullptr;
    throw;
  }
}

void gluten::JniErrorState::close() {
  std::lock_guard<std::mutex> lockGuard(mtx_);
  if (!initialized_ || closed_) {
    return;
  }
  JNIEnv* env = nullptr;
  attachCurrentThreadAsDaemonOrThrow(vm_, &env);
  env->DeleteGlobalRef(glutenExceptionClass_);
  env->DeleteGlobalRef(ioExceptionClass_);
  env->DeleteGlobalRef(runtimeExceptionClass_);
  env->DeleteGlobalRef(unsupportedOperationExceptionClass_);
  env->DeleteGlobalRef(illegalAccessExceptionClass_);
  env->DeleteGlobalRef(illegalArgumentExceptionClass_);
  glutenExceptionClass_ = nullptr;
  ioExceptionClass_ = nullptr;
  runtimeExceptionClass_ = nullptr;
  unsupportedOperationExceptionClass_ = nullptr;
  illegalAccessExceptionClass_ = nullptr;
  illegalArgumentExceptionClass_ = nullptr;
  vm_ = nullptr;
  closed_ = true;
}
