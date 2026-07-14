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
package org.apache.gluten.jni

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, Executors, TimeUnit}

class BoltNativeLoaderSuite extends AnyFunSuite {
  private lazy val loaderPath = {
    val path = sys.props
      .get("bolt.native.loader.path")
      .orElse(sys.props.get("bolt.native.package.dir").map(findPackagedLoader))
      .getOrElse(cancel("a Bolt native loader path or package directory is required"))
    val canonicalPath = Paths.get(path).toRealPath().toString
    System.load(canonicalPath)
    canonicalPath
  }

  private def findPackagedLoader(packageDirectory: String): String = {
    val stream = Files.list(Paths.get(packageDirectory))
    val matches =
      try {
        stream
          .filter(path => path.getFileName.toString.startsWith("libglutenlibloader."))
          .toArray
          .map(_.asInstanceOf[Path])
      } finally {
        stream.close()
      }
    if (matches.length != 1) {
      cancel(
        s"expected exactly one libglutenlibloader in $packageDirectory, found ${matches.length}")
    }
    matches.head.toString
  }

  test("legacy load upgrades LAZY to NOW and LOCAL to GLOBAL") {
    val copiedLoader = Files.createTempFile("glutenlibloader-upgrade", ".so")
    Files.copy(Paths.get(loaderPath), copiedLoader, StandardCopyOption.REPLACE_EXISTING)
    try {
      assert(
        BoltJniLibLoader.nativeLoadLibrary(
          copiedLoader.toString,
          BoltJniLibLoader.RTLD_LAZY | BoltJniLibLoader.RTLD_LOCAL))
      assert(
        BoltJniLibLoader.nativeLoadLibrary(
          copiedLoader.toString,
          BoltJniLibLoader.RTLD_NOW | BoltJniLibLoader.RTLD_LOCAL))
      BoltJniLibLoader.nativePromoteLibrary(copiedLoader.toString)
    } finally {
      Files.deleteIfExists(copiedLoader)
    }
  }

  test("concurrent callers share load and promotion results") {
    val copiedLoader = Files.createTempFile("glutenlibloader-concurrent", ".so")
    Files.copy(Paths.get(loaderPath), copiedLoader, StandardCopyOption.REPLACE_EXISTING)
    val executor = Executors.newFixedThreadPool(8)
    try {
      val futures = (0 until 16).map {
        index =>
          executor.submit(new Callable[Boolean] {
            override def call(): Boolean = {
              val scope =
                if (index % 2 == 0) BoltJniLibLoader.RTLD_LOCAL else BoltJniLibLoader.RTLD_GLOBAL
              BoltJniLibLoader.nativeLoadLibrary(
                copiedLoader.toString,
                BoltJniLibLoader.RTLD_LAZY | scope)
            }
          })
      }
      futures.foreach(future => assert(future.get(10, TimeUnit.SECONDS)))
      BoltJniLibLoader.nativePromoteLibrary(copiedLoader.toString)
    } finally {
      executor.shutdownNow()
      Files.deleteIfExists(copiedLoader)
    }
  }

  test("System.load followed by concurrent promotion is idempotent") {
    assume(System.getProperty("os.name").contains("Linux"))
    val copiedLoader = Files.createTempFile("glutenlibloader-system-load", ".so")
    Files.copy(Paths.get(loaderPath), copiedLoader, StandardCopyOption.REPLACE_EXISTING)
    val canonicalPath = copiedLoader.toRealPath().toString
    val executor = Executors.newFixedThreadPool(8)
    try {
      System.load(canonicalPath)
      val futures = (0 until 16).map {
        _ =>
          executor.submit(new Callable[Unit] {
            override def call(): Unit = BoltJniLibLoader.nativePromoteLibrary(canonicalPath)
          })
      }
      futures.foreach(_.get(10, TimeUnit.SECONDS))
      BoltJniLibLoader.nativePromoteLibrary(canonicalPath)
    } finally {
      executor.shutdownNow()
      Files.deleteIfExists(copiedLoader)
    }
  }

  test("native loader maps invalid input to standard Java errors") {
    val loadedLoaderPath = loaderPath
    intercept[NullPointerException] {
      BoltJniLibLoader.nativePromoteLibrary(null)
    }
    intercept[IllegalArgumentException] {
      BoltJniLibLoader.nativeLoadLibrary(loadedLoaderPath, 0)
    }
    intercept[IllegalArgumentException] {
      BoltJniLibLoader.nativeLoadLibrary(
        loadedLoaderPath,
        BoltJniLibLoader.RTLD_NOW | 0x40000000)
    }
    intercept[UnsatisfiedLinkError] {
      BoltJniLibLoader.nativePromoteLibrary(s"$loadedLoaderPath.does-not-exist")
    }
  }
}
