/*
 * Copyright (c) 2010-2016. Axon Framework
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

package org.axonframework.commandhandling.distributed.commandfilter;

import org.axonframework.commandhandling.CommandMessage;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DenyCommandNameFilter implements Predicate<CommandMessage<?>>, Serializable {
    private final Set<String> commandNames;

    public DenyCommandNameFilter(Set<String> commandNames) {
        this.commandNames = new HashSet<>(commandNames);
    }

    public DenyCommandNameFilter(String commandName) {
        this.commandNames = Collections.singleton(commandName);
    }

    @Override
    public boolean test(CommandMessage commandMessage) {
        return !commandNames.contains(commandMessage.getCommandName());
    }

    @Override
    public Predicate<CommandMessage<?>> and(Predicate<? super CommandMessage<?>> other) {
        if (other instanceof DenyCommandNameFilter) {
            return new DenyCommandNameFilter(
                    Stream.concat(commandNames.stream(), ((DenyCommandNameFilter) other).commandNames.stream())
                            .collect(Collectors.toSet())
            );
        } else {
            return (t) -> test(t) && other.test(t);
        }
    }

    @Override
    public Predicate<CommandMessage<?>> or(Predicate<? super CommandMessage<?>> other) {
        if (other instanceof DenyCommandNameFilter) {
            Set<String> otherCommandNames = ((DenyCommandNameFilter) other).commandNames;
            return new DenyCommandNameFilter(
                    commandNames.stream()
                            .filter(otherCommandNames::contains)
                            .collect(Collectors.toSet()));
        } else {
            return (t) -> test(t) || other.test(t);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DenyCommandNameFilter that = (DenyCommandNameFilter) o;
        return Objects.equals(commandNames, that.commandNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandNames);
    }

    @Override
    public String toString() {
        return "DenyCommandNameFilter{" +
                "commandNames=" + commandNames +
                '}';
    }
}
