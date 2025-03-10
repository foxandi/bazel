// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.builder;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConstraintValueLookupUtil.InvalidConstraintValueException;
import com.google.devtools.build.lib.skyframe.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.RegisteredExecutionPlatformsFunction.InvalidExecutionPlatformLabelException;
import com.google.devtools.build.lib.skyframe.RegisteredToolchainsFunction.InvalidToolchainLabelException;
import com.google.devtools.build.lib.skyframe.SingleToolchainResolutionFunction.NoToolchainFoundException;
import com.google.devtools.build.lib.skyframe.SingleToolchainResolutionValue.SingleToolchainResolutionKey;
import com.google.devtools.build.lib.skyframe.ToolchainTypeLookupUtil.InvalidToolchainTypeException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeIterableResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Sky function which performs toolchain resolution for multiple toolchain types, including
 * selecting the execution platform.
 */
public class ToolchainResolutionFunction implements SkyFunction {

  @Nullable
  @Override
  public UnloadedToolchainContext compute(SkyKey skyKey, Environment env)
      throws ToolchainResolutionFunctionException, InterruptedException {
    ToolchainContextKey key = (ToolchainContextKey) skyKey.argument();

    try {
      UnloadedToolchainContextImpl.Builder builder = UnloadedToolchainContextImpl.builder(key);

      // Determine the configuration being used.
      BuildConfigurationValue configuration =
          (BuildConfigurationValue) env.getValue(key.configurationKey());
      if (configuration == null) {
        throw new ValueMissingException();
      }
      PlatformConfiguration platformConfiguration =
          Preconditions.checkNotNull(configuration.getFragment(PlatformConfiguration.class));

      // Check if debug output should be generated.
      boolean debug =
          key.debugTarget()
              || configuration
                  .getFragment(PlatformConfiguration.class)
                  .debugToolchainResolution(
                      key.toolchainTypes().stream()
                          .map(ToolchainTypeRequirement::toolchainType)
                          .collect(toImmutableSet()));

      // Load the configured target for the toolchain types to ensure that they are valid and
      // resolve aliases.
      ImmutableMap<Label, ToolchainTypeInfo> resolvedToolchainTypeInfos =
          loadToolchainTypes(
              env,
              configuration,
              key.toolchainTypes().stream()
                  .map(ToolchainTypeRequirement::toolchainType)
                  .collect(toImmutableSet()));
      builder.setRequestedLabelToToolchainType(resolvedToolchainTypeInfos);
      ImmutableSet<ToolchainTypeRequirement> resolvedToolchainTypes =
          loadToolchainTypeRequirements(resolvedToolchainTypeInfos, key.toolchainTypes());

      // Create keys for all platforms that will be used, and validate them early.
      PlatformKeys platformKeys =
          loadPlatformKeys(
              env,
              debug,
              key.configurationKey(),
              configuration,
              platformConfiguration,
              key.execConstraintLabels());
      if (env.valuesMissing()) {
        return null;
      }

      // Determine the actual toolchain implementations to use.
      determineToolchainImplementations(
          env,
          key.configurationKey(),
          resolvedToolchainTypes,
          key.forceExecutionPlatform().map(platformKeys::find),
          builder,
          platformKeys,
          key.debugTarget());

      UnloadedToolchainContext unloadedToolchainContext = builder.build();
      if (debug) {
        String selectedToolchains =
            unloadedToolchainContext.toolchainTypeToResolved().entries().stream()
                .map(
                    e ->
                        String.format(
                            "type %s -> toolchain %s", e.getKey().typeLabel(), e.getValue()))
                .collect(joining(", "));
        env.getListener()
            .handle(
                Event.info(
                    String.format(
                        "ToolchainResolution: Target platform %s: Selected execution platform %s,"
                            + " %s",
                        unloadedToolchainContext.targetPlatform().label(),
                        unloadedToolchainContext.executionPlatform().label(),
                        selectedToolchains)));
      }
      return unloadedToolchainContext;
    } catch (ToolchainException e) {
      throw new ToolchainResolutionFunctionException(e);
    } catch (ValueMissingException e) {
      return null;
    }
  }

  /**
   * Returns a map from the requested toolchain type Label (after any alias chains) to the {@link
   * ToolchainTypeInfo} provider.
   */
  private static ImmutableMap<Label, ToolchainTypeInfo> loadToolchainTypes(
      Environment environment,
      BuildConfigurationValue configuration,
      ImmutableSet<Label> toolchainTypeLabels)
      throws InvalidToolchainTypeException, InterruptedException, ValueMissingException {
    ImmutableSet<ConfiguredTargetKey> toolchainTypeKeys =
        toolchainTypeLabels.stream()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableSet());

