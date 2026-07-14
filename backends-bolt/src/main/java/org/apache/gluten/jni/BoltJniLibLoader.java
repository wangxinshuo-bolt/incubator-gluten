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
package org.apache.gluten.jni;

import java.util.Objects;

/**
 * Bolt-specific native library helper.
 *
 * <p>JNI libraries are always registered with the JVM through {@link System#load(String)} via the
 * shared {@link JniLibLoader}. The native helper only promotes an already loaded library into the
 * process-global symbol scope for LLVM JIT and UDF symbol lookup. Libraries are intentionally kept
 * for the lifetime of the process.
 */
public class BoltJniLibLoader {
  public static final int RTLD_GLOBAL = 0x00100;
  public static final int RTLD_LAZY = 0x00001;
  public static final int RTLD_NOW = 0x00002;
  public static final int RTLD_LOCAL = 0x00000;

  private final JniLibLoader delegate;

  public BoltJniLibLoader(String workDir) {
    this(new JniLibLoader(workDir));
  }

  public BoltJniLibLoader(JniLibLoader delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Legacy preload ABI retained for callers outside this repository. */
  @Deprecated
  public static native boolean nativeLoadLibrary(String lib, int rtldFlags)
      throws UnsatisfiedLinkError;

  /** Promotes a library that has already been loaded by the JVM into global symbol scope. */
  public static native void nativePromoteLibrary(String lib) throws UnsatisfiedLinkError;

  public static void loadFromPath(String libPath, boolean requireUnload) {
    JniLibLoader.loadFromPath(libPath);
  }

  public static String loadFromPathAndGetPath(String libPath) {
    return JniLibLoader.loadFromPathAndGetPath(libPath);
  }

  public void mapAndLoad(String unmappedLibName, boolean requireUnload) {
    load(System.mapLibraryName(unmappedLibName), requireUnload);
  }

  public void mapAndLoadWithRtldFlag(String unmappedLibName, boolean requireUnload, int rtldFlags) {
    load(System.mapLibraryName(unmappedLibName), requireUnload, rtldFlags);
  }

  public void load(String libName, boolean requireUnload) {
    delegate.load(libName);
  }

  public String loadAndGetPath(String libName) {
    return delegate.loadAndGetPath(libName);
  }

  /**
   * Compatibility overload. The library is first registered with the JVM and is promoted only after
   * {@code System.load} completes.
   */
  public void load(String libName, boolean requireUnload, int rtldFlags) {
    String loadedPath = loadAndGetPath(libName);
    if ((rtldFlags & RTLD_GLOBAL) != 0) {
      nativePromoteLibrary(loadedPath);
    }
  }

  public void loadAndCreateLink(String libName, String linkName, boolean requireUnload) {
    delegate.loadAndCreateLink(libName, linkName);
  }

  public void loadAndCreateLink(String libName, String linkName) {
    loadAndCreateLink(libName, linkName, false);
  }
}
