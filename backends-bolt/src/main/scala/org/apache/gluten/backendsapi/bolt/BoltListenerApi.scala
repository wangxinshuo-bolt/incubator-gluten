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

import org.apache.gluten.backendsapi.ListenerApi
import org.apache.gluten.backendsapi.arrow.ArrowBatchTypes.{ArrowJavaBatchType, ArrowNativeBatchType}
import org.apache.gluten.config.{BoltConfig, GlutenConfig, GlutenCoreConfig}
import org.apache.gluten.config.BoltConfig._
import org.apache.gluten.execution.datasource.GlutenFormatFactory
import org.apache.gluten.expression.UDFMappings
import org.apache.gluten.extension.columnar.transition.Convention
import org.apache.gluten.init.NativeBackendInitializer
import org.apache.gluten.jni.{BoltJniLibLoader, JniLibLoader, JniWorkspace}
import org.apache.gluten.memory.{MemoryUsageRecorder, SimpleMemoryUsageRecorder}
import org.apache.gluten.memory.listener.ReservationListener
import org.apache.gluten.monitor.BoltMemoryProfiler
import org.apache.gluten.udf.UdfJniWrapper
import org.apache.gluten.utils._

import org.apache.spark.{HdfsConfGenerator, ShuffleDependency, SparkConf, SparkContext}
import org.apache.spark.api.plugin.PluginContext
import org.apache.spark.internal.Logging
import org.apache.spark.memory.GlobalOffHeapMemory
import org.apache.spark.network.util.ByteUnit
import org.apache.spark.shuffle.{ColumnarShuffleDependency, LookupKey, ShuffleManagerRegistry}
import org.apache.spark.shuffle.sort.ColumnarShuffleManager
import org.apache.spark.sql.execution.ColumnarCachedBatchSerializer
import org.apache.spark.sql.execution.datasources.GlutenWriterColumnarRules
import org.apache.spark.sql.execution.datasources.bolt.{BoltParquetWriterInjects, BoltRowSplitter}
import org.apache.spark.sql.expression.UDFResolver
import org.apache.spark.sql.internal.{GlutenConfigUtil, StaticSQLConf}
import org.apache.spark.sql.internal.SparkConfigUtil._
import org.apache.spark.util.{SparkDirectoryUtil, SparkResourceUtil, SparkShutdownManagerUtil}

import org.apache.commons.lang3.StringUtils

import java.io.{File, FileInputStream}
import java.util.{Properties, UUID}

class BoltListenerApi extends ListenerApi with Logging {
  import BoltListenerApi._

