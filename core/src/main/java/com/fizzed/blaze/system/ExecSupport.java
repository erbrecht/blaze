/*
 * Copyright 2015 Fizzed, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fizzed.blaze.system;

import java.util.concurrent.TimeUnit;

/**
 *
 * @author joelauer
 */
public interface ExecSupport<T> {

    /**
     * Adds one or more arguments by appending to existing list.
     * @param arguments
     * @return
     * @see #args(java.lang.Object...) For replacing existing arguments
     */
    T arg(Object... arguments);

    /**
     * Replaces existing arguments with one or more new arguments.
     * @param arguments
     * @return
     * @see #arg(java.lang.Object...) For adding to existing arguments rather
     *      than replacing
     */
    T args(Object... arguments);

    T captureOutput();

    T command(String command, Object... arguments);

    T env(String name, String value);

    T timeout(long timeoutInMillis);
    
    default public T timeout(long timeout, TimeUnit units) {
        this.timeout(TimeUnit.MILLISECONDS.convert(timeout, units));
        return (T)this;
    }
    
}