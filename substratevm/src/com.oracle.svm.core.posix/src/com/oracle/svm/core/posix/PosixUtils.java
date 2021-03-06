/*
 * Copyright (c) 2014, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.posix;

import static com.oracle.svm.core.headers.Errno.errno;
import static com.oracle.svm.core.posix.headers.Fcntl.O_WRONLY;
import static com.oracle.svm.core.posix.headers.Fcntl.open;
import static com.oracle.svm.core.posix.headers.Unistd.close;
import static com.oracle.svm.core.posix.headers.Unistd.dup2;
import static com.oracle.svm.core.posix.headers.Unistd.read;
import static com.oracle.svm.core.posix.headers.Unistd.write;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.ArrayList;
import java.util.List;

import com.oracle.svm.core.posix.headers.Dlfcn;
import com.oracle.svm.core.headers.Errno;
import com.oracle.svm.core.posix.headers.Fcntl;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Locale;
import com.oracle.svm.core.posix.headers.Unistd;
import com.oracle.svm.core.posix.headers.Wait;
import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.nativeimage.impl.InternalPlatform;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.jdk.JDK9OrLater;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.VMError;

@Platforms({InternalPlatform.LINUX_AND_JNI.class, InternalPlatform.DARWIN_AND_JNI.class})
public class PosixUtils {

    static String setLocale(String category, String locale) {
        int intCategory = getCategory(category);

        return setLocale(intCategory, locale);
    }

    public static String setLocale(int category, String locale) {
        if (locale == null) {
            CCharPointer cstrResult = Locale.setlocale(category, WordFactory.nullPointer());
            return CTypeConversion.toJavaString(cstrResult);
        }
        try (CCharPointerHolder localePin = CTypeConversion.toCString(locale)) {
            CCharPointer cstrLocale = localePin.get();
            CCharPointer cstrResult = Locale.setlocale(category, cstrLocale);
            return CTypeConversion.toJavaString(cstrResult);
        }
    }

    private static int getCategory(String category) {
        switch (category) {
            case "LC_ALL":
                return Locale.LC_ALL();
            case "LC_COLLATE":
                return Locale.LC_COLLATE();
            case "LC_CTYPE":
                return Locale.LC_CTYPE();
            case "LC_MONETARY":
                return Locale.LC_MONETARY();
            case "LC_NUMERIC":
                return Locale.LC_NUMERIC();
            case "LC_TIME":
                return Locale.LC_TIME();
            case "LC_MESSAGES":
                return Locale.LC_MESSAGES();
        }
        if (Platform.includedIn(Platform.LINUX.class)) {
            switch (category) {
                case "LC_PAPER":
                    return Locale.LC_PAPER();
                case "LC_NAME":
                    return Locale.LC_NAME();
                case "LC_ADDRESS":
                    return Locale.LC_ADDRESS();
                case "LC_TELEPHONE":
                    return Locale.LC_TELEPHONE();
                case "LC_MEASUREMENT":
                    return Locale.LC_MEASUREMENT();
                case "LC_IDENTIFICATION":
                    return Locale.LC_IDENTIFICATION();
            }
        }
        throw VMError.shouldNotReachHere("Unknown locale category: " + category);
    }

    static String removeTrailingSlashes(String path) {
        int p = path.length() - 1;
        while (p > 0 && path.charAt(p) == '/') {
            --p;
        }
        return p > 0 ? path.substring(0, p + 1) : path;
    }

    @TargetClass(java.io.FileDescriptor.class)
    private static final class Target_java_io_FileDescriptor {

        @Alias int fd;

        /* jdk/src/solaris/native/java/io/FileDescriptor_md.c */
        // 53 JNIEXPORT void JNICALL
        // 54 Java_java_io_FileDescriptor_sync(JNIEnv *env, jobject this) {
        @Substitute
        public /* native */ void sync() throws SyncFailedException {
            // 55 FD fd = THIS_FD(this);
            // 56 if (IO_Sync(fd) == -1) {
            if (Unistd.fsync(fd) == -1) {
                // 57 JNU_ThrowByName(env, "java/io/SyncFailedException", "sync failed");
                throw new SyncFailedException("sync failed");
            }
        }

        @Substitute //
        @TargetElement(onlyWith = JDK9OrLater.class) //
        @SuppressWarnings({"unused"})
        /* { Do not re-format commented out C code.  @formatter:off */
        /* open-jdk11/src/java.base/unix/native/libjava/FileDescriptor_md.c */
        // 72  JNIEXPORT jboolean JNICALL
        // 73  Java_java_io_FileDescriptor_getAppend(JNIEnv *env, jclass fdClass, jint fd) {
        private static /* native */ boolean getAppend(int fd) {
            // 74      int flags = fcntl(fd, F_GETFL);
            int flags = Fcntl.fcntl(fd, Fcntl.F_GETFL());
            // 75      return ((flags & O_APPEND) == 0) ? JNI_FALSE : JNI_TRUE;
            return ((flags & Fcntl.O_APPEND()) == 0) ? false : true;
        }
        /* } Do not re-format commented out C code. @formatter:on */

        @Substitute //
        @TargetElement(onlyWith = JDK9OrLater.class) //
        @SuppressWarnings({"unused", "static-method"})
        /* { Do not re-format commented out C code.  @formatter:off */
        /* open-jdk11/src/java.base/unix/native/libjava/FileDescriptor_md.c */
        // 78  // instance method close0 for FileDescriptor
        // 79  JNIEXPORT void JNICALL
        // 80  Java_java_io_FileDescriptor_close0(JNIEnv *env, jobject this) {
        private /* native */ void close0() throws IOException {
            // 81      fileDescriptorClose(env, this);
            PosixUtils.fileClose(Util_java_io_FileDescriptor.fromTarget(this));
        }
    }

    static final class Util_java_io_FileDescriptor {

        /** Cast from Target class to Java class. */
        @SuppressFBWarnings(value = "BC", justification = "Cast from @TargetClass")
        static java.io.FileDescriptor fromTarget(Target_java_io_FileDescriptor tjifd) {
            return java.io.FileDescriptor.class.cast(tjifd);
        }

        /** Cast from Java class to Target class. */
        @SuppressFBWarnings(value = "BC", justification = "Cast to @TargetClass")
        static Target_java_io_FileDescriptor toTarget(java.io.FileDescriptor jifd) {
            return Target_java_io_FileDescriptor.class.cast(jifd);
        }

    }

    public static int getFD(FileDescriptor descriptor) {
        return Util_java_io_FileDescriptor.toTarget(descriptor).fd;
    }

    static void setFD(FileDescriptor descriptor, int fd) {
        Util_java_io_FileDescriptor.toTarget(descriptor).fd = fd;
    }

    /** Return the error string for the last error, or a default message. */
    public static String lastErrorString(String defaultMsg) {
        int errno = Errno.errno();
        return errorString(errno, defaultMsg);
    }

    public static IOException newIOExceptionWithLastError(String defaultMsg) {
        return new IOException(PosixUtils.lastErrorString(defaultMsg));
    }

    /** Return the error string for the given error number, or a default message. */
    public static String errorString(int errno, String defaultMsg) {
        String result = "";
        if (errno != 0) {
            result = CTypeConversion.toJavaString(Errno.strerror(errno));
        }
        return result.length() != 0 ? result : defaultMsg;
    }

    static void fileOpen(String path, FileDescriptor fd, int flags) throws FileNotFoundException {
        try (CCharPointerHolder pathPin = CTypeConversion.toCString(removeTrailingSlashes(path))) {
            CCharPointer pathPtr = pathPin.get();
            int handle = open(pathPtr, flags, 0666);
            if (handle >= 0) {
                setFD(fd, handle);
            } else {
                throw new FileNotFoundException(path);
            }
        }
    }

    static void fileClose(FileDescriptor fd) throws IOException {
        int handle = getFD(fd);
        if (handle == -1) {
            return;
        }
        setFD(fd, -1);

        // Do not close file descriptors 0, 1, 2. Instead, redirect to /dev/null.
        if (handle >= 0 && handle <= 2) {
            int devnull;

            try (CCharPointerHolder pathPin = CTypeConversion.toCString("/dev/null")) {
                CCharPointer pathPtr = pathPin.get();
                devnull = open(pathPtr, O_WRONLY(), 0);
            }
            if (devnull < 0) {
                setFD(fd, handle);
                throw PosixUtils.newIOExceptionWithLastError("open /dev/null failed");
            } else {
                dup2(devnull, handle);
                close(devnull);
            }
        } else if (close(handle) == -1) {
            throw PosixUtils.newIOExceptionWithLastError("close failed");
        }
    }

    public static int getpid() {
        return Unistd.getpid();
    }

    public static int getpid(Process process) {
        Target_java_lang_UNIXProcess instance = KnownIntrinsics.unsafeCast(process, Target_java_lang_UNIXProcess.class);
        return instance.pid;
    }

    public static int waitForProcessExit(int ppid) {
        CIntPointer statusptr = StackValue.get(CIntPointer.class);
        while (Wait.waitpid(ppid, statusptr, 0) < 0) {
            if (Errno.errno() == Errno.ECHILD()) {
                return 0;
            } else if (Errno.errno() == Errno.EINTR()) {
                break;
            } else {
                return -1;
            }
        }

        int status = statusptr.read();
        if (Wait.WIFEXITED(status)) {
            return Wait.WEXITSTATUS(status);
        } else if (Wait.WIFSIGNALED(status)) {
            // Exited because of signal: return 0x80 + signal number like shells do
            return 0x80 + Wait.WTERMSIG(status);
        }
        return status;
    }

    static int readSingle(FileDescriptor fd) throws IOException {
        CCharPointer retPtr = StackValue.get(CCharPointer.class);
        int handle = PosixUtils.getFDHandle(fd);
        SignedWord nread = read(handle, retPtr, WordFactory.unsigned(1));
        if (nread.equal(0)) {
            // EOF
            return -1;
        } else if (nread.equal(-1)) {
            throw PosixUtils.newIOExceptionWithLastError("Read error");
        }
        return retPtr.read() & 0xFF;
    }

    static int readBytes(byte[] b, int off, int len, FileDescriptor fd) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (PosixUtils.outOfBounds(off, len, b)) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        SignedWord nread;
        CCharPointer buf = LibC.malloc(WordFactory.unsigned(len));
        try {
            if (buf.equal(WordFactory.zero())) {
                throw new OutOfMemoryError();
            }

            int handle = getFDHandle(fd);
            nread = read(handle, buf, WordFactory.unsigned(len));
            if (nread.greaterThan(0)) {
                /*
                 * We do not read directly into the (pinned) result array because read can block,
                 * and that could lead to object pinned for an unexpectedly long time.
                 */
                try (PinnedObject pin = PinnedObject.create(b)) {
                    LibC.memcpy(pin.addressOfArrayElement(off), buf, (UnsignedWord) nread);
                }
            } else if (nread.equal(-1)) {
                throw PosixUtils.newIOExceptionWithLastError("Read error");
            } else {
                // EOF
                nread = WordFactory.signed(-1);
            }
        } finally {
            LibC.free(buf);
        }

        return (int) nread.rawValue();
    }

    @SuppressWarnings("unused")
    static void writeSingle(FileDescriptor fd, int b, boolean append) throws IOException {
        SignedWord n;
        int handle = getFD(fd);
        if (handle == -1) {
            throw new IOException("Stream Closed");
        }

        CCharPointer bufPtr = StackValue.get(CCharPointer.class);
        bufPtr.write((byte) b);
        // the append parameter is disregarded
        n = write(handle, bufPtr, WordFactory.unsigned(1));

        if (n.equal(-1)) {
            throw PosixUtils.newIOExceptionWithLastError("Write error");
        }
    }

    @SuppressWarnings("unused")
    static void writeBytes(FileDescriptor descriptor, byte[] bytes, int off, int len, boolean append) throws IOException {
        if (bytes == null) {
            throw new NullPointerException();
        } else if (PosixUtils.outOfBounds(off, len, bytes)) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }

        try (PinnedObject bytesPin = PinnedObject.create(bytes)) {
            CCharPointer curBuf = bytesPin.addressOfArrayElement(off);
            UnsignedWord curLen = WordFactory.unsigned(len);
            while (curLen.notEqual(0)) {
                int fd = getFD(descriptor);
                if (fd == -1) {
                    throw new IOException("Stream Closed");
                }

                SignedWord n = write(fd, curBuf, curLen);

                if (n.equal(-1)) {
                    throw PosixUtils.newIOExceptionWithLastError("Write error");
                }
                curBuf = curBuf.addressOf(n);
                curLen = curLen.subtract((UnsignedWord) n);
            }
        }
    }

    /**
     * Low-level output of bytes already in native memory. This method is allocation free, so that
     * it can be used, e.g., in low-level logging routines.
     */
    public static boolean writeBytes(FileDescriptor descriptor, CCharPointer bytes, UnsignedWord length) {
        CCharPointer curBuf = bytes;
        UnsignedWord curLen = length;
        while (curLen.notEqual(0)) {
            int fd = getFD(descriptor);
            if (fd == -1) {
                return false;
            }

            SignedWord n = Unistd.write(fd, curBuf, curLen);

            if (n.equal(-1)) {
                return false;
            }
            curBuf = curBuf.addressOf(n);
            curLen = curLen.subtract((UnsignedWord) n);
        }
        return true;
    }

    static boolean flush(FileDescriptor descriptor) {
        int fd = getFD(descriptor);
        return Unistd.fsync(fd) == 0;
    }

    static int getFDHandle(FileDescriptor fd) throws IOException {
        int handle = getFD(fd);
        if (handle == -1) {
            throw new IOException("Stream Closed");
        }
        return handle;
    }

    static boolean outOfBounds(int off, int len, byte[] array) {
        return off < 0 || len < 0 || array.length - off < len;
    }

    /**
     * From a given path, remove all {@code .} and {@code dir/..}.
     */
    static String collapse(String path) {
        boolean absolute = path.charAt(0) == '/';
        String wpath = absolute ? path.substring(1) : path;

        // split the path and remove unnecessary elements
        List<String> parts = new ArrayList<>();
        int pos = 0;
        int next;
        do {
            next = wpath.indexOf('/', pos);
            String part = next != -1 ? wpath.substring(pos, next) : wpath.substring(pos);
            if (part.length() > 0) {
                if (part.equals(".")) {
                    // ignore
                } else if (part.equals("..")) {
                    // omit this .. and the preceding part
                    parts.remove(parts.size() - 1);
                } else {
                    parts.add(part);
                }
            }
            pos = next + 1;
        } while (next != -1);

        // reassemble the path
        StringBuilder rpath = new StringBuilder(absolute ? "/" : "");
        for (String part : parts) {
            rpath.append(part).append('/');
        }
        rpath.deleteCharAt(rpath.length() - 1);

        return rpath.toString();
    }

    public static PointerBase dlopen(String file, int mode) {
        try (CCharPointerHolder pathPin = CTypeConversion.toCString(file)) {
            CCharPointer pathPtr = pathPin.get();
            return Dlfcn.dlopen(pathPtr, mode);
        }
    }

    public static <T extends PointerBase> T dlsym(PointerBase handle, String name) {
        try (CCharPointerHolder namePin = CTypeConversion.toCString(name)) {
            CCharPointer namePtr = namePin.get();
            return Dlfcn.dlsym(handle, namePtr);
        }
    }

    public static String dlerror() {
        return CTypeConversion.toJavaString(Dlfcn.dlerror());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    public static void checkStatusIs0(int status, String message) {
        VMError.guarantee(status == 0, message);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    public static boolean readEntirely(int fd, CCharPointer buffer, int bufferLen) {
        int bufferOffset = 0;
        for (;;) {
            int readBytes = readBytes(fd, buffer, bufferLen - 1, bufferOffset);
            if (readBytes < 0) { // NOTE: also when file does not fit in buffer
                return false;
            }
            bufferOffset += readBytes;
            if (readBytes == 0) { // EOF, terminate string
                buffer.write(bufferOffset, (byte) 0);
                return true;
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    public static int readBytes(int fd, CCharPointer buffer, int bufferLen, int readOffset) {
        int readBytes = -1;
        if (readOffset < bufferLen) {
            do {
                readBytes = (int) Unistd.NoTransitions.read(fd, buffer.addressOf(readOffset), WordFactory.unsigned(bufferLen - readOffset)).rawValue();
            } while (readBytes == -1 && errno() == Errno.EINTR());
        }
        return readBytes;
    }
}
