package com.taobao.arthas.agent;

import java.arthas.Spy;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.util.jar.JarFile;

/**
 * 代理启动类
 *
 * @author vlinux on 15/5/19.
 *
 *
 * 想要理解虚拟机如何加载agent ，那么就需要找到agent的启动方法，并理解agent是如何对运行的class进行修改，找到，com.taobao.arthas.agetn.AgetnBootstarap
 * 这个类，它是agent的启动类，
 *
 *
 *
 */
public class AgentBootstrap {

    private static final String ADVICEWEAVER = "com.taobao.arthas.core.advisor.AdviceWeaver";
    private static final String ON_BEFORE = "methodOnBegin";
    private static final String ON_RETURN = "methodOnReturnEnd";
    private static final String ON_THROWS = "methodOnThrowingEnd";
    private static final String BEFORE_INVOKE = "methodOnInvokeBeforeTracing";
    private static final String AFTER_INVOKE = "methodOnInvokeAfterTracing";
    private static final String THROW_INVOKE = "methodOnInvokeThrowTracing";
    private static final String RESET = "resetArthasClassLoader";
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    private static final String ARTHAS_CONFIGURE = "com.taobao.arthas.core.config.Configure";
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String TO_CONFIGURE = "toConfigure";
    private static final String GET_JAVA_PID = "getJavaPid";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";
    private static final String BIND = "bind";

