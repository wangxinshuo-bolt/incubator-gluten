# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if (ENABLE_BOLT)

cmake_minimum_required(VERSION 3.25)

find_package(JNI REQUIRED)

add_library(glutenlibloader SHARED JniNativeLibraryLoader.cc)
target_include_directories(glutenlibloader PRIVATE ${JNI_INCLUDE_DIRS})
if(CMAKE_DL_LIBS)
  target_link_libraries(glutenlibloader PRIVATE ${CMAKE_DL_LIBS})
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_compile_definitions(glutenlibloader PRIVATE _GNU_SOURCE)
endif()

if(NOT CMAKE_SYSTEM_NAME MATCHES "Darwin")
  target_link_options(
    glutenlibloader
    PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
  set_target_properties(
    glutenlibloader
    PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
endif()

set_target_properties(glutenlibloader PROPERTIES SUFFIX "${CMAKE_SHARED_LIBRARY_SUFFIX}")

install(TARGETS glutenlibloader
        DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/../../build/releases)
if(TARGET hdfs_client::hdfs_client)
  include(${hdfs_client_DIR}/hdfs_client-config-extras.cmake OPTIONAL)
  if(COMMAND install_hdfs_client_libraries)
    install_hdfs_client_libraries(${CMAKE_CURRENT_SOURCE_DIR}/../../build/releases)
  endif()
endif()

endif()
