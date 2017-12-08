package com.barbarysoftware.watchservice;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.barbarysoftware.jna.CFArrayRef;
import com.barbarysoftware.jna.CFIndex;
import com.barbarysoftware.jna.CFRunLoopRef;
import com.barbarysoftware.jna.CFStringRef;
import com.barbarysoftware.jna.CarbonAPI;
import com.barbarysoftware.jna.FSEventStreamRef;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * This class contains the bulk of my implementation of the Watch Service API. It hooks into Carbon's
 * File System Events API.
 *
 * @author Steve McLeod
 */
class MacOSXListeningWatchService extends AbstractWatchService {

    private Function<File, Boolean> includeDirInRecursion = null;
    private Function<File, Boolean> invokeOnDirChange = null;	
	private boolean debug = false;

    public MacOSXListeningWatchService() {
        this(null, null, false);
    }

    public MacOSXListeningWatchService(
            Function<File, Boolean> includeDirInRecursion, 
            Function<File, Boolean> invokeOnDirChange, 
            boolean debug
    ) {
        this.includeDirInRecursion = includeDirInRecursion;
        this.invokeOnDirChange = invokeOnDirChange;
        this.debug = debug;
    }

    // need to keep reference to callbacks to prevent garbage collection
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    private final List<CarbonAPI.FSEventStreamCallback> callbackList = new ArrayList<CarbonAPI.FSEventStreamCallback>();
    private final List<CFRunLoopThread> threadList = new ArrayList<CFRunLoopThread>();

    @Override
    WatchKey register(WatchableFile watchableFile, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifers) throws IOException {
        final File file = watchableFile.getFile();
        final Map<File, Long> lastModifiedMap = createLastModifiedMap(file, includeDirInRecursion);
        
        if (debug) {
            System.out.println("MacOSXListeningWatchService.register: " + file.getAbsolutePath() + ", " + lastModifiedMap.size() + " files registered");
        }
        
        final String s = file.getAbsolutePath();
        final Pointer[] values = {CFStringRef.toCFString(s).getPointer()};
        final CFArrayRef pathsToWatch = CarbonAPI.INSTANCE.CFArrayCreate(null, values, CFIndex.valueOf(1), null);
        final MacOSXWatchKey watchKey = new MacOSXWatchKey(this, events);

        final double latency = 1.0; /* Latency in seconds */

        final long kFSEventStreamEventIdSinceNow = -1; //  this is 0xFFFFFFFFFFFFFFFF
        final int kFSEventStreamCreateFlagNoDefer = 0x00000002;
        final CarbonAPI.FSEventStreamCallback callback = new MacOSXListeningCallback(watchKey, lastModifiedMap, includeDirInRecursion, invokeOnDirChange, debug);
        callbackList.add(callback);
        final FSEventStreamRef stream = CarbonAPI.INSTANCE.FSEventStreamCreate(
                Pointer.NULL,
                callback,
                Pointer.NULL,
                pathsToWatch,
                kFSEventStreamEventIdSinceNow,
                latency,
                kFSEventStreamCreateFlagNoDefer);

        final CFRunLoopThread thread = new CFRunLoopThread(stream, file);
        thread.setDaemon(true);
        thread.start();
        threadList.add(thread);
        return watchKey;
    }

    public static class CFRunLoopThread extends Thread {

        private final FSEventStreamRef streamRef;
        private CFRunLoopRef runLoop;

        public CFRunLoopThread(FSEventStreamRef streamRef, File file) {
            super("WatchService for " + file);
            this.streamRef = streamRef;
        }

        @Override
        public void run() {
            runLoop = CarbonAPI.INSTANCE.CFRunLoopGetCurrent();
            final CFStringRef runLoopMode = CFStringRef.toCFString("kCFRunLoopDefaultMode");
            CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(streamRef, runLoop, runLoopMode);
            CarbonAPI.INSTANCE.FSEventStreamStart(streamRef);
            CarbonAPI.INSTANCE.CFRunLoopRun();
        }

        public CFRunLoopRef getRunLoop() {
            return runLoop;
        }

        public FSEventStreamRef getStreamRef() {
            return streamRef;
        }
    }

    private Map<File, Long> createLastModifiedMap(File file, Function<File, Boolean> includeDirInRecursion) {
        Map<File, Long> lastModifiedMap = new ConcurrentHashMap<File, Long>();
        for (File child : recursiveListFiles(file, includeDirInRecursion)) {
            lastModifiedMap.put(child, child.lastModified());
        }
        return lastModifiedMap;
    }

