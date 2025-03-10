// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.apple;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BazelModuleContext;
import com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions.AppleBitcodeMode;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.starlarkbuildapi.apple.AppleConfigurationApi;
import com.google.devtools.build.lib.util.CPU;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** A configuration containing flags required for Apple platforms and tools. */
@Immutable
@RequiresOptions(options = {AppleCommandLineOptions.class})
public class AppleConfiguration extends Fragment implements AppleConfigurationApi<PlatformType> {
  /** Environment variable name for the developer dir of the selected Xcode. */
  public static final String DEVELOPER_DIR_ENV_NAME = "DEVELOPER_DIR";
  /**
   * Environment variable name for the xcode version. The value of this environment variable should
   * be set to the version (for example, "7.2") of xcode to use when invoking part of the apple
   * toolkit in action execution.
   */
  public static final String XCODE_VERSION_ENV_NAME = "XCODE_VERSION_OVERRIDE";
  /**
   * Environment variable name for the apple SDK version. If unset, uses the system default of the
   * host for the platform in the value of {@link #APPLE_SDK_PLATFORM_ENV_NAME}.
   */
  public static final String APPLE_SDK_VERSION_ENV_NAME = "APPLE_SDK_VERSION_OVERRIDE";
  /**
   * Environment variable name for the apple SDK platform. This should be set for all actions that
   * require an apple SDK. The valid values consist of {@link ApplePlatform} names.
   */
  public static final String APPLE_SDK_PLATFORM_ENV_NAME = "APPLE_SDK_PLATFORM";

  /** Prefix for iOS cpu values */
  public static final String IOS_CPU_PREFIX = "ios_";

  /** Prefix for macOS cpu values */
  private static final String MACOS_CPU_PREFIX = "darwin_";

  // TODO(b/180572694): Remove after platforms based toolchain resolution supported.
  /** Prefix for forced iOS and tvOS simulator cpu values */
  public static final String FORCED_SIMULATOR_CPU_PREFIX = "sim_";

  /** Default cpu for iOS builds. */
  @VisibleForTesting
  static final String DEFAULT_IOS_CPU = CPU.getCurrent() == CPU.AARCH64 ? "sim_arm64" : "x86_64";

  private final PlatformType applePlatformType;
  private final ConfigurationDistinguisher configurationDistinguisher;
  private final EnumMap<ApplePlatform.PlatformType, AppleBitcodeMode> platformBitcodeModes;
  private final Label xcodeConfigLabel;
  private final AppleCommandLineOptions options;
  private final AppleCpus appleCpus;
  private final boolean mandatoryMinimumVersion;

  public AppleConfiguration(BuildOptions buildOptions) {
    AppleCommandLineOptions options = buildOptions.get(AppleCommandLineOptions.class);
    this.options = options;
    this.appleCpus = AppleCpus.create(options, buildOptions.get(CoreOptions.class));
    this.applePlatformType =
        Preconditions.checkNotNull(options.applePlatformType, "applePlatformType");
    this.configurationDistinguisher = options.configurationDistinguisher;
    this.platformBitcodeModes = collectBitcodeModes(options.appleBitcodeMode);
    this.xcodeConfigLabel =
        Preconditions.checkNotNull(options.xcodeVersionConfig, "xcodeConfigLabel");
    this.mandatoryMinimumVersion = options.mandatoryMinimumVersion;
  }