  override def onDriverStart(sc: SparkContext, pc: PluginContext): Unit = {
    val conf = pc.conf()

    // When the Bolt cache is enabled, the Bolt file handle cache should also be enabled.
    // Otherwise, a 'reference id not found' error may occur.
    if (
      conf.get(COLUMNAR_BOLT_CACHE_ENABLED) &&
      !conf.get(COLUMNAR_BOLT_FILE_HANDLE_CACHE_ENABLED)
    ) {
      throw new IllegalArgumentException(
        s"${COLUMNAR_BOLT_CACHE_ENABLED.key} and " +
          s"${COLUMNAR_BOLT_FILE_HANDLE_CACHE_ENABLED.key} should be enabled together.")
    }

    if (
      conf.get(COLUMNAR_BOLT_CACHE_ENABLED) &&
      !conf.get(GlutenConfig.GLUTEN_SOFT_AFFINITY_ENABLED)
    ) {
      logWarning(
        s"It's recommened to enable ${GlutenConfig.GLUTEN_SOFT_AFFINITY_ENABLED.key} when " +
          s"${COLUMNAR_BOLT_CACHE_ENABLED.key} is set to get better locality.")
    }

    if (conf.get(COLUMNAR_BOLT_CACHE_ENABLED) && conf.get(LOAD_QUANTUM) > 8 * 1024 * 1024) {
      throw new IllegalArgumentException(
        s"Bolt currently only support up to 8MB load quantum size " +
          s"on SSD cache enabled by ${COLUMNAR_BOLT_CACHE_ENABLED.key}, " +
          s"User can set ${LOAD_QUANTUM.key} <= 8MB skip this error.")
    }

    if (conf.contains(DIRECTORY_SIZE_GUESS.key)) {
      logWarning(
        s"${DIRECTORY_SIZE_GUESS.key} is Deprecated " +
          s"replacing it with ${FOOTER_ESTIMATED_SIZE.key} instead.")
    }

    // Generate HDFS client configurations.
    HdfsConfGenerator.addHdfsClientToSparkWorkDirectory(sc)

    // Overhead memory limits.
    val offHeapSize = conf.getSizeAsBytes(GlutenCoreConfig.SPARK_OFFHEAP_SIZE_KEY)
    val desiredOverheadSize = (0.3 * offHeapSize).toLong.max(ByteUnit.MiB.toBytes(384))
    if (!SparkResourceUtil.isMemoryOverheadSet(conf)) {
      // If memory overhead is not set by user, automatically set it according to off-heap settings.
      logInfo(
        s"Memory overhead is not set. Setting it to $desiredOverheadSize automatically." +
          " Gluten doesn't follow Spark's calculation on default value of this option because the" +
          " actual required memory overhead will depend on off-heap usage than on on-heap usage.")
      conf.set(
        GlutenConfig.SPARK_OVERHEAD_SIZE_KEY,
        ByteUnit.BYTE.toMiB(desiredOverheadSize).toString)
    }
    val overheadSize: Long = SparkResourceUtil.getMemoryOverheadSize(conf)
    if (ByteUnit.BYTE.toMiB(overheadSize) < ByteUnit.BYTE.toMiB(desiredOverheadSize)) {
      logWarning(
        s"Memory overhead is set to ${ByteUnit.BYTE.toMiB(overheadSize)}MiB which is smaller than" +
          s" the recommended size ${ByteUnit.BYTE.toMiB(desiredOverheadSize)}MiB." +
          s" This may cause OOM.")
    }
    conf.set(GlutenCoreConfig.COLUMNAR_OVERHEAD_SIZE_IN_BYTES, overheadSize)

    // Sql table cache serializer.
    if (conf.get(GlutenConfig.COLUMNAR_TABLE_CACHE_ENABLED)) {
      conf.set(StaticSQLConf.SPARK_CACHE_SERIALIZER, classOf[ColumnarCachedBatchSerializer].getName)
    }

    // Static initializers for driver. Keep every one-time initialization step inside the gate so
    // READY means the whole sequence completed successfully.
    if (
      !driverInitializationGate.initialize {
        SparkDirectoryUtil.init(conf)
        initialize(conf, isDriver = true)
        UdfJniWrapper.registerFunctionSignatures()
      }
    ) {
      logInfo(
        "Skip rerunning static initializers since they are only supposed to run once." +
          " You see this message probably because you are creating a new SparkSession.")
    }
  }

  override def onDriverShutdown(): Unit = shutdown()

  override def onExecutorStart(pc: PluginContext): Unit = {
    val conf = pc.conf()

    if (inLocalMode(conf)) {
      // Don't do static initializations from executor side in local mode.
      // Driver already did that.
      logInfo(
        "Gluten is running with Spark local mode. Skip running static initializer for executor.")
      return
    }

    // Static initializers for executor. Local mode is intentionally checked before entering the
    // gate because the driver owns initialization in that mode.
    if (
      !executorInitializationGate.initialize {
        SparkDirectoryUtil.init(conf)
        initialize(conf, isDriver = false)
        addIfNeedMemoryDumpShutdownHook(conf)
      }
    ) {
      logInfo(
        "Skip rerunning static initializers since they are only supposed to run once." +
          " You see this message probably because you are creating a new SparkSession.")
    }
  }

  override def onExecutorShutdown(): Unit = shutdown()

