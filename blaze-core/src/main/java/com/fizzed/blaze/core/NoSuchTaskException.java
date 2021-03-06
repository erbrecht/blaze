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
package com.fizzed.blaze.core;

import com.fizzed.blaze.core.MessageOnlyException;

/**
 *
 * @author joelauer
 */
public class NoSuchTaskException extends MessageOnlyException {

    final private String task;
    
    public NoSuchTaskException(String task) {
        this(task, "Task '" + task + "' not found");
    }
    
    public NoSuchTaskException(String task, String msg) {
        super(msg);
        this.task = task;
    }

    public String getTask() {
        return task;
    }

}