  /** A class that contains information pertaining to Apple CPUs. */
  @AutoValue
  public abstract static class AppleCpus {
    public static AppleCpus create(AppleCommandLineOptions options, CoreOptions coreOptions) {
      String iosCpu = iosCpuFromCpu(coreOptions.cpu);
      String appleSplitCpu = Preconditions.checkNotNull(options.appleSplitCpu, "appleSplitCpu");
      ImmutableList<String> iosMultiCpus =
          ImmutableList.copyOf(Preconditions.checkNotNull(options.iosMultiCpus, "iosMultiCpus"));
      ImmutableList<String> watchosCpus =
          (options.watchosCpus == null || options.watchosCpus.isEmpty())
              ? ImmutableList.of(AppleCommandLineOptions.DEFAULT_WATCHOS_CPU)
              : ImmutableList.copyOf(options.watchosCpus);
      ImmutableList<String> tvosCpus =
          (options.tvosCpus == null || options.tvosCpus.isEmpty())
              ? ImmutableList.of(AppleCommandLineOptions.DEFAULT_TVOS_CPU)
              : ImmutableList.copyOf(options.tvosCpus);
      ImmutableList<String> macosCpus =
          (options.macosCpus == null || options.macosCpus.isEmpty())
              ? ImmutableList.of(macosCpuFromCpu(coreOptions.cpu))
              : ImmutableList.copyOf(options.macosCpus);
      ImmutableList<String> catalystCpus =
          (options.catalystCpus == null || options.catalystCpus.isEmpty())
              ? ImmutableList.of(AppleCommandLineOptions.DEFAULT_CATALYST_CPU)
              : ImmutableList.copyOf(options.catalystCpus);

      return new AutoValue_AppleConfiguration_AppleCpus(
          iosCpu, appleSplitCpu, iosMultiCpus, watchosCpus, tvosCpus, macosCpus, catalystCpus);
    }

    abstract String iosCpu();

    abstract String appleSplitCpu();

    abstract ImmutableList<String> iosMultiCpus();

    abstract ImmutableList<String> watchosCpus();

    abstract ImmutableList<String> tvosCpus();

    abstract ImmutableList<String> macosCpus();

    abstract ImmutableList<String> catalystCpus();
  }

  /** Determines iOS cpu value from apple-specific toolchain identifier. */
  public static String iosCpuFromCpu(String cpu) {
    if (cpu.startsWith(IOS_CPU_PREFIX)) {
      return cpu.substring(IOS_CPU_PREFIX.length());
    } else {
      return DEFAULT_IOS_CPU;
    }
  }

  /** Determines macOS cpu value from apple-specific toolchain identifier. */
  private static String macosCpuFromCpu(String cpu) {
    if (cpu.startsWith(MACOS_CPU_PREFIX)) {
      return cpu.substring(MACOS_CPU_PREFIX.length());
    }
    return AppleCommandLineOptions.DEFAULT_MACOS_CPU;
  }

  public AppleCommandLineOptions getOptions() {
    return options;
  }

  /**
   * Returns a map of environment variables (derived from configuration) that should be propagated
   * for actions pertaining to building applications for apple platforms. These environment
   * variables are needed to use apple toolkits. Keys are variable names and values are their
   * corresponding values.
   */
  public static ImmutableMap <String, String> appleTargetPlatformEnv(
      ApplePlatform platform, DottedVersion sdkVersion) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    builder
        .put(AppleConfiguration.APPLE_SDK_VERSION_ENV_NAME,
            sdkVersion.toStringWithMinimumComponents(2))
        .put(AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME,
            platform.getNameInPlist());

