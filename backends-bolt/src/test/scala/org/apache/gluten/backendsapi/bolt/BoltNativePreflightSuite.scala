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

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

class BoltNativePreflightSuite extends AnyFunSuite {
  test("native resource directories use package architecture names") {
    assert(BoltListenerApi.nativeResourceDirectory("Linux", "x86_64") == "linux/amd64")
    assert(BoltListenerApi.nativeResourceDirectory("Linux", "amd64") == "linux/amd64")
    assert(BoltListenerApi.nativeResourceDirectory("Linux", "arm64") == "linux/aarch64")
    assert(BoltListenerApi.nativeResourceDirectory("Mac OS X", "x86_64") == "darwin/x86_64")
    assert(BoltListenerApi.nativeResourceDirectory("Mac OS X", "arm64") == "darwin/aarch64")
  }

  test("external native libraries are canonicalized and architecture checked before loading") {
    assume(System.getProperty("os.name").contains("Linux"))
    val expectedMachine = System.getProperty("os.arch") match {
      case "amd64" | "x86_64" => 62
      case "aarch64" | "arm64" => 183
      case arch => cancel(s"Unsupported test architecture: $arch")
    }
    val wrongMachine = if (expectedMachine == 62) 183 else 62
    val directory = Files.createTempDirectory("bolt-native-preflight")
    val loader = directory.resolve(System.mapLibraryName("glutenlibloader"))
    val core = directory.resolve(System.mapLibraryName("gluten"))
    val backend = directory.resolve(System.mapLibraryName("bolt_backend"))

    try {
      Seq(loader, core, backend).foreach(path => Files.write(path, elf64Header(expectedMachine)))
      val libraries = BoltListenerApi.resolveExternalLibraries(
        backend.toString,
        loader.getFileName.toString,
        core.getFileName.toString)

      BoltListenerApi.requireNativeFiles(libraries)
      BoltListenerApi.requireNativeArchitecture(libraries)
      assert(libraries.loader.toPath == loader.toRealPath())
      assert(libraries.core.toPath == core.toRealPath())
      assert(libraries.backend.toPath == backend.toRealPath())

      Files.write(core, elf64Header(wrongMachine))
      val error = intercept[IllegalArgumentException] {
        BoltListenerApi.requireNativeArchitecture(libraries)
      }
      assert(error.getMessage.contains("architecture mismatch"))
      assert(error.getMessage.contains(core.getFileName.toString))
    } finally {
      Seq(loader, core, backend).foreach(path => Files.deleteIfExists(path))
      Files.deleteIfExists(directory)
    }
  }

  private def elf64Header(machine: Int): Array[Byte] = {
    val header = new Array[Byte](20)
    header(0) = 0x7f
    header(1) = 'E'.toByte
    header(2) = 'L'.toByte
    header(3) = 'F'.toByte
    header(4) = 2 // ELFCLASS64
    header(5) = 1 // ELFDATA2LSB
    header(18) = (machine & 0xff).toByte
    header(19) = ((machine >> 8) & 0xff).toByte
    header
  }
}