    private static PrintStream ps = System.err;
    static {
        try {
            File arthasLogDir = new File(System.getProperty("user.home") + File.separator + "logs" + File.separator
                    + "arthas" + File.separator);
            if (!arthasLogDir.exists()) {
                arthasLogDir.mkdirs();
            }
            if (!arthasLogDir.exists()) {
                // #572
                arthasLogDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "logs" + File.separator
                        + "arthas" + File.separator);
                if (!arthasLogDir.exists()) {
                    arthasLogDir.mkdirs();
                }
            }

            File log = new File(arthasLogDir, "arthas.log");

            if (!log.exists()) {
                log.createNewFile();
            }
            ps = new PrintStream(new FileOutputStream(log, true));
        } catch (Throwable t) {
            t.printStackTrace(ps);
        }
    }

    // 全局持有classloader用于隔离 Arthas 实现
    private static volatile ClassLoader arthasClassLoader;

    // premain 和agentmain两个方法，这两个方法有些不同，premain方法用于运行前的agent加载，agentmain用于运行的加载，在artchas
    // 在artchas中使用的是agentmain方法，那么那么jar是怎么封装成agent进行使用呢，
    public static void premain(String args, Instrumentation inst) {
        main(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        main(args, inst);
    }

    /**
     * 让下次再次启动时有机会重新加载
     */
    public synchronized static void resetArthasClassLoader() {
        arthasClassLoader = null;
    }

    private static ClassLoader getClassLoader(Instrumentation inst, File spyJarFile, File agentJarFile) throws Throwable {
        // 将Spy添加到BootstrapClassLoader
        inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));

        // 构造自定义的类加载器ArthasClassloader，尽量减少Arthas对现有工程的侵蚀
        return loadOrDefineClassLoader(agentJarFile);
    }

    private static ClassLoader loadOrDefineClassLoader(File agentJar) throws Throwable {
        if (arthasClassLoader == null) {
            arthasClassLoader = new ArthasClassloader(new URL[]{agentJar.toURI().toURL()});
        }
        return arthasClassLoader;
    }

    private static void initSpy(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        // 该 classLoader 为ArthasClassLoader
        Class<?> adviceWeaverClass = classLoader.loadClass(ADVICEWEAVER);
        Method onBefore = adviceWeaverClass.getMethod(ON_BEFORE, int.class, ClassLoader.class, String.class,
                String.class, String.class, Object.class, Object[].class);
        Method onReturn = adviceWeaverClass.getMethod(ON_RETURN, Object.class);
        Method onThrows = adviceWeaverClass.getMethod(ON_THROWS, Throwable.class);
        Method beforeInvoke = adviceWeaverClass.getMethod(BEFORE_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method afterInvoke = adviceWeaverClass.getMethod(AFTER_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method throwInvoke = adviceWeaverClass.getMethod(THROW_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method reset = AgentBootstrap.class.getMethod(RESET);
        Spy.initForAgentLauncher(classLoader, onBefore, onReturn, onThrows, beforeInvoke, afterInvoke, throwInvoke, reset);
    }


    /**
     * 获取arthas-spy.jar，用bootstrapClassLoader进行加载
     * 创建自定义加载器
     * 初始化探针，加载com.taobao.arthas.core.advisor.AdviceWeaver中的methodOnBegin
     * methodOnReturnEnd,methodOnThrowingEnd等待方法，
     * 加载com.taobao.arthas.core.server.ArthasBootstrap，调用bind方法，启动server服务
     *
     */
    private static synchronized void main(String args, final Instrumentation inst) {
        try {
            ps.println("Arthas server agent start...");
            // 传递的args参数分两个部分:agentJar路径和agentArgs, 分别是Agent的JAR包路径和期望传递到服务端的参数
            args = decodeArg(args);
            int index = args.indexOf(';');
            String agentJar = args.substring(0, index);
            final String agentArgs = args.substring(index);

            File agentJarFile = new File(agentJar);
            if (!agentJarFile.exists()) {
                ps.println("Agent jar file does not exist: " + agentJarFile);
                return;
            }

            File spyJarFile = new File(agentJarFile.getParentFile(), ARTHAS_SPY_JAR);
            if (!spyJarFile.exists()) {
                ps.println("Spy jar file does not exist: " + spyJarFile);
                return;
            }

            /**
             * Use a dedicated thread to run the binding logic to prevent possible memory leak. #195
             *  找到 arthas-spy.jar,并调用 Instrumentation#appendToBootstrapClassLoaderSearch 方法，使用
             *  bootstrapClassLoader来加载arthas-spy.jar类的Spy类
             *
             */
            final ClassLoader agentLoader = getClassLoader(inst, spyJarFile, agentJarFile);
            // arthas-agent路径传递给自定义的classLoader（ArthasClassLoader），用来隔离arthas配制的类和目标进程的类
            // 使用ArthasClassLoader#loaderClass方法，加载com.taobao.arthas.core.advisor.AdviceWeaver类
            // 并将里面的methodOnBegin,methodOnReturnEnd,methodOnThrowingEnd等方法取出赋值给Spy类对应的方法
            // 同时Spy类里面的方法又会通过ASM字节码增强方式，编织到目标代码的方法里，使得spy间谍可以关联由AppClassLoader加载
            // 的电影票进程的业务类和
            // ArthasClassLoader加载的arthas类，因此Spy类可以看做是两者之间的桥梁，根据ClassLoader双亲委派特性，子
            // classLoader可以访问父classloader加载的类，源码如下
            //
            initSpy(agentLoader);

            // 异步调用bind方法，该方法最终启动server监听线程，监听客户端连接，包括telnet和websocket两种通信方式，源码如下
            // 主要做两件事情
            // 使用ArthasClassLoader加载com.taobao.arthas.core.config.Configure类位于arthas-core.jar ,并将
            // 传递过来的序列化之后的config，反序列化成对应的Configure对象
            // 使用ArthasClassLoader加载com.taobao.arthas.core.server.ArthasBootstrap类，位于arthas-core.jar
            // 并调用bind方法
            //
            Thread bindingThread = new Thread() {
                @Override
                public void run() {
                    try {
                        bind(inst, agentLoader, agentArgs);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace(ps);
                    }
                }
            };

            bindingThread.setName("arthas-binding-thread");
            bindingThread.start();
            bindingThread.join();
        } catch (Throwable t) {
            t.printStackTrace(ps);
            try {
                if (ps != System.err) {
                    ps.close();
                }
            } catch (Throwable tt) {
                // ignore
            }
            throw new RuntimeException(t);
        }
    }



    // 启动服务器，并监听客户端请求
    private static void bind(Instrumentation inst, ClassLoader agentLoader, String args) throws Throwable {
        /**
         * <pre>
         * Configure configure = Configure.toConfigure(args);
         * int javaPid = configure.getJavaPid();
         * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(javaPid, inst);
         * </pre>
         */
        Class<?> classOfConfigure = agentLoader.loadClass(ARTHAS_CONFIGURE);
        Object configure = classOfConfigure.getMethod(TO_CONFIGURE, String.class).invoke(null, args);
        int javaPid = (Integer) classOfConfigure.getMethod(GET_JAVA_PID).invoke(configure);
        Class<?> bootstrapClass = agentLoader.loadClass(ARTHAS_BOOTSTRAP);
        Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, int.class, Instrumentation.class).invoke(null, javaPid, inst);
        boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
        if (!isBind) {
            try {
                ps.println("Arthas start to bind...");
                bootstrapClass.getMethod(BIND, classOfConfigure).invoke(bootstrap, configure);
                ps.println("Arthas server bind success.");
                return;
            } catch (Exception e) {
                ps.println("Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.");
                throw e;
            }
        }
        ps.println("Arthas server already bind.");
    }

    private static String decodeArg(String arg) {
        try {
            return URLDecoder.decode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return arg;
        }
    }
}
