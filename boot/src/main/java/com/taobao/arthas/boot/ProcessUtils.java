package com.taobao.arthas.boot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.InputMismatchException;

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.ExecutingCommand;
import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.common.PidUtils;

/**
 *
 * @author hengyunabc 2018-11-06
 *
 */
public class ProcessUtils {
    private static String FOUND_JAVA_HOME = null;

    @SuppressWarnings("resource")
    public static int select(boolean v, int telnetPortPid) throws InputMismatchException {
        Map<Integer, String> processMap = listProcessByJps(v);
        // Put the port that is already listening at the first
        if (telnetPortPid > 0 && processMap.containsKey(telnetPortPid)) {
            String telnetPortProcess = processMap.get(telnetPortPid);
            processMap.remove(telnetPortPid);
            Map<Integer, String> newProcessMap = new LinkedHashMap<Integer, String>();
            newProcessMap.put(telnetPortPid, telnetPortProcess);
            newProcessMap.putAll(processMap);
            processMap = newProcessMap;
        }

        if (processMap.isEmpty()) {
            AnsiLog.info("Can not find java process. Try to pass <pid> in command line.");
            return -1;
        }

        AnsiLog.info("Found existing java process, please choose one and hit RETURN.");
        // print list
        int count = 1;
        for (String process : processMap.values()) {
            if (count == 1) {
                System.out.println("* [" + count + "]: " + process);
            } else {
                System.out.println("  [" + count + "]: " + process);
            }
            count++;
        }

        // read choice
        String line = new Scanner(System.in).nextLine();
        if (line.trim().isEmpty()) {
            // get the first process id
            return processMap.keySet().iterator().next();
        }

        int choice = new Scanner(line).nextInt();

        if (choice <= 0 || choice > processMap.size()) {
            return -1;
        }

        Iterator<Integer> idIter = processMap.keySet().iterator();
        for (int i = 1; i <= choice; ++i) {
            if (i == choice) {
                return idIter.next();
            }
            idIter.next();
        }

        return -1;
    }

    private static Map<Integer, String> listProcessByJps(boolean v) {
        Map<Integer, String> result = new LinkedHashMap<Integer, String>();

        String jps = "jps";
        File jpsFile = findJps();
        if (jpsFile != null) {
            jps = jpsFile.getAbsolutePath();
        }

        AnsiLog.debug("Try use jps to lis java process, jps: " + jps);

        String[] command = null;
        if (v) {
            command = new String[] { jps, "-v", "-l" };
        } else {
            command = new String[] { jps, "-l" };
        }

        List<String> lines = ExecutingCommand.runNative(command);

        int currentPid = Integer.parseInt(PidUtils.currentPid());
        for (String line : lines) {
            String[] strings = line.trim().split("\\s+");
            if (strings.length < 1) {
                continue;
            }
            int pid = Integer.parseInt(strings[0]);
            if (pid == currentPid) {
                continue;
            }
            if (strings.length >= 2 && isJpsProcess(strings[1])) { // skip jps
                continue;
            }

            result.put(pid, line);
        }

        return result;
    }

