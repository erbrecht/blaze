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
package com.fizzed.blaze.kotlin;

import com.fizzed.blaze.Context;
import com.fizzed.blaze.core.BlazeException;
import com.fizzed.blaze.core.AbstractEngine;
import com.fizzed.blaze.internal.ClassLoaderHelper;
import static com.fizzed.blaze.internal.ClassLoaderHelper.currentThreadContextClassLoader;
import com.fizzed.blaze.internal.ConfigHelper;
import com.fizzed.blaze.internal.FileHelper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlazeKotlinEngine extends AbstractEngine<BlazeKotlinScript> {
    static private final Logger log = LoggerFactory.getLogger(BlazeKotlinEngine.class);

    @Override
    public String getName() {
        return "kotlin";
    }
    
    @Override
    public String getFileExtension() {
        return ".kt";
    }
    
    @Override
    public void init(Context initialContext) throws BlazeException {
        super.init(initialContext);
    }

    @Override
    public BlazeKotlinScript compile(Context context) throws BlazeException {
        // what class would we be producing?
        // hello.kt -> Hello
        String className = context.scriptFile().toFile().getName().replace(".kt", "");
        className = className.substring(0, 1).toUpperCase() + className.substring(1);
        
        ClassLoader classLoader = currentThreadContextClassLoader();
        Path classesDir = null;
        Path expectedClassFile = null;
        String scriptHash = null;
        boolean compile = true;
        
        try {
            // directory to save compile classes on a semi-reliable basis
            classesDir = ConfigHelper.userEngineClassesDir(context, getName());
            log.trace("Using classes dir {}", classesDir);
            
            expectedClassFile = classesDir.resolve(className + ".class");
            
            // to check if we need to recompile we use an md5 hash of the source file
            scriptHash = FileHelper.md5hash(context.scriptFile());
            
            if (FileHelper.verifyHashFileFor(expectedClassFile, scriptHash)) {
                compile = false;
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new BlazeException("Unable to get or create path to compile classes", e);
        }
        
        if (!compile) {
            log.info("Script has not changed, using previous compiled version");
        } else {
            //javac(classLoader, context, classesDir);
            KotlinCompiler compiler = new KotlinCompiler(classLoader);
            
            try {
                compiler.compile(context.scriptFile(), classesDir);
            } catch (Exception e) {
                throw new BlazeException(e.getMessage(), e);
            }
            
            try {
                // save the hash for future use
                FileHelper.writeHashFileFor(expectedClassFile, scriptHash);
            } catch (IOException e) {
                throw new BlazeException("Unable to save script hash", e);
            }
        }
        
        // add directory it was compiled to classpath
        if (ClassLoaderHelper.addClassPath(classLoader, classesDir)) {
            log.info("Added {} to classpath", classesDir);
        }
        
        // create new instance of this class
        try {
            Class<?> type = classLoader.loadClass(className);
            
            Object targetObject = type.getConstructor().newInstance();

            return new BlazeKotlinScript(targetObject);
        } catch (ClassNotFoundException | InstantiationException | IllegalArgumentException |
                IllegalAccessException | NoSuchMethodException | InvocationTargetException | SecurityException e) {
            throw new BlazeException("Unable to load class '" + className + "'", e);
        }
    } 
}