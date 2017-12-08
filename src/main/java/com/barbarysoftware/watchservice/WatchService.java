package com.barbarysoftware.watchservice;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public abstract class WatchService implements Closeable {

    protected WatchService() {
    }

    /**
     * Closes this watch service.
     * <p/>
     * <p> If a thread is currently blocked in the {@link #take take} or {@link
     * #poll(long,TimeUnit) poll} methods waiting for a key to be queued then
     * it immediately receives a {@link ClosedWatchServiceException}. Any
     * valid keys associated with this watch service are {@link WatchKey#isValid
     * invalidated}.
     * <p/>
     * <p> After a watch service is closed, any further attempt to invoke
     * operations upon it will throw {@link ClosedWatchServiceException}.
     * If this watch service is already closed then invoking this method
     * has no effect.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public abstract void close() throws IOException;

    /**
     * Retrieves and removes the next watch key, or {@code null} if none are
     * present.
     *
     * @return the next watch key, or {@code null}
     * @throws ClosedWatchServiceException if this watch service is closed
     */
    public abstract WatchKey poll();

    /**
     * Retrieves and removes the next watch key, waiting if necessary up to the
     * specified wait time if none are yet present.
     *
     * @param timeout how to wait before giving up, in units of unit
     * @param unit    a {@code TimeUnit} determining how to interpret the timeout
     *                parameter
     * @return the next watch key, or {@code null}
     * @throws ClosedWatchServiceException if this watch service is closed, or it is closed while waiting
     *                                     for the next key
     * @throws InterruptedException        if interrupted while waiting
     */
    public abstract WatchKey poll(long timeout, TimeUnit unit)
            throws InterruptedException;

    /**
     * Retrieves and removes next watch key, waiting if none are yet present.
     *
     * @return the next watch key
     * @throws ClosedWatchServiceException if this watch service is closed, or it is closed while waiting
     *                                     for the next key
     * @throws InterruptedException        if interrupted while waiting
     */
    public abstract WatchKey take() throws InterruptedException;

    public static WatchService newWatchService() {
        return newWatchService(null, null, 0, false);
    }
    
    /**
     * @param includeDirInRecursion - allows to filter out undesired sub-directories (e.d. node_modules),
     *                              if NULL then all sub-directories will be included,
     *                              if specified and returns FALSE then the directory and all sub-directories will be excluded   
     * @param invokeOnDirChange - allows to ignore changes on particular directories
     * @param warningOnMaxNumOfWatchedFiles - if more than 0 then prints a warning if number of watched files exceeds this number 
     * @param debug - set to TRUE to log information about watched files
     * @return
     */
    public static WatchService newWatchService(Function<File, Boolean> includeDirInRecursion, Function<File, Boolean> invokeOnDirChange, int warningOnMaxNumOfWatchedFiles, boolean debug) {        
        final String osVersion = System.getProperty("os.version");
        final int minorVersion = Integer.parseInt(osVersion.split("\\.")[1]);
        if (minorVersion < 5) {
            // for Mac OS X prior to Leopard (10.5)
            return new MacOSXPollingWatchService();
            
        } else {
            // for Mac OS X Leopard (10.5) and upwards
            return new MacOSXListeningWatchService(includeDirInRecursion, invokeOnDirChange, warningOnMaxNumOfWatchedFiles, debug);
        }
    }

}
