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

import com.fizzed.blaze.Context;
import com.fizzed.blaze.core.Action;
import com.fizzed.blaze.core.BlazeException;
import com.fizzed.blaze.internal.ConfigHelper;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fizzed.blaze.core.PathsMixin;
import com.fizzed.blaze.util.ObjectHelper;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * which - locate a file for a command.
 * 
 * @author joelauer
 */
public class Which extends Action<Path> implements PathsMixin<Which> {
    private static final Logger log = LoggerFactory.getLogger(Which.class);
    
    private final List<Path> paths;
    private Path command;
    
    public Which(Context context) {
        super(context);
        // initialize with system environment PATHs
        this.paths = ConfigHelper.systemEnvironmentPaths();
    }

    public Path getCommand() {
        return this.command;
    }
    
    public Which command(String command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = Paths.get(command);
        return this;
    }
    
    public Which command(Path command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = command;
        return this;
    }
    
    public Which command(File command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = command.toPath();
        return this;
    }

    @Override
    public List<Path> getPaths() {
        return this.paths;
    }
    
    @Override
    protected Path doRun() throws BlazeException {
        return find(context, paths, command);
    }
    
    static public Path find(Context context, List<Path> paths, Path command) throws BlazeException {
        // first, check if the command is already an absolute file
        if (Files.exists(command)) {
            return command;
        }
        
        // second, check each path to see if the command exists
        for (Path path : paths) {
            List<String> commandExtensions = ConfigHelper.commandExtensions(context.config());
            for (String ext : commandExtensions) {
                // cmd -> cmd.exe
                String commandWithExt = command.toString() + ext;
                
                //File commandFile = new File(path.toFile(), commandWithExt);
                Path exeFile = path.resolve(commandWithExt);
                
                //logger.trace("commandFile: {}", commandFile);
                //File f = commandFile;
                
                log.trace("Trying file: {}", exeFile);
                if (Files.exists(exeFile)) {
                    if (Files.isExecutable(exeFile)) {
                        return exeFile;
                    } else {
                        log.warn("Command '" + exeFile + "' found but it isn't executable! (continuing search...)");
                    }
                }
            }
        }
        
        return null;
    }
    
}