  private def initialize(conf: SparkConf, isDriver: Boolean): Unit = {
    // Sets this configuration only once, since not undoable.
    // DebugInstance should be created first.
    if (conf.get(GlutenConfig.DEBUG_KEEP_JNI_WORKSPACE)) {
      val debugDir = conf.get(GlutenConfig.DEBUG_KEEP_JNI_WORKSPACE_DIR)
      JniWorkspace.enableDebug(debugDir)
    } else {
      JniWorkspace.initializeDefault(
        () =>
          SparkDirectoryUtil.get
            .namespace("jni")
            .mkChildDirRandomly(UUID.randomUUID.toString)
            .getAbsolutePath)
    }

    UDFResolver.resolveUdfConf(conf, isDriver)

    // Do row / batch type initializations.
    Convention.ensureSparkRowAndBatchTypesRegistered()
    ArrowJavaBatchType.ensureRegistered()
    ArrowNativeBatchType.ensureRegistered()
    BoltBatchType.ensureRegistered()
    BoltCarrierRowType.ensureRegistered()

    // Register columnar shuffle so can be considered when
    // `org.apache.spark.shuffle.GlutenShuffleManager` is set as Spark shuffle manager.
    ShuffleManagerRegistry
      .get()
      .register(
        new LookupKey {
          override def accepts[K, V, C](dependency: ShuffleDependency[K, V, C]): Boolean = {
            dependency.getClass == classOf[ColumnarShuffleDependency[_, _, _]]
          }
        },
        classOf[ColumnarShuffleManager].getName
      )

    // Set the system properties.
    // Use appending policy for children with the same name in a arrow struct vector.
    System.setProperty("arrow.struct.conflict.policy", "CONFLICT_APPEND")

    // Load supported hive/python/scala udfs
    UDFMappings.loadFromSparkConf(conf)

    val resourceLoader = JniWorkspace.getDefault.libLoader
    val loader = new BoltJniLibLoader(resourceLoader)
    val loaderLibName = System.mapLibraryName("glutenlibloader")
    val coreLibName = System.mapLibraryName(conf.get(GlutenConfig.GLUTEN_LIB_NAME))
    val boltLibName = System.mapLibraryName(BoltBackend.BACKEND_NAME + "_backend")
    val libPath = conf.get(GlutenConfig.GLUTEN_LIB_PATH)

    if (StringUtils.isBlank(libPath)) {
      val manifestResource = s"$platformLibDir/$nativeManifestName"
      val loaderResource = s"$platformLibDir/$loaderLibName"
      val coreResource = s"$platformLibDir/$coreLibName"
      val boltResource = s"$platformLibDir/$boltLibName"
      requireNativeResources(Seq(manifestResource, loaderResource, coreResource, boltResource))
      requireNativeManifest(manifestResource, loaderLibName, coreLibName, boltLibName)

      loader.load(loaderResource, false)
      SharedLibraryLoaderUtils.load(conf, loader)

      val corePath = loader.loadAndGetPath(coreResource)
      BoltJniLibLoader.nativePromoteLibrary(corePath)
      val boltPath = loader.loadAndGetPath(boltResource)
      BoltJniLibLoader.nativePromoteLibrary(boltPath)
    } else {
      val libraries = resolveExternalLibraries(libPath, loaderLibName, coreLibName)
      requireNativeFiles(libraries)
      requireNativeArchitecture(libraries)

      JniLibLoader.loadFromPath(libraries.loader.getPath)
      SharedLibraryLoaderUtils.load(conf, loader)

      val corePath = JniLibLoader.loadFromPathAndGetPath(libraries.core.getPath)
      BoltJniLibLoader.nativePromoteLibrary(corePath)
      val boltPath = JniLibLoader.loadFromPathAndGetPath(libraries.backend.getPath)
      BoltJniLibLoader.nativePromoteLibrary(boltPath)
    }

    // Initial native backend with configurations.
    NativeBackendInitializer
      .forBackend(BoltBackend.BACKEND_NAME)
      .initialize(newGlobalOffHeapMemoryListener(), parseConf(conf, isDriver))

    // Inject backend-specific implementations to override spark classes.
    GlutenFormatFactory.register(new BoltParquetWriterInjects)
    GlutenFormatFactory.injectPostRuleFactory(
      session => GlutenWriterColumnarRules.NativeWritePostRule(session))
    GlutenFormatFactory.register(new BoltRowSplitter())
  }

  private def addIfNeedMemoryDumpShutdownHook(conf: SparkConf): Unit = {
    val memoryDumpOnExit = conf.get(MEMORY_DUMP_ON_EXIT)
    if (memoryDumpOnExit) {
      SparkShutdownManagerUtil.addHook(
        () => {
          logInfo("MemoryDumpOnExit triggered, dumping memory profile.")
          BoltMemoryProfiler.dump()
          logInfo("MemoryDumpOnExit completed.")
        })
    }
  }

  private def shutdown(): Unit = {
    // TODO shutdown implementation in bolt to release resources
  }
}

object BoltListenerApi {
  private[bolt] case class NativeLibraries(loader: File, core: File, backend: File)