    ImmutableMap<Label, ToolchainTypeInfo> resolvedToolchainTypes =
        ToolchainTypeLookupUtil.resolveToolchainTypes(environment, toolchainTypeKeys);
    if (environment.valuesMissing()) {
      throw new ValueMissingException();
    }
    return resolvedToolchainTypes;
  }

  /**
   * Returns a map from the actual post-alias Label to the ToolchainTypeRequirement for that type.
   */
  private ImmutableSet<ToolchainTypeRequirement> loadToolchainTypeRequirements(
      ImmutableMap<Label, ToolchainTypeInfo> resolvedToolchainTypeInfos,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes) {
    ImmutableSet.Builder<ToolchainTypeRequirement> resolved = new ImmutableSet.Builder<>();

    for (ToolchainTypeRequirement toolchainTypeRequirement : toolchainTypes) {
      // Find the actual Label.
      Label toolchainTypeLabel = toolchainTypeRequirement.toolchainType();
      if (resolvedToolchainTypeInfos.containsKey(toolchainTypeLabel)) {
        toolchainTypeLabel = resolvedToolchainTypeInfos.get(toolchainTypeLabel).typeLabel();
      }

      // If the labels don't match, re-build the TTR.
      if (!toolchainTypeLabel.equals(toolchainTypeRequirement.toolchainType())) {
        toolchainTypeRequirement =
            toolchainTypeRequirement.toBuilder().toolchainType(toolchainTypeLabel).build();
      }

      resolved.add(toolchainTypeRequirement);
    }
    return resolved.build();
  }

  @AutoValue
  abstract static class PlatformKeys {
    abstract ConfiguredTargetKey hostPlatformKey();

    abstract ConfiguredTargetKey targetPlatformKey();

    abstract ImmutableList<ConfiguredTargetKey> executionPlatformKeys();

    @Nullable
    public ConfiguredTargetKey find(Label platformLabel) {
      if (platformLabel.equals(hostPlatformKey().getLabel())) {
        return hostPlatformKey();
      }
      if (platformLabel.equals(targetPlatformKey().getLabel())) {
        return targetPlatformKey();
      }

      for (ConfiguredTargetKey configuredTargetKey : executionPlatformKeys()) {
        if (platformLabel.equals(configuredTargetKey.getLabel())) {
          return configuredTargetKey;
        }
      }

      return null;
    }

    static PlatformKeys create(
        ConfiguredTargetKey hostPlatformKey,
        ConfiguredTargetKey targetPlatformKey,
        List<ConfiguredTargetKey> executionPlatformKeys) {
      return new AutoValue_ToolchainResolutionFunction_PlatformKeys(
          hostPlatformKey, targetPlatformKey, ImmutableList.copyOf(executionPlatformKeys));
    }
  }

  private static PlatformKeys loadPlatformKeys(
      SkyFunction.Environment environment,
      boolean debug,
      BuildConfigurationKey configurationKey,
      BuildConfigurationValue configuration,
      PlatformConfiguration platformConfiguration,
      ImmutableSet<Label> execConstraintLabels)
      throws InterruptedException, ValueMissingException, InvalidConstraintValueException,
          InvalidPlatformException, InvalidExecutionPlatformLabelException {
    // Determine the target and host platform keys.
    Label hostPlatformLabel = platformConfiguration.getHostPlatform();
    Label targetPlatformLabel = platformConfiguration.getTargetPlatform();

    ConfiguredTargetKey hostPlatformKey =
        ConfiguredTargetKey.builder()
            .setLabel(hostPlatformLabel)
            .setConfiguration(configuration)
            .build();
    ConfiguredTargetKey targetPlatformKey =
        ConfiguredTargetKey.builder()
            .setLabel(targetPlatformLabel)
            .setConfiguration(configuration)
            .build();

    // Load the host and target platforms early, to check for errors.
    PlatformLookupUtil.getPlatformInfo(
        ImmutableList.of(hostPlatformKey, targetPlatformKey), environment);
    if (environment.valuesMissing()) {
      throw new ValueMissingException();
    }

    ImmutableList<ConfiguredTargetKey> executionPlatformKeys =
        loadExecutionPlatformKeys(
            environment,
            debug,
            configurationKey,
            configuration,
            hostPlatformKey,
            execConstraintLabels);

    return PlatformKeys.create(hostPlatformKey, targetPlatformKey, executionPlatformKeys);
  }

  private static ImmutableList<ConfiguredTargetKey> loadExecutionPlatformKeys(
      SkyFunction.Environment environment,
      boolean debug,
      BuildConfigurationKey configurationKey,
      BuildConfigurationValue configuration,
      ConfiguredTargetKey defaultPlatformKey,
      ImmutableSet<Label> execConstraintLabels)
      throws InterruptedException, ValueMissingException, InvalidConstraintValueException,
          InvalidPlatformException, InvalidExecutionPlatformLabelException {
    RegisteredExecutionPlatformsValue registeredExecutionPlatforms =
        (RegisteredExecutionPlatformsValue)
            environment.getValueOrThrow(
                RegisteredExecutionPlatformsValue.key(configurationKey),
                InvalidPlatformException.class,
                InvalidExecutionPlatformLabelException.class);
    if (registeredExecutionPlatforms == null) {
      throw new ValueMissingException();
    }

    ImmutableList<ConfiguredTargetKey> availableExecutionPlatformKeys =
        new ImmutableList.Builder<ConfiguredTargetKey>()
            .addAll(registeredExecutionPlatforms.registeredExecutionPlatformKeys())
            .add(defaultPlatformKey)
            .build();

    // Filter out execution platforms that don't satisfy the extra constraints.
    ImmutableList<ConfiguredTargetKey> execConstraintKeys =
        execConstraintLabels.stream()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableList());

    return filterAvailablePlatforms(
        environment, debug, availableExecutionPlatformKeys, execConstraintKeys);
  }

  /** Returns only the platform keys that match the given constraints. */
  private static ImmutableList<ConfiguredTargetKey> filterAvailablePlatforms(
      SkyFunction.Environment environment,
      boolean debug,
      ImmutableList<ConfiguredTargetKey> platformKeys,
      ImmutableList<ConfiguredTargetKey> constraintKeys)
      throws InterruptedException, ValueMissingException, InvalidConstraintValueException,
          InvalidPlatformException {

    // Short circuit if not needed.
    if (constraintKeys.isEmpty()) {
      return platformKeys;
    }

    // At this point the host and target platforms have been loaded, but not necessarily the chosen
    // execution platform (it might be the same as the host platform, and might not).
    //
    // It's not worth trying to optimize away this call, since in the optimizable case (the exec
    // platform is the host platform), Skyframe will return the correct results immediately without
    // need of a restart.
    Map<ConfiguredTargetKey, PlatformInfo> platformInfoMap =
        PlatformLookupUtil.getPlatformInfo(platformKeys, environment);
    if (platformInfoMap == null) {
      throw new ValueMissingException();
    }
    List<ConstraintValueInfo> constraints =
        ConstraintValueLookupUtil.getConstraintValueInfo(constraintKeys, environment);
    if (constraints == null) {
      throw new ValueMissingException();
    }

    return platformKeys.stream()
        .filter(key -> filterPlatform(environment, debug, platformInfoMap.get(key), constraints))
        .collect(toImmutableList());
  }

  /** Returns {@code true} if the given platform has all of the constraints. */
  private static boolean filterPlatform(
      SkyFunction.Environment environment,
      boolean debug,
      PlatformInfo platformInfo,
      List<ConstraintValueInfo> constraints) {
    ImmutableList<ConstraintValueInfo> missingConstraints =
        platformInfo.constraints().findMissing(constraints);
    if (debug) {
      for (ConstraintValueInfo constraint : missingConstraints) {
        // The value for this setting is not present in the platform, or doesn't match the expected
        // value.
        environment
            .getListener()
            .handle(
                Event.info(
                    String.format(
                        "ToolchainResolution: Removed execution platform %s from"
                            + " available execution platforms, it is missing constraint %s",
                        platformInfo.label(), constraint.label())));
      }
    }

    return missingConstraints.isEmpty();
  }

  private static void determineToolchainImplementations(
      Environment environment,
      BuildConfigurationKey configurationKey,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      Optional<ConfiguredTargetKey> forcedExecutionPlatform,
      UnloadedToolchainContextImpl.Builder builder,
      PlatformKeys platformKeys,
      boolean debugTarget)
      throws InterruptedException, ValueMissingException, InvalidPlatformException,
          NoMatchingPlatformException, UnresolvedToolchainsException,
          InvalidToolchainLabelException {

    // Find the toolchains for the requested toolchain types.
    List<SingleToolchainResolutionKey> registeredToolchainKeys = new ArrayList<>();
    for (ToolchainTypeRequirement toolchainType : toolchainTypes) {
      registeredToolchainKeys.add(
          SingleToolchainResolutionValue.key(
              configurationKey,
              toolchainType,
              platformKeys.targetPlatformKey(),
              platformKeys.executionPlatformKeys(),
              debugTarget));
    }

    SkyframeIterableResult results =
        environment.getOrderedValuesAndExceptions(registeredToolchainKeys);
    boolean valuesMissing = false;

    // Determine the potential set of toolchains.
    Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> resolvedToolchains =
        HashBasedTable.create();
    ImmutableSet.Builder<ToolchainTypeInfo> requiredToolchainTypesBuilder = ImmutableSet.builder();
    List<Label> missingToolchains = new ArrayList<>();
    while (results.hasNext()) {
      try {
        SingleToolchainResolutionValue singleToolchainResolutionValue =
            (SingleToolchainResolutionValue)
                results.nextOrThrow(
                    NoToolchainFoundException.class, InvalidToolchainLabelException.class);
        if (singleToolchainResolutionValue == null) {
          valuesMissing = true;
          continue;
        }

        ToolchainTypeInfo requiredToolchainType = singleToolchainResolutionValue.toolchainType();
        requiredToolchainTypesBuilder.add(requiredToolchainType);
        resolvedToolchains.putAll(
            findPlatformsAndLabels(requiredToolchainType, singleToolchainResolutionValue));
      } catch (NoToolchainFoundException e) {
        // Save the missing type and continue looping to check for more.
        missingToolchains.add(e.missingToolchainType().toolchainType());
      }
    }

    if (!missingToolchains.isEmpty()) {
      throw new UnresolvedToolchainsException(missingToolchains);
    }

    if (valuesMissing) {
      throw new ValueMissingException();
    }

    ImmutableSet<ToolchainTypeInfo> requiredToolchainTypes = requiredToolchainTypesBuilder.build();

    // Find and return the first execution platform which has all required toolchains.
    // TODO(katre): Handle mandatory/optional.
    Optional<ConfiguredTargetKey> selectedExecutionPlatformKey =
        findExecutionPlatformForToolchains(
            requiredToolchainTypes,
            forcedExecutionPlatform,
            platformKeys.executionPlatformKeys(),
            resolvedToolchains);

    if (selectedExecutionPlatformKey.isEmpty()) {
      throw new NoMatchingPlatformException(
          toolchainTypes, platformKeys.executionPlatformKeys(), platformKeys.targetPlatformKey());
    }

    Map<ConfiguredTargetKey, PlatformInfo> platforms =
        PlatformLookupUtil.getPlatformInfo(
            ImmutableList.of(selectedExecutionPlatformKey.get(), platformKeys.targetPlatformKey()),
            environment);
    if (platforms == null) {
      throw new ValueMissingException();
    }

    builder.setToolchainTypes(toolchainTypes);
    builder.setExecutionPlatform(platforms.get(selectedExecutionPlatformKey.get()));
    builder.setTargetPlatform(platforms.get(platformKeys.targetPlatformKey()));

    Map<ToolchainTypeInfo, Label> toolchains =
        resolvedToolchains.row(selectedExecutionPlatformKey.get());
    builder.setToolchainTypeToResolved(ImmutableSetMultimap.copyOf(toolchains.entrySet()));
  }

  /**
   * Adds all of toolchain labels from {@code toolchainResolutionValue} to {@code
   * resolvedToolchains}.
   */
  private static Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> findPlatformsAndLabels(
      ToolchainTypeInfo requiredToolchainType,
      SingleToolchainResolutionValue singleToolchainResolutionValue) {

    Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> resolvedToolchains =
        HashBasedTable.create();
    for (Map.Entry<ConfiguredTargetKey, Label> entry :
        singleToolchainResolutionValue.availableToolchainLabels().entrySet()) {
      resolvedToolchains.put(entry.getKey(), requiredToolchainType, entry.getValue());
    }
    return resolvedToolchains;
  }

  /**
   * Finds the first platform from {@code availableExecutionPlatformKeys} that is present in {@code
   * resolvedToolchains} and has all required toolchain types.
   */
  private static Optional<ConfiguredTargetKey> findExecutionPlatformForToolchains(
      ImmutableSet<ToolchainTypeInfo> requiredToolchainTypes,
      Optional<ConfiguredTargetKey> forcedExecutionPlatform,
      ImmutableList<ConfiguredTargetKey> availableExecutionPlatformKeys,
      Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> resolvedToolchains) {

    if (forcedExecutionPlatform.isPresent()) {
      // Is the forced platform suitable?
      if (isPlatformSuitable(
          forcedExecutionPlatform.get(), requiredToolchainTypes, resolvedToolchains)) {
        return forcedExecutionPlatform;
      }
    }

    return availableExecutionPlatformKeys.stream()
        .filter(epk -> isPlatformSuitable(epk, requiredToolchainTypes, resolvedToolchains))
        .findFirst();
  }

  private static boolean isPlatformSuitable(
      ConfiguredTargetKey executionPlatformKey,
      ImmutableSet<ToolchainTypeInfo> requiredToolchainTypes,
      Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> resolvedToolchains) {
    if (requiredToolchainTypes.isEmpty()) {
      // Since there aren't any toolchains, we should be able to use any execution platform that
      // has made it this far.
      return true;
    }

    if (!resolvedToolchains.containsRow(executionPlatformKey)) {
      return false;
    }

    // Unless all toolchains are present, ignore this execution platform.
    return resolvedToolchains
        .row(executionPlatformKey)
        .keySet()
        .containsAll(requiredToolchainTypes);
  }

  private static final class ValueMissingException extends Exception {
    private ValueMissingException() {
      super();
    }
  }

  /** Exception used when no execution platform can be found. */
  static final class NoMatchingPlatformException extends ToolchainException {
    NoMatchingPlatformException(
        Set<ToolchainTypeRequirement> toolchainTypes,
        ImmutableList<ConfiguredTargetKey> availableExecutionPlatformKeys,
        ConfiguredTargetKey targetPlatformKey) {
      super(formatError(toolchainTypes, availableExecutionPlatformKeys, targetPlatformKey));
    }

    private static String formatError(
        Set<ToolchainTypeRequirement> toolchainTypes,
        ImmutableList<ConfiguredTargetKey> availableExecutionPlatformKeys,
        ConfiguredTargetKey targetPlatformKey) {
      if (toolchainTypes.isEmpty()) {
        return String.format(
            "Unable to find an execution platform for target platform %s"
                + " from available execution platforms [%s]",
            targetPlatformKey.getLabel(),
            availableExecutionPlatformKeys.stream()
                .map(key -> key.getLabel().toString())
                .collect(Collectors.joining(", ")));
      }
      return String.format(
          "Unable to find an execution platform for toolchains [%s] and target platform %s"
              + " from available execution platforms [%s]",
          toolchainTypes.stream()
              .map(ToolchainTypeRequirement::toolchainType)
              .map(Label::toString)
              .collect(joining(", ")),
          targetPlatformKey.getLabel(),
          availableExecutionPlatformKeys.stream()
              .map(key -> key.getLabel().toString())
              .collect(Collectors.joining(", ")));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.NO_MATCHING_EXECUTION_PLATFORM;
    }
  }

  /** Exception used when a toolchain type is required but no matching toolchain is found. */
  static final class UnresolvedToolchainsException extends ToolchainException {
    UnresolvedToolchainsException(List<Label> missingToolchainTypes) {
      super(getMessage(missingToolchainTypes));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.NO_MATCHING_TOOLCHAIN;
    }

    private static String getMessage(List<Label> missingToolchainTypes) {
      if (missingToolchainTypes.size() == 1
          && Iterables.getOnlyElement(missingToolchainTypes)
              .toString()
              .equals("@bazel_tools//tools/cpp:toolchain_type")) {
        return "No matching toolchains found for types @bazel_tools//tools/cpp:toolchain_type. "
            + "Maybe --incompatible_use_cc_configure_from_rules_cc has been flipped and there "
            + "is no default C++ toolchain added in the WORKSPACE file? See "
            + "https://github.com/bazelbuild/bazel/issues/10134 for details and migration "
            + "instructions.";
      }

      return String.format(
          "no matching toolchains found for types %s",
          missingToolchainTypes.stream().map(Label::toString).collect(joining(", ")));
    }
  }

  /** Used to indicate errors during the computation of an {@link UnloadedToolchainContextImpl}. */
  private static final class ToolchainResolutionFunctionException extends SkyFunctionException {
    ToolchainResolutionFunctionException(ToolchainException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
