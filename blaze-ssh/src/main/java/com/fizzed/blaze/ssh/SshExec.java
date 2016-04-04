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
package com.fizzed.blaze.ssh;

import com.fizzed.blaze.ssh.impl.JschSession;
import com.fizzed.blaze.Context;
import com.fizzed.blaze.core.Action;
import com.fizzed.blaze.core.UnexpectedExitValueException;
import com.fizzed.blaze.core.BlazeException;
import com.fizzed.blaze.util.ObjectHelper;
import com.fizzed.blaze.util.WrappedOutputStream;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fizzed.blaze.util.StreamableInput;
import com.fizzed.blaze.util.StreamableOutput;
import com.fizzed.blaze.util.Streamables;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.fizzed.blaze.core.ExecMixin;
import com.fizzed.blaze.ssh.impl.PathHelper;
import com.fizzed.blaze.util.WrappedInputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class SshExec extends Action<SshExec.Result,Integer> implements ExecMixin<SshExec> {
    static private final Logger log = LoggerFactory.getLogger(SshExec.class);

    final private SshSession session;
    private Path command;
    final private List<String> arguments;
    private StreamableInput pipeInput;
    private StreamableOutput pipeOutput;
    private StreamableOutput pipeError;
    private boolean pipeErrorToOutput;
    private Map<String,String> environment;
    private long timeout;
    final private List<Integer> exitValues;
    
    public SshExec(Context context, SshSession session) {
        super(context);
        this.pipeInput = Streamables.standardInput();
        this.pipeOutput = Streamables.standardOutput();
        this.pipeError = Streamables.standardError();
        this.pipeErrorToOutput = false;
        this.session = session;
        this.arguments = new ArrayList<>();
        this.exitValues = new ArrayList<>(Arrays.asList(0));
    }
    
    @Override
    public SshExec command(String command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = Paths.get(command);
        return this;
    }
    
    @Override
    public SshExec command(Path command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = command;
        return this;
    }
    
    @Override
    public SshExec command(File command) {
        ObjectHelper.requireNonNull("command", "command cannot be null");
        this.command = command.toPath();
        return this;
    }
    
    @Override
    public SshExec arg(Object argument) {
        this.arguments.add(ObjectHelper.nonNullToString(argument));
        return this;
    }

    @Override
    public SshExec args(Object... arguments) {
        this.arguments.addAll(ObjectHelper.nonNullToStringList(arguments));
        return this;
    }
    
    @Override
    public StreamableInput getPipeInput() {
        return this.pipeInput;
    }
    
    @Override
    public SshExec pipeInput(StreamableInput pipeInput) {
        this.pipeInput = pipeInput;
        return this;
    }

    @Override
    public StreamableOutput getPipeOutput() {
        return this.pipeOutput;
    }

    @Override
    public SshExec pipeOutput(StreamableOutput pipeOutput) {
        this.pipeOutput = pipeOutput;
        return this;
    }
    
    @Override
    public StreamableOutput getPipeError() {
        return this.pipeError;
    }
    
    @Override
    public SshExec pipeError(StreamableOutput pipeError) {
        this.pipeError = pipeError;
        return this;
    }

    @Override
    public SshExec pipeErrorToOutput(boolean pipeErrorToOutput) {
        this.pipeErrorToOutput = pipeErrorToOutput;
        return this;
    }

    @Override
    public SshExec env(String name, String value) {
        if (this.environment == null) {
            this.environment = new HashMap<>();
        }
        this.environment.put(name, value);
        return this;
    }

    @Override
    public SshExec timeout(long timeoutInMillis) {
        this.timeout = timeoutInMillis;
        return this;
    }
    
    @Override
    public SshExec exitValues(Integer... exitValues) {
        this.exitValues.clear();
        this.exitValues.addAll(Arrays.asList(exitValues));
        return this;
    }

    @Override
    protected Result doRun() throws BlazeException {
        Session jschSession = ((JschSession)session).getJschSession();
        ObjectHelper.requireNonNull(jschSession, "ssh session must be established first");
        ObjectHelper.requireNonNull(command, "ssh command cannot be null");
        
        ChannelExec channel = null;
        final AtomicReference<Thread> execThreadRef = new AtomicReference<>();
        try {
            channel = (ChannelExec)jschSession.openChannel("exec");
            
            // setup environment
            if (this.environment != null) {
                for (Map.Entry<String,String> entry : this.environment.entrySet()) {
                    log.debug("Adding env {}={}", entry.getKey(), entry.getValue());
                    channel.setEnv(entry.getKey(), entry.getValue());
                }
            }
            
            // NOTE: In order for JSCH to pump the inputstream to its outputstream
            // it starts an "exec thread" for every channel-exec it creates
            // that also includes non-null inputstream. The problem is that
            // JSCH's exec thread will block forever on the inputstream.read()
            // call unless the inputstream is actually closed.  Since we don't
            // want that behavior, we'll sneakily introduce a wrapped inputstream
            // that will supply an interruptible read() call. This is done in
            // 2 steps:  1) our read() method will use a combo of Thread.sleep()
            // and available() to provide a non-blocking read() that will also
            // response to a Thread.interrupt() call.  and 2) we'll capture
            // a reference to JSCH's exec thread by saving it when it actually
            // enters the read() method.
            if (this.pipeInput != null) {
                channel.setInputStream(new WrappedInputStream(this.pipeInput.stream()) {
                    @Override @SuppressWarnings("SleepWhileInLoop")
                    public int read(byte[] b, int off, int len) throws IOException {
                        // sneaky way of getting access to JSCH's internal
                        // exec thread since its the only thread to call read()
                        // on this input stream.  This is the thread we'll need
                        // to interrupt when we want to close the exec run.
                        execThreadRef.compareAndSet(null, Thread.currentThread());

                        // make this read interruptable by using Thread.sleep
                        do {
                            // non-blocking, how many bytes available
                            int available = this.available();
                            if (available == 0) {
                                try {
                                    // read would definitely block waiting for data
                                    Thread.sleep(50L);
                                } catch (InterruptedException ex) {
                                    throw new IOException("Interrupted while blocked on read()");
                                }
                            } else {
                                // read will not block
                                return input.read(b, off, len);
                            }
                        } while (true);
                    }
                });
            }
            
            // both streams closing signals exec is finished
            CountDownLatch outputStreamClosedSignal = new CountDownLatch(1);
            CountDownLatch errorStreamClosedSignal = new CountDownLatch(1);
            
            // determine final ouput and then wrap to monitor for close events
            //OutputStream finalPipeOutput = (this.captureOutputStream != null ? this.captureOutputStream : this.pipeOutput.stream());
            OutputStream finalPipeOutput = this.pipeOutput.stream();
            
            channel.setOutputStream(new WrappedOutputStream(finalPipeOutput) {
                @Override
                public void close() throws IOException {
                    outputStreamClosedSignal.countDown();
                }
            }, false);
            
            // determine final error and then wrap to monitor for close events
            OutputStream finalPipeError = (this.pipeErrorToOutput ? finalPipeOutput : this.pipeError.stream());
            
            channel.setErrStream(new WrappedOutputStream(finalPipeError) {
                @Override
                public void close() throws IOException {
                    errorStreamClosedSignal.countDown();
                }
            }, false);
            
            // building the command may be a little tricky, not sure about spaces...
            final StringBuilder sb = new StringBuilder(PathHelper.toString(command));
            
            arguments.stream().forEach((arg) -> {
                sb.append(" ");
                // TODO: should we actually escape instead such as " " -> "\ "???
                if (arg.contains(" ")) {
                    sb.append("'");
                    sb.append(arg);
                    sb.append("'");
                } else {
                    sb.append(arg);
                }
            });
            
            String finalCommand = sb.toString();
            
            log.debug("ssh-exec [{}]", finalCommand);
            
            channel.setCommand(finalCommand);
            
            // this connects and sends command
            channel.connect();
            
            // wait for both streams to be closed
            outputStreamClosedSignal.await();
            errorStreamClosedSignal.await();
            
            Integer exitValue = channel.getExitStatus();
            
            if (!this.exitValues.contains(exitValue)) {
                throw new UnexpectedExitValueException("Process exited with unexpected value", this.exitValues, exitValue);
            }
            
            return new Result(this, exitValue);
        } catch (JSchException | InterruptedException e) {
            throw new SshException(e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            // cleanup JSCH exec thread if it exists
            Thread execThread = execThreadRef.get();
            if (execThread != null) {
                log.trace("Interrupting thread [{}]", execThread.getName());
                execThread.interrupt();
            }
        }
    }
    
    static public class Result extends com.fizzed.blaze.core.Result<SshExec,Integer,Result> {
        
        Result(SshExec action, Integer value) {
            super(action, value);
        }
        
    }
    
}