/*
 * Copyright 2015 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.deps;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sorted list of inputs following the ES6 module ordering spec.
 * <p>
 * Orders such that each input always comes after its dependencies.
 * Circular references are allowed by emitting the current input in the moment
 * before a loop would complete.
 * <p>
 * The resulting order is not the same as the user-provided order but is
 * influenced by it since it may take more than one graph traversal to account
 * for all provided inputs and the graph traversals start with the first user
 * provided input and continue from there.
 * <p>
 * Also exposes other information about the inputs, like which inputs
 * do not provide symbols.
 *
 * @author stalcup@google.com (John Stalcup)
 */
public final class Es6SortedDependencies<INPUT extends DependencyInfo>
    implements SortedDependencies<INPUT> {

  private final List<INPUT> userOrderedInputs = new ArrayList<>();
  private final List<INPUT> importOrderedInputs = new ArrayList<>();
  private final Set<INPUT> completedInputs = new HashSet<>();
  private final Map<String, INPUT> nonExportingInputs = new LinkedHashMap<>();
  private final Map<String, INPUT> exportingInputBySymbolName = new HashMap<>();
  // Maps an input A to the inputs it depends on, ie, inputs that provide stuff that A requires.
  private final Multimap<INPUT, INPUT> importedInputByImportingInput = LinkedHashMultimap.create();

  public Es6SortedDependencies(List<INPUT> userOrderedInputs) {
    this.userOrderedInputs.addAll(userOrderedInputs);
    processInputs();
  }

  @Override
  public ImmutableList<INPUT> getDependenciesOf(List<INPUT> rootInputs, boolean sorted) {
    checkArgument(userOrderedInputs.containsAll(rootInputs));

    Set<INPUT> includedInputs = new HashSet<>();
    Deque<INPUT> worklist = new ArrayDeque<>(rootInputs);
    while (!worklist.isEmpty()) {
      INPUT input = worklist.pop();
      if (includedInputs.add(input)) {
        for (String symbolName : input.getRequires()) {
          INPUT importedSymbolName = exportingInputBySymbolName.get(symbolName);
          if (importedSymbolName != null) {
            worklist.add(importedSymbolName);
          }
        }
      }
    }

    ImmutableList.Builder<INPUT> builder = ImmutableList.builder();
    for (INPUT input : (sorted ? importOrderedInputs : userOrderedInputs)) {
      if (includedInputs.contains(input)) {
        builder.add(input);
      }
    }
    return builder.build();
  }

  @Override
  public INPUT getInputProviding(String symbolName) throws MissingProvideException {
    INPUT input = maybeGetInputProviding(symbolName);
    if (input != null) {
      return input;
    }

    throw new MissingProvideException(symbolName);
  }

  @Override
  public ImmutableList<INPUT> getInputsWithoutProvides() {
    return ImmutableList.copyOf(nonExportingInputs.values());
  }

  @Override
  public ImmutableList<INPUT> getSortedDependenciesOf(List<INPUT> roots) {
    return getDependenciesOf(roots, true);
  }

  @Override
  public List<INPUT> getSortedList() {
    return Collections.unmodifiableList(importOrderedInputs);
  }

  @Override
  public INPUT maybeGetInputProviding(String symbol) {
    if (exportingInputBySymbolName.containsKey(symbol)) {
      return exportingInputBySymbolName.get(symbol);
    }

    return nonExportingInputs.get(ModuleNames.fileToModuleName(symbol));
  }

  private void orderInput(INPUT input) {
    if (completedInputs.contains(input)) {
      return;
    }

    completedInputs.add(input);
    for (INPUT importedInput : importedInputByImportingInput.get(input)) {
      orderInput(importedInput);
    }

    // Emit an input after its imports have been emitted.
    importOrderedInputs.add(input);
  }

  private void processInputs() {
    // Index.
    for (INPUT userOrderedInput : userOrderedInputs) {
      Collection<String> provides = userOrderedInput.getProvides();
      String firstProvide = Iterables.getFirst(provides, null);
      if (firstProvide == null
          // TODO(sdh): It would be better to have a more robust way to distinguish
          // between actual provided symbols and synthetic symbols generated for
          // ES6 (or other) modules.  We can't read loadFlags here (to see if
          // the module type is 'es6') either, since that requires a full parse.
          // So for now we rely on the heuristic that all generated provides start
          // with "module$".
          || (provides.size() == 1 && firstProvide.startsWith("module$"))) {
        nonExportingInputs.put(
            ModuleNames.fileToModuleName(userOrderedInput.getName()), userOrderedInput);
      }
      for (String providedSymbolName : userOrderedInput.getProvides()) {
        exportingInputBySymbolName.put(providedSymbolName, userOrderedInput);
      }
    }
    for (INPUT userOrderedInput : userOrderedInputs) {
      for (String symbolName : userOrderedInput.getRequires()) {
        INPUT importedInput = exportingInputBySymbolName.get(symbolName);
        if (importedInput != null) {
          importedInputByImportingInput.put(userOrderedInput, importedInput);
        }
      }
    }

    // Order.
    // For each input, traverse in user-provided order.
    for (INPUT userOrderedInput : userOrderedInputs) {
      // Traverse the graph starting from this input and record any
      // newly-reached inputs.
      orderInput(userOrderedInput);
    }

    // Free temporary indexes.
    completedInputs.clear();
    importedInputByImportingInput.clear();
  }
}