    private static Set<File> recursiveListFiles(File file, Function<File, Boolean> includeDirInRecursion) {
        Set<File> files = new HashSet<File>();

        if (includeDirInRecursion != null && !includeDirInRecursion.apply(file)) return files;

        files.add(file);
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                files.addAll(recursiveListFiles(child, includeDirInRecursion));
            }
        }
        return files;
    }

    @Override
    void implClose() throws IOException {
        for (CFRunLoopThread thread : threadList) {
            CarbonAPI.INSTANCE.CFRunLoopStop(thread.getRunLoop());
            CarbonAPI.INSTANCE.FSEventStreamStop(thread.getStreamRef());
        }
        threadList.clear();
        callbackList.clear();
    }


    private static class MacOSXListeningCallback implements CarbonAPI.FSEventStreamCallback {
        private final MacOSXWatchKey watchKey;
        private final Map<File, Long> lastModifiedMap;

        private Function<File, Boolean> includeDirInRecursion = null;
        private Function<File, Boolean> invokeOnDirChange = null;
        private boolean debug = false;
        private DecimalFormat debugTimeFormat = new DecimalFormat("0.##");

        private MacOSXListeningCallback(
                MacOSXWatchKey watchKey, 
                Map<File, Long> lastModifiedMap,

                Function<File, Boolean> includeDirInRecursion,
                Function<File, Boolean> invokeOnDirChange,
                boolean debug
        ) {
            this.watchKey = watchKey;
            this.lastModifiedMap = lastModifiedMap;

            this.includeDirInRecursion = includeDirInRecursion;
            this.invokeOnDirChange = invokeOnDirChange;
            this.debug = debug;
        }

        public void invoke(FSEventStreamRef streamRef, Pointer clientCallBackInfo, NativeLong numEvents, Pointer eventPaths, Pointer /* array of unsigned int */ eventFlags, /* array of unsigned long */ Pointer eventIds) {
            final int length = numEvents.intValue();

            for (String folderName : eventPaths.getStringArray(0, length)) {

                File folder = new File(folderName);
                
                if (invokeOnDirChange != null && !invokeOnDirChange.apply(folder)) {
                    if (debug) {
                        System.out.println("MacOSXListeningCallback.invoke: ignoring: " + folderName);
                    }
                    continue;
                }

                long startTime = 0;
                if (debug) {
                    startTime = System.nanoTime();
                    System.out.println("MacOSXListeningCallback.invoke: start running recursiveListFiles for: " + folderName);
                }
                
                final Set<File> filesOnDisk = recursiveListFiles(folder, includeDirInRecursion);

                if (debug) {
                    double duration = (System.nanoTime() - startTime)/1000000.0;
                   
                    System.out.println(
                        "MacOSXListeningCallback.invoke: result for recursiveListFiles: " + folderName + 
                        ", filesOnDisk: " + filesOnDisk.size() + 
                        " (" + debugTimeFormat.format(duration)+ "ms) "
                    );
                }

                final List<File> createdFiles = findCreatedFiles(filesOnDisk);
                final List<File> modifiedFiles = findModifiedFiles(filesOnDisk);
                final List<File> deletedFiles = findDeletedFiles(folderName, filesOnDisk);

                for (File file : createdFiles) {
                    if (watchKey.isReportCreateEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_CREATE, file);
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for (File file : modifiedFiles) {
                    if (watchKey.isReportModifyEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_MODIFY, file);
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for (File file : deletedFiles) {
                    if (watchKey.isReportDeleteEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_DELETE, file);
                    }
                    lastModifiedMap.remove(file);
                }
            }
        }

        private List<File> findModifiedFiles(Set<File> filesOnDisk) {
            List<File> modifiedFileList = new ArrayList<File>();
            for (File file : filesOnDisk) {
                final Long lastModified = lastModifiedMap.get(file);
                if (lastModified != null && lastModified != file.lastModified()) {
                    modifiedFileList.add(file);
                }
            }
            return modifiedFileList;
        }

        private List<File> findCreatedFiles(Set<File> filesOnDisk) {
            List<File> createdFileList = new ArrayList<File>();
            for (File file : filesOnDisk) {
                if (!lastModifiedMap.containsKey(file)) {
                    createdFileList.add(file);
                }
            }
            return createdFileList;
        }

        private List<File> findDeletedFiles(String folderName, Set<File> filesOnDisk) {
            List<File> deletedFileList = new ArrayList<File>();
            for (File file : lastModifiedMap.keySet()) {
                if (file.getAbsolutePath().startsWith(folderName) && !filesOnDisk.contains(file)) {
                    deletedFileList.add(file);
                }
            }
            return deletedFileList;
        }
    }
}
