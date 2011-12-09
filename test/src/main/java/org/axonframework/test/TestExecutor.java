/*
 * Copyright (c) 2010-2011. Axon Framework
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

package org.axonframework.test;

/**
 * Interface describing the operations available on a test fixture in the execution stage. In this stage, there is only
 * on operation: {@link #when(Object)}, which dispatches a command on this fixture's Command Bus.
 *
 * @author Allard Buijze
 * @since 0.6
 */
public interface TestExecutor {

    /**
     * Dispatches the given command to the appropriate command handler and records all activity in the fixture for
     * result validation. If the given <code>command</code> is a {@link org.axonframework.commandhandling.annotation.CommandMessage}
     * instance, it will be dispatched as-is. Any other object will cause the given <code>command</code> to be wrapped
     * in a {@code CommandMessage} as its payload.
     *
     * @param command The command to execute
     * @return a ResultValidator that can be used to validate the resulting actions of the command execution
     */
    ResultValidator when(Object command);
}