    /**
     * <pre>
     * 1. Try to find java home from System Property java.home
     * 2. If jdk > 8, FOUND_JAVA_HOME set to java.home
     * 3. If jdk <= 8, try to find tools.jar under java.home
     * 4. If tools.jar do not exists under java.home, try to find System env JAVA_HOME
     * 5. If jdk <= 8 and tools.jar do not exists under JAVA_HOME, throw IllegalArgumentException
     * </pre>
     *
     * @return
     */
    public static String findJavaHome() {
        if (FOUND_JAVA_HOME != null) {
            return FOUND_JAVA_HOME;
        }

        String javaHome = System.getProperty("java.home");

        if (JavaVersionUtils.isLessThanJava9()) {
            File toolsJar = new File(javaHome, "lib/tools.jar");
            if (!toolsJar.exists()) {
                toolsJar = new File(javaHome, "../lib/tools.jar");
            }
            if (!toolsJar.exists()) {
                // maybe jre
                toolsJar = new File(javaHome, "../../lib/tools.jar");
            }

            if (toolsJar.exists()) {
                FOUND_JAVA_HOME = javaHome;
                return FOUND_JAVA_HOME;
            }

            if (!toolsJar.exists()) {
                AnsiLog.debug("Can not find tools.jar under java.home: " + javaHome);
                String javaHomeEnv = System.getenv("JAVA_HOME");
                if (javaHomeEnv != null && !javaHomeEnv.isEmpty()) {
                    AnsiLog.debug("Try to find tools.jar in System Env JAVA_HOME: " + javaHomeEnv);
                    // $JAVA_HOME/lib/tools.jar
                    toolsJar = new File(javaHomeEnv, "lib/tools.jar");
                    if (!toolsJar.exists()) {
                        // maybe jre
                        toolsJar = new File(javaHomeEnv, "../lib/tools.jar");
                    }
                }

                if (toolsJar.exists()) {
                    AnsiLog.info("Found java home from System Env JAVA_HOME: " + javaHomeEnv);
                    FOUND_JAVA_HOME = javaHomeEnv;
                    return FOUND_JAVA_HOME;
                }

                throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome
                                + ", please try to start arthas-boot with full path java. Such as /opt/jdk/bin/java -jar arthas-boot.jar");
            }
        } else {
            FOUND_JAVA_HOME = javaHome;
        }
        return FOUND_JAVA_HOME;
    }

    public static void startArthasCore(int targetPid, List<String> attachArgs) {
        // find java/java.exe, then try to find tools.jar
        String javaHome = findJavaHome();

        // find java/java.exe
        File javaPath = findJava();
        if (javaPath == null) {
            throw new IllegalArgumentException(
                            "Can not find java/java.exe executable file under java home: " + javaHome);
        }

        File toolsJar = findToolsJar();

        if (JavaVersionUtils.isLessThanJava9()) {
            if (toolsJar == null || !toolsJar.exists()) {
                throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome);
            }
        }

        List<String> command = new ArrayList<String>();
        command.add(javaPath.getAbsolutePath());

        if (toolsJar != null && toolsJar.exists()) {
            command.add("-Xbootclasspath/a:" + toolsJar.getAbsolutePath());
        }

        command.addAll(attachArgs);
        // "${JAVA_HOME}"/bin/java \
        // ${opts} \
        // -jar "${arthas_lib_dir}/arthas-core.jar" \
        // -pid ${TARGET_PID} \
        // -target-ip ${TARGET_IP} \
        // -telnet-port ${TELNET_PORT} \
        // -http-port ${HTTP_PORT} \
        // -core "${arthas_lib_dir}/arthas-core.jar" \
        // -agent "${arthas_lib_dir}/arthas-agent.jar"


        // ProcessBuilder为进程提供了更多的控制，例如，可以设置当前工作目录，还可以改变环境参数。而Process的功能相对来说简单的多。
        //ProcessBuilder是一个final类，有两个带参数的构造方法，你可以通过构造方法来直接创建ProcessBuilder的对象。
        // 而Process是一个抽象类，一般都通过Runtime.exec()和ProcessBuilder.start()来间接创建其实例。
        // （有关Process类的详细介绍可以看下一节。）
        // 修改进程构造器的属性将影响后续由该对象的 start() 方法启动的进程，但从不会影响以前启动的进程或 Java 自身的进程。
        // ProcessBuilder类不是同步的。如果多个线程同时访问一个 ProcessBuilder，而其中至少一个线程从结构上修改了其中一个属性，它必须 保持外部同步。
        // Process 类提供了执行从进程输入，执行输出到进程，等待进程的退出状态以及销毁（杀掉）进程的方法
        // 创建进程的方法可能无法针对某些本机平台上的特定进程很好地工作，比如，本机窗口进程，守护进程， Microsoft Windows上的 Win16/DOS
        // 进程，或者 shell脚本，创建的子进程没有自己的终端或控制台，它的所有标准 io(即 stdin,stdout和 stderr) 操作都将通过三个流
        // (getOutPutStream,getInputStream()和 getErrorStream()) 重定向到父进程，父进程使用这些流来提供到子进程的输入和获得从子进程
        // 的输出，因为有些本机平台仅针对标准输入和输出流提供有限的的缓冲区大小，如果读写子进程的输出流迅速出现失败，则可能导致子进程阻塞，
        // 甚至产生死锁，当没有 Process 对象的更多引用时，不是删掉子进程，而继续异步执行子进程，对于带有 process 对象的 java进程，没有必要异步或
        // 并发执行由 process 对象表示的进程
        ProcessBuilder pb = new ProcessBuilder(command);            //command(List<String> command)  返回此进程生成器的操作系统程序和参数。

        try {
            // 使用命令名和命令的参数选项构造ProcessBuilder对象，它的start方法执行命令，启动一个进程，返回一个Process对象。
            final Process proc = pb.start();
            Thread redirectStdout = new Thread(new Runnable() {
                @Override
                public void run() {
                    // 获得子进程的输入流。
                    InputStream inputStream = proc.getInputStream();
                    try {
                        IOUtils.copy(inputStream, System.out);
                    } catch (IOException e) {
                        IOUtils.close(inputStream);
                    }

                }
            });

            Thread redirectStderr = new Thread(new Runnable() {
                @Override
                public void run() {
                    // InputStream getErrorStream() 获得子进程的错误流。
                    InputStream inputStream = proc.getErrorStream();
                    try {
                        IOUtils.copy(inputStream, System.err);
                    } catch (IOException e) {
                        IOUtils.close(inputStream);
                    }


                }
            });
            // start()   使用此进程生成器的属性启动一个新进程。
            redirectStdout.start();
            redirectStderr.start();
            redirectStdout.join();
            redirectStderr.join();
            // exitValue()
            int exitValue = proc.exitValue();
            if (exitValue != 0) {
                AnsiLog.error("attach fail, targetPid: " + targetPid);
                System.exit(1);
            }
        } catch (Throwable e) {
            // ignore
        }

    }

    private static File findJava() {
        String javaHome = findJavaHome();
        String[] paths = { "bin/java", "bin/java.exe", "../bin/java", "../bin/java.exe" };

        List<File> javaList = new ArrayList<File>();
        for (String path : paths) {
            File javaFile = new File(javaHome, path);
            if (javaFile.exists()) {
                AnsiLog.debug("Found java: " + javaFile.getAbsolutePath());
                javaList.add(javaFile);
            }
        }

        if (javaList.isEmpty()) {
            AnsiLog.debug("Can not find java/java.exe under current java home: " + javaHome);
            return null;
        }

        // find the shortest path, jre path longer than jdk path
        if (javaList.size() > 1) {
            Collections.sort(javaList, new Comparator<File>() {
                @Override
                public int compare(File file1, File file2) {
                    try {
                        return file1.getCanonicalPath().length() - file2.getCanonicalPath().length();
                    } catch (IOException e) {
                        // ignore
                    }
                    return -1;
                }
            });
        }
        return javaList.get(0);
    }

    private static File findToolsJar() {
        if (JavaVersionUtils.isGreaterThanJava8()) {
            return null;
        }

        String javaHome = findJavaHome();
        File toolsJar = new File(javaHome, "lib/tools.jar");
        if (!toolsJar.exists()) {
            toolsJar = new File(javaHome, "../lib/tools.jar");
        }
        if (!toolsJar.exists()) {
            // maybe jre
            toolsJar = new File(javaHome, "../../lib/tools.jar");
        }

        if (!toolsJar.exists()) {
            throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome);
        }

        AnsiLog.debug("Found tools.jar: " + toolsJar.getAbsolutePath());
        return toolsJar;
    }

    private static File findJps() {
        // Try to find jps under java.home and System env JAVA_HOME
        String javaHome = System.getProperty("java.home");
        String[] paths = { "bin/jps", "bin/jps.exe", "../bin/jps", "../bin/jps.exe" };

        List<File> jpsList = new ArrayList<File>();
        for (String path : paths) {
            File jpsFile = new File(javaHome, path);
            if (jpsFile.exists()) {
                AnsiLog.debug("Found jps: " + jpsFile.getAbsolutePath());
                jpsList.add(jpsFile);
            }
        }

        if (jpsList.isEmpty()) {
            AnsiLog.debug("Can not find jps under :" + javaHome);
            String javaHomeEnv = System.getenv("JAVA_HOME");
            AnsiLog.debug("Try to find jps under env JAVA_HOME :" + javaHomeEnv);
            for (String path : paths) {
                File jpsFile = new File(javaHomeEnv, path);
                if (jpsFile.exists()) {
                    AnsiLog.debug("Found jps: " + jpsFile.getAbsolutePath());
                    jpsList.add(jpsFile);
                }
            }
        }

        if (jpsList.isEmpty()) {
            AnsiLog.debug("Can not find jps under current java home: " + javaHome);
            return null;
        }

        // find the shortest path, jre path longer than jdk path
        if (jpsList.size() > 1) {
            Collections.sort(jpsList, new Comparator<File>() {
                @Override
                public int compare(File file1, File file2) {
                    try {
                        return file1.getCanonicalPath().length() - file2.getCanonicalPath().length();
                    } catch (IOException e) {
                        // ignore
                    }
                    return -1;
                }
            });
        }
        return jpsList.get(0);
    }

    private static boolean isJpsProcess(String mainClassName) {
        return "sun.tools.jps.Jps".equals(mainClassName) || "jdk.jcmd/sun.tools.jps.Jps".equals(mainClassName);
    }
}