    return builder.buildOrThrow();
  }

  /**
   * Returns a map of environment variables that should be propagated for actions that require a
   * version of xcode to be explicitly declared. Keys are variable names and values are their
   * corresponding values.
   */
  public static ImmutableMap<String, String> getXcodeVersionEnv(DottedVersion xcodeVersion) {
    if (xcodeVersion != null) {
      return ImmutableMap.of(AppleConfiguration.XCODE_VERSION_ENV_NAME, xcodeVersion.toString());
    } else {
      return ImmutableMap.of();
    }
  }

  /**
   * Gets the single "effective" architecture for this configuration's {@link PlatformType} (for
   * example, "i386" or "arm64"). Prefer this over {@link #getMultiArchitectures(PlatformType)} only
   * if in the context of rule logic which is only concerned with a single architecture (such as in
   * {@code objc_library}, which registers single-architecture compile actions).
   *
   * <p>Single effective architecture is determined using the following rules:
   *
   * <ol>
   * <li>If {@code --apple_split_cpu} is set (done via prior configuration transition), then that is
   *     the effective architecture.
   * <li>If the multi cpus flag (e.g. {@code --ios_multi_cpus}) is set and non-empty, then the first
   *     such architecture is returned.
   * <li>In the case of iOS, use {@code --ios_cpu} for backwards compatibility.
   * <li>Use the default.
   * </ol>
   */
  @Override
  public String getSingleArchitecture() {
    return getSingleArchitecture(applePlatformType, appleCpus, /* removeSimPrefix= */ true);
  }

  private static String getSingleArchitecture(
      PlatformType applePlatformType, AppleCpus appleCpus, boolean removeSimPrefix) {
    // The removeSimPrefix argument is necessary due to a simulator and device both using arm64
    // architecture. In the case of Starlark asking for the architecture, we should return the
    // actual architecture (arm64) but in other cases in this class what we actually want is the
    // CPU without the ios/tvos prefix (e.g. sim_arm64). This parameter is provided in the private
    // method so that internal to this class we are able to use both without duplicating retrieval
    // logic.
    // TODO(b/180572694): Remove removeSimPrefix parameter once platforms are used instead of CPU
    String cpu = getPrefixedAppleCpu(applePlatformType, appleCpus);
    if (removeSimPrefix && cpu.startsWith(FORCED_SIMULATOR_CPU_PREFIX)) {
      cpu = cpu.substring(FORCED_SIMULATOR_CPU_PREFIX.length());
    }
    return cpu;
  }

  private static String getPrefixedAppleCpu(PlatformType applePlatformType, AppleCpus appleCpus) {
    if (!Strings.isNullOrEmpty(appleCpus.appleSplitCpu())) {
      return appleCpus.appleSplitCpu();
    }
    switch (applePlatformType) {
      case IOS:
        return Iterables.getFirst(appleCpus.iosMultiCpus(), appleCpus.iosCpu());
      case WATCHOS:
        return appleCpus.watchosCpus().get(0);
      case TVOS:
        return appleCpus.tvosCpus().get(0);
      case MACOS:
        return appleCpus.macosCpus().get(0);
      case CATALYST:
        return appleCpus.catalystCpus().get(0);
      default:
        throw new IllegalArgumentException("Unhandled platform type " + applePlatformType);
    }
  }

  /**
   * Gets the "effective" architecture(s) for the given {@link PlatformType}. For example,
   * "i386" or "arm64". At least one architecture is always returned. Prefer this over
   * {@link #getSingleArchitecture} in rule logic which may support multiple architectures, such
   * as bundling rules.
   *
   * <p>Effective architecture(s) is determined using the following rules:
   * <ol>
   * <li>If {@code --apple_split_cpu} is set (done via prior configuration transition), then
   * that is the effective architecture.</li>
   * <li>If the multi-cpu flag (for example, {@code --ios_multi_cpus}) is non-empty, then, return
   * all architectures from that flag.</li>
   * <li>In the case of iOS, use {@code --ios_cpu} for backwards compatibility.</li>
   * <li>Use the default.</li></ol>
   *
   * @throws IllegalArgumentException if {@code --apple_platform_type} is set (via prior
   *     configuration transition) yet does not match {@code platformType}
   */
  public List<String> getMultiArchitectures(PlatformType platformType) {
    if (!Strings.isNullOrEmpty(appleCpus.appleSplitCpu())) {
      if (applePlatformType != platformType) {
        throw new IllegalArgumentException(
            String.format("Expected post-split-transition platform type %s to match input %s ",
                applePlatformType, platformType));
      }
      return ImmutableList.of(appleCpus.appleSplitCpu());
    }
    switch (platformType) {
      case IOS:
        ImmutableList<String> cpus = appleCpus.iosMultiCpus();
        if (cpus.isEmpty()) {
          return ImmutableList.of(appleCpus.iosCpu());
        } else {
          return cpus;
        }
      case WATCHOS:
        return appleCpus.watchosCpus();
      case TVOS:
        return appleCpus.tvosCpus();
      case MACOS:
        return appleCpus.macosCpus();
      case CATALYST:
        return appleCpus.catalystCpus();
      default:
        throw new IllegalArgumentException("Unhandled platform type " + platformType);
    }
  }

  /**
   * Gets the single "effective" platform for this configuration's {@link PlatformType} and
   * architecture. Prefer this over {@link #getMultiArchPlatform(PlatformType)} only in cases if in
   * the context of rule logic which is only concerned with a single architecture (such as in {@code
   * objc_library}, which registers single-architecture compile actions).
   */
  @Override
  public ApplePlatform getSingleArchPlatform() {
    return ApplePlatform.forTarget(
        applePlatformType,
        getSingleArchitecture(applePlatformType, appleCpus, /* removeSimPrefix= */ false));
  }

  /**
   * Gets the current configuration {@link ApplePlatform} for the given {@link PlatformType}.
   * ApplePlatform is determined via a combination between the given platform type and the
   * "effective" architectures of this configuration, as returned by {@link #getMultiArchitectures};
   * if any of the supported architectures are of device type, this will return a device platform.
   * Otherwise, this will return a simulator platform.
   */
  // TODO(bazel-team): This should support returning multiple platforms.
  @Override
  public ApplePlatform getMultiArchPlatform(PlatformType platformType) {
    List<String> architectures = getMultiArchitectures(platformType);
    switch (platformType) {
      case IOS:
        for (String arch : architectures) {
          if (ApplePlatform.forTarget(PlatformType.IOS, arch) == ApplePlatform.IOS_DEVICE) {
            return ApplePlatform.IOS_DEVICE;
          }
        }
        return ApplePlatform.IOS_SIMULATOR;
      case WATCHOS:
        for (String arch : architectures) {
          if (ApplePlatform.forTarget(PlatformType.WATCHOS, arch) == ApplePlatform.WATCHOS_DEVICE) {
            return ApplePlatform.WATCHOS_DEVICE;
          }
        }
        return ApplePlatform.WATCHOS_SIMULATOR;
      case TVOS:
        for (String arch : architectures) {
          if (ApplePlatform.forTarget(PlatformType.TVOS, arch) == ApplePlatform.TVOS_DEVICE) {
            return ApplePlatform.TVOS_DEVICE;
          }
        }
        return ApplePlatform.TVOS_SIMULATOR;
      case MACOS:
        return ApplePlatform.MACOS;
      case CATALYST:
        return ApplePlatform.CATALYST;
      default:
        throw new IllegalArgumentException("Unsupported platform type " + platformType);
    }
  }

  /**
   * Returns the bitcode mode to use for compilation steps. This should only be invoked in
   * single-architecture contexts.
   *
   * <p>Users can control bitcode mode using the {@code apple_bitcode} build flag, but bitcode
   * will be disabled for all simulator architectures regardless of this flag.
   *
   * @see AppleBitcodeMode
   */
  @Override
  public AppleBitcodeMode getBitcodeMode() {
    return getAppleBitcodeMode(applePlatformType, appleCpus, platformBitcodeModes);
  }

  /** Returns the bitcode mode to use for compilation steps. */
  public static AppleBitcodeMode getAppleBitcodeMode(
      PlatformType applePlatformType,
      AppleCpus appleCpus,
      EnumMap<ApplePlatform.PlatformType, AppleBitcodeMode> platformBitcodeModes) {
    String architecture =
        getSingleArchitecture(applePlatformType, appleCpus, /* removeSimPrefix= */ false);
    String cpuString = ApplePlatform.cpuStringForTarget(applePlatformType, architecture);
    if (ApplePlatform.isApplePlatform(cpuString)) {
      ApplePlatform platform = ApplePlatform.forTarget(applePlatformType, architecture);
      if (platform.isDevice()) {
        return platformBitcodeModes.get(applePlatformType);
      }
    }
    return AppleBitcodeMode.NONE;
  }

  /**
   * Returns the label of the xcode_config rule to use for resolving the host system xcode version.
   */
  @StarlarkConfigurationField(
      name = "xcode_config_label",
      doc = "Returns the target denoted by the value of the --xcode_version_config flag",
      defaultLabel = AppleCommandLineOptions.DEFAULT_XCODE_VERSION_CONFIG_LABEL,
      defaultInToolRepository = true)
  public Label getXcodeConfigLabel() {
    return xcodeConfigLabel;
  }

  @Nullable
  @Override
  public String getOutputDirectoryName() {
    List<String> components = new ArrayList<>();
    if (!appleCpus.appleSplitCpu().isEmpty()) {
      components.add(applePlatformType.toString().toLowerCase());
      components.add(appleCpus.appleSplitCpu());

      if (options.getMinimumOsVersion() != null) {
        components.add("min" + options.getMinimumOsVersion());
      }
    }
    if (configurationDistinguisher != ConfigurationDistinguisher.UNKNOWN) {
      components.add(configurationDistinguisher.getFileSystemName());
    }

    if (components.isEmpty()) {
      return null;
    }
    return Joiner.on('-').join(components);
  }

  /** Returns true if the minimum_os_version attribute should be mandatory on rules with linking. */
  @Override
  public boolean isMandatoryMinimumVersionForStarlark(StarlarkThread thread) throws EvalException {
    RepositoryName repository =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread))
            .label()
            .getRepository();
    if (!"@_builtins".equals(repository.getNameWithAt())) {
      throw Starlark.errorf("private API only for use by builtins");
    }
    return isMandatoryMinimumVersion();
  }

  public boolean isMandatoryMinimumVersion() {
    return mandatoryMinimumVersion;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof AppleConfiguration)) {
      return false;
    }
    AppleConfiguration that = (AppleConfiguration) obj;
    return this.options.equals(that.options);
  }

  @Override
  public int hashCode() {
    return options.hashCode();
  }

  /**
   * Compute the platform-type-to-bitcode-mode mapping from the pairs that were passed on the
   * command line.
   */
  public static EnumMap<ApplePlatform.PlatformType, AppleBitcodeMode> collectBitcodeModes(
      List<Map.Entry<ApplePlatform.PlatformType, AppleBitcodeMode>> platformModeMappings) {
    EnumMap<ApplePlatform.PlatformType, AppleBitcodeMode> modes =
        new EnumMap<>(ApplePlatform.PlatformType.class);
    ApplePlatform.PlatformType[] allPlatforms = ApplePlatform.PlatformType.values();

    // Seed the map with the default mode for every key so that there is a valid mode for every
    // platform.
    // TODO(blaze-team): Default to embedded_markers when fully implemented.
    Arrays.stream(allPlatforms).forEach(platform -> modes.put(platform, AppleBitcodeMode.NONE));

    // Process the entries in order. If we encounter one with a null key, apply the mode to all
    // platforms; otherwise, apply it only to that specific platform. This ensures that the later
    // options override the earlier options.
    for (Map.Entry<ApplePlatform.PlatformType, AppleBitcodeMode> entry : platformModeMappings) {
      if (entry.getKey() == null) {
        Arrays.stream(allPlatforms).forEach(platform -> modes.put(platform, entry.getValue()));
      } else {
        modes.put(entry.getKey(), entry.getValue());
      }
    }

    return modes;
  }

  /**
   * Value used to avoid multiple configurations from conflicting. No two instances of this
   * transition may exist with the same value in a single Bazel invocation.
   */
  public enum ConfigurationDistinguisher implements StarlarkValue {
    UNKNOWN("unknown"),
    /** Distinguisher for {@code apple_binary} rule with "ios" platform_type. */
    APPLEBIN_IOS("applebin_ios"),
    /** Distinguisher for {@code apple_binary} rule with "watchos" platform_type. */
    APPLEBIN_WATCHOS("applebin_watchos"),
    /** Distinguisher for {@code apple_binary} rule with "tvos" platform_type. */
    APPLEBIN_TVOS("applebin_tvos"),
    /** Distinguisher for {@code apple_binary} rule with "macos" platform_type. */
    APPLEBIN_MACOS("applebin_macos"),
    /** Distinguisher for {@code apple_binary} rule with "catalyst" platform_type. */
    APPLEBIN_CATALYST("applebin_catalyst"),

    /**
     * Distinguisher for the apple crosstool configuration.  We use "apl" for output directory
     * names instead of "apple_crosstool" to avoid oversized path names, which can be problematic
     * on OSX.
     */
    APPLE_CROSSTOOL("apl");

    private final String fileSystemName;

    private ConfigurationDistinguisher(String fileSystemName) {
      this.fileSystemName = fileSystemName;
    }

    /**
     * Returns the distinct string that should be used in creating output directories for a
     * configuration with this distinguisher.
     */
    public String getFileSystemName() {
      return fileSystemName;
    }
  }
}
