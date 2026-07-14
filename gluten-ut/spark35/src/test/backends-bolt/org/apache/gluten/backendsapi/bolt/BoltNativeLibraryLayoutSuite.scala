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
package org.apache.gluten.backendsapi.bolt

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class BoltNativeLibraryLayoutSuite extends AnyFunSuite {
  test("derive loader and Core from the Bolt backend directory") {
    val directory = Files.createTempDirectory("bolt-native-layout")
    val backend = Files.createFile(directory.resolve(System.mapLibraryName("bolt_backend")))

    try {
      val libraries = BoltListenerApi.resolveExternalLibraries(
        backend.toString,
        System.mapLibraryName("glutenlibloader"),
        System.mapLibraryName("gluten"))

      assert(libraries.backend.toPath == backend.toRealPath())
      assert(libraries.loader.toPath == directory.resolve(System.mapLibraryName("glutenlibloader")))
      assert(libraries.core.toPath == directory.resolve(System.mapLibraryName("gluten")))
    } finally {
      Files.deleteIfExists(backend)
      Files.deleteIfExists(directory)
    }
  }

  test("reject an incomplete external native bundle before loading") {
    val directory = Files.createTempDirectory("bolt-native-layout-missing")
    val backend = Files.createFile(directory.resolve(System.mapLibraryName("bolt_backend")))

    try {
      val libraries = BoltListenerApi.resolveExternalLibraries(
        backend.toString,
        System.mapLibraryName("glutenlibloader"),
        System.mapLibraryName("gluten"))

      val error = intercept[IllegalArgumentException] {
        BoltListenerApi.requireNativeFiles(libraries)
      }
      assert(error.getMessage.contains(System.mapLibraryName("glutenlibloader")))
      assert(error.getMessage.contains(System.mapLibraryName("gluten")))
    } finally {
      Files.deleteIfExists(backend)
      Files.deleteIfExists(directory)
    }
  }
}