  sealed private[bolt] trait InitializationState
  private[bolt] case object UNINITIALIZED extends InitializationState
  private[bolt] case object INITIALIZING extends InitializationState
  private[bolt] case object READY extends InitializationState
  private[bolt] case object FAILED extends InitializationState

  /**
   * A process-lifetime, fail-sticky gate for native initialization.
   *
   * Exactly one caller executes the initializer. Concurrent callers wait for it to finish. Once
   * initialization fails, every current and future caller receives the first failure object; the
   * initializer is never retried because native loading has irreversible side effects.
   */
  final private[bolt] class InitializationGate {
    private var state: InitializationState = UNINITIALIZED
    private var firstFailure: Throwable = _
    private var initializingThread: Thread = _

    /** @return true when this caller ran the initializer, false when it was already READY. */
    def initialize(initializer: => Unit): Boolean = {
      val shouldInitialize = synchronized {
        while (state == INITIALIZING) {
          if (initializingThread eq Thread.currentThread()) {
            throw new IllegalStateException(
              "Native initialization re-entered on the initializing thread")
          }
          wait()
        }
        state match {
          case UNINITIALIZED =>
            state = INITIALIZING
            initializingThread = Thread.currentThread()
            true
          case READY => false
          case FAILED => throw firstFailure
          case INITIALIZING =>
            throw new IllegalStateException("Initialization gate remained INITIALIZING after wait")
        }
      }

      if (!shouldInitialize) {
        false
      } else {
        try {
          initializer
          synchronized {
            initializingThread = null
            state = READY
            notifyAll()
          }
          true
        } catch {
          case failure: Throwable =>
            synchronized {
              firstFailure = failure
              initializingThread = null
              state = FAILED
              notifyAll()
            }
            throw failure
        }
      }
    }

    private[bolt] def currentState: InitializationState = synchronized {
      state
    }

    private[bolt] def failure: Option[Throwable] = synchronized {
      Option(firstFailure)
    }
  }

  // Driver and executor initialization have different ownership in non-local deployments.
  private val driverInitializationGate = new InitializationGate
  private val executorInitializationGate = new InitializationGate
  private val nativeManifestName = "bolt-native-libraries.properties"
  private val platformLibDir: String = nativeResourceDirectory(
    System.getProperty("os.name"),
    System.getProperty("os.arch"))

  private[bolt] def nativeResourceDirectory(osProperty: String, archProperty: String): String = {
    val osName = osProperty match {
      case n if n.contains("Linux") => "linux"
      case n if n.contains("Mac") => "darwin"
      case _ =>
        // Default to linux
        "linux"
    }
    val arch = (osName, archProperty) match {
      case ("linux", "amd64" | "x86_64") => "amd64"
      case ("darwin", "amd64" | "x86_64") => "x86_64"
      case (_, "aarch64" | "arm64") => "aarch64"
      case (_, arch) => arch
    }
    s"$osName/$arch"
  }

  private[bolt] def resolveExternalLibraries(
      backendPath: String,
      loaderLibName: String,
      coreLibName: String): NativeLibraries = {
    val backend = new File(backendPath).getCanonicalFile
    val parent = Option(backend.getParentFile).getOrElse {
      throw new IllegalArgumentException(
        s"Native backend path has no parent directory: $backendPath")
    }
    NativeLibraries(
      new File(parent, loaderLibName).getCanonicalFile,
      new File(parent, coreLibName).getCanonicalFile,
      backend)
  }

  private[bolt] def requireNativeFiles(libraries: NativeLibraries): Unit = {
    val missing = Seq(libraries.loader, libraries.core, libraries.backend).filterNot(_.isFile)
    if (missing.nonEmpty) {
      throw new IllegalArgumentException(
        s"Bolt native bundle is incomplete; missing files: ${missing.mkString(", ")}")
    }
  }

  private[bolt] def requireNativeArchitecture(libraries: NativeLibraries): Unit = {
    if (!System.getProperty("os.name").contains("Linux")) {
      return
    }

    val expectedMachine = System.getProperty("os.arch") match {
      case "amd64" | "x86_64" => 62 // EM_X86_64
      case "aarch64" | "arm64" => 183 // EM_AARCH64
      case arch =>
        throw new IllegalArgumentException(s"Unsupported Linux native architecture: $arch")
    }
    Seq(libraries.loader, libraries.core, libraries.backend).foreach {
      library => requireElf64Machine(library, expectedMachine)
    }
  }

