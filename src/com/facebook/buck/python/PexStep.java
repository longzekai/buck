/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.python;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.toolchain.PythonVersion;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class PexStep extends ShellStep {
  private static final String SRC_ZIP = ".src.zip";

  private final ProjectFilesystem filesystem;

  // The PEX builder environment variables.
  private final ImmutableMap<String, String> environment;

  // The PEX builder command prefix.
  private final ImmutableList<String> commandPrefix;

  // The path to the executable/directory to create.
  private final Path destination;

  // The main module that begins execution in the PEX.
  private final String entry;

  // The map of modules to sources to package into the PEX.
  private final ImmutableMap<Path, Path> modules;

  // The map of resources to include in the PEX.
  private final ImmutableMap<Path, Path> resources;
  private final PythonVersion pythonVersion;
  private final Path pythonPath;
  private final Path tempDir;

  // The map of native libraries to include in the PEX.
  private final ImmutableMap<Path, Path> nativeLibraries;

  // The list of prebuilt python libraries to add to the PEX.
  private final ImmutableSet<Path> prebuiltLibraries;

  // The list of native libraries to preload into the interpreter.
  private final ImmutableSet<String> preloadLibraries;

  private final boolean zipSafe;

  public PexStep(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> commandPrefix,
      Path pythonPath,
      PythonVersion pythonVersion,
      Path tempDir,
      Path destination,
      String entry,
      ImmutableMap<Path, Path> modules,
      ImmutableMap<Path, Path> resources,
      ImmutableMap<Path, Path> nativeLibraries,
      ImmutableSet<Path> prebuiltLibraries,
      ImmutableSet<String> preloadLibraries,
      boolean zipSafe) {
    super(Optional.of(buildTarget), filesystem.getRootPath());

    this.filesystem = filesystem;
    this.environment = environment;
    this.commandPrefix = commandPrefix;
    this.pythonPath = pythonPath;
    this.pythonVersion = pythonVersion;
    this.tempDir = tempDir;
    this.destination = destination;
    this.entry = entry;
    this.modules = modules;
    this.resources = resources;
    this.nativeLibraries = nativeLibraries;
    this.prebuiltLibraries = prebuiltLibraries;
    this.preloadLibraries = preloadLibraries;
    this.zipSafe = zipSafe;
  }

  @Override
  public String getShortName() {
    return "pex";
  }

  /**
   * Return the manifest as a JSON blob to write to the pex processes stdin.
   *
   * <p>We use stdin rather than passing as an argument to the processes since manifest files can
   * occasionally get extremely large, and surpass exec/shell limits on arguments.
   */
  @Override
  protected Optional<String> getStdin(ExecutionContext context) throws InterruptedException {
    // Convert the map of paths to a map of strings before converting to JSON.
    ImmutableMap<Path, Path> resolvedModules;
    try {
      resolvedModules = getExpandedSourcePaths(context.getProjectFilesystemFactory(), modules);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    ImmutableMap.Builder<String, String> modulesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : resolvedModules.entrySet()) {
      modulesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    ImmutableMap.Builder<String, String> resourcesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : resources.entrySet()) {
      resourcesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    ImmutableMap.Builder<String, String> nativeLibrariesBuilder = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> ent : nativeLibraries.entrySet()) {
      nativeLibrariesBuilder.put(ent.getKey().toString(), ent.getValue().toString());
    }
    ImmutableList.Builder<String> prebuiltLibrariesBuilder = ImmutableList.builder();
    for (Path req : prebuiltLibraries) {
      prebuiltLibrariesBuilder.add(req.toString());
    }
    try {
      return Optional.of(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableMap.of(
                  "modules", modulesBuilder.build(),
                  "resources", resourcesBuilder.build(),
                  "nativeLibraries", nativeLibrariesBuilder.build(),
                  "prebuiltLibraries", prebuiltLibrariesBuilder.build())));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(commandPrefix);
    builder.add("--python");
    builder.add(pythonPath.toString());
    builder.add("--python-version");
    builder.add(pythonVersion.toString());
    builder.add("--entry-point");
    builder.add(entry);

    if (!zipSafe) {
      builder.add("--no-zip-safe");
    }

    for (String lib : preloadLibraries) {
      builder.add("--preload", lib);
    }

    builder.add(destination.toString());
    return builder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  private ImmutableMap<Path, Path> getExpandedSourcePaths(
      ProjectFilesystemFactory projectFilesystemFactory, ImmutableMap<Path, Path> paths)
      throws InterruptedException, IOException {
    ImmutableMap.Builder<Path, Path> sources = ImmutableMap.builder();

    for (ImmutableMap.Entry<Path, Path> ent : paths.entrySet()) {
      if (ent.getValue().toString().endsWith(SRC_ZIP)) {
        Path destinationDirectory = filesystem.resolve(tempDir.resolve(ent.getKey()));
        Files.createDirectories(destinationDirectory);

        ImmutableList<Path> zipPaths =
            Unzip.extractZipFile(
                projectFilesystemFactory,
                filesystem.resolve(ent.getValue()),
                destinationDirectory,
                Unzip.ExistingFileMode.OVERWRITE);
        for (Path path : zipPaths) {
          Path modulePath = destinationDirectory.relativize(path);
          sources.put(modulePath, path);
        }
      } else {
        sources.put(ent.getKey(), ent.getValue());
      }
    }

    return sources.build();
  }

  @VisibleForTesting
  protected ImmutableList<String> getCommandPrefix() {
    return commandPrefix;
  }
}