  private def requireElf64Machine(library: File, expectedMachine: Int): Unit = {
    val header = new Array[Byte](20)
    val input = new FileInputStream(library)
    try {
      var offset = 0
      while (offset < header.length) {
        val read = input.read(header, offset, header.length - offset)
        if (read < 0) {
          throw new IllegalArgumentException(s"Native library has a truncated ELF header: $library")
        }
        offset += read
      }
    } finally {
      input.close()
    }

    val isElf =
      (header(0) & 0xff) == 0x7f && header(1) == 'E'.toByte && header(2) == 'L'.toByte &&
        header(3) == 'F'.toByte
    if (!isElf || header(4) != 2) {
      throw new IllegalArgumentException(s"Native library is not a 64-bit ELF file: $library")
    }
    val machine = header(5) match {
      case 1 => (header(18) & 0xff) | ((header(19) & 0xff) << 8)
      case 2 => ((header(18) & 0xff) << 8) | (header(19) & 0xff)
      case encoding =>
        throw new IllegalArgumentException(
          s"Native library has unsupported ELF byte order $encoding: $library")
    }
    if (machine != expectedMachine) {
      throw new IllegalArgumentException(
        s"Native library architecture mismatch for $library: " +
          s"ELF machine $machine, expected $expectedMachine")
    }
  }

  private def requireNativeResources(resources: Seq[String]): Unit = {
    val classLoader = classOf[BoltListenerApi].getClassLoader
    val missing = resources.filter(path => classLoader.getResource(path) == null)
    if (missing.nonEmpty) {
      throw new IllegalArgumentException(
        s"Bolt native bundle is incomplete; missing resources: ${missing.mkString(", ")}")
    }
  }

  private def requireNativeManifest(
      manifestResource: String,
      loaderLibName: String,
      coreLibName: String,
      backendLibName: String): Unit = {
    val classLoader = classOf[BoltListenerApi].getClassLoader
    val input = Option(classLoader.getResourceAsStream(manifestResource)).getOrElse {
      throw new IllegalArgumentException(s"Bolt native manifest is missing: $manifestResource")
    }
    val properties = new Properties
    try {
      properties.load(input)
    } finally {
      input.close()
    }

    val platformAndArch = platformLibDir.split("/", 2)
    val expected = Map(
      "format.version" -> "1",
      "platform" -> platformAndArch(0),
      "arch" -> platformAndArch(1),
      "loader.file" -> loaderLibName,
      "loader.soname" -> loaderLibName,
      "loader.arch" -> platformAndArch(1),
      "core.file" -> coreLibName,
      "core.soname" -> coreLibName,
      "core.arch" -> platformAndArch(1),
      "backend.file" -> backendLibName,
      "backend.soname" -> backendLibName,
      "backend.arch" -> platformAndArch(1),
      "dependency.delivery" -> "thirdparty-companion-jar"
    )
    val mismatches = expected.collect {
      case (key, value) if properties.getProperty(key) != value =>
        s"$key=${properties.getProperty(key)} (expected $value)"
    }
    if (mismatches.nonEmpty) {
      throw new IllegalArgumentException(
        s"Bolt native manifest does not match this runtime: ${mismatches.mkString(", ")}")
    }
  }

  private def inLocalMode(conf: SparkConf): Boolean = {
    SparkResourceUtil.isLocalMaster(conf)
  }

  private def newGlobalOffHeapMemoryListener(): ReservationListener = {
    new ReservationListener {
      private val recorder: MemoryUsageRecorder = new SimpleMemoryUsageRecorder()

      override def reserve(size: Long): Long = {
        GlobalOffHeapMemory.acquire(size)
        recorder.inc(size)
        size
      }

      override def unreserve(size: Long): Long = {
        GlobalOffHeapMemory.release(size)
        recorder.inc(-size)
        size
      }

      override def getUsedBytes: Long = {
        recorder.current()
      }
    }
  }

  def parseConf(conf: SparkConf, isDriver: Boolean): Map[String, String] = {
    // Ensure bolt conf registered.
    BoltConfig.get

    var parsed: Map[String, String] = GlutenConfigUtil.parseConfig(conf.getAll.toMap)

    // Workaround for https://github.com/apache/incubator-gluten/issues/7837
    if (isDriver && !inLocalMode(conf)) {
      parsed += (COLUMNAR_BOLT_CACHE_ENABLED.key -> "false")
    }

    parsed
  }
}
