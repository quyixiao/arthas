package com.taobao.arthas.core.command.klass100;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.taobao.arthas.core.util.LogUtil;
import com.taobao.middleware.logger.Logger;
import org.objectweb.asm.ClassReader;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 * Redefine Classes.
 *
 * @author hengyunabc 2018-07-13
 *  加载外部的.class文件，redefine jvm 已经加载的类
 *  注意，redefine后的原来的类不能恢复，redefine有可能失败，比如增加了新的field，参考jdk本身的文档
 *  参数说明
 *
 *
 *  //jad命令反编译，然后可以用他来编译器，比如vim来修改源码
 *  jad --source-only com.example.demo.arthas.user.UserController > /tmp/UserController.java
 *  //mc命令来内存编译修改过的代码
 *  mc /tmp/UserController.java -d /tmp
 *  //用redefine命令加载新的字节码
 *  redefine /tmp/com./example/demo/arthas/user/UserContorller.class
 *
 *  redefine的的限制
 *  不不允许新增的field/method
 *  正在跑函数的，没有退出不能生效，比如下面的新增加的System.out.println，只有run(函数里)会生效
 *
 *
 *
 *
 * @see java.lang.instrument.Instrumentation#redefineClasses(ClassDefinition...)
 */
@Name("redefine")
@Summary("Redefine classes. @see Instrumentation#redefineClasses(ClassDefinition...)")
@Description(Constants.EXAMPLE +
                "  redefine /tmp/Test.class\n" +
                "  redefine -c 327a647b /tmp/Test.class /tmp/Test\\$Inner.class \n" +
                Constants.WIKI + Constants.WIKI_HOME + "redefine")
public class RedefineCommand extends AnnotatedCommand {
    private static final Logger logger = LogUtil.getArthasLogger();
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private String hashCode;

    private List<String> paths;

    @Option(shortName = "c", longName = "classloader")          //ClassLoader的hashcode
    @Description("classLoader hashcode")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Argument(argName = "classfilePaths", index = 0)            //外部的.class文件的完整的路径，支持多个
    @Description(".class file paths")
    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public void process(CommandProcess process) {
        Instrumentation inst = process.session().getInstrumentation();

        for (String path : paths) {
            File file = new File(path);
            if (!file.exists()) {
                process.write("file does not exist, path:" + path + "\n");
                process.end();
                return;
            }
            if (!file.isFile()) {
                process.write("not a normal file, path:" + path + "\n");
                process.end();
                return;
            }
            if (file.length() >= MAX_FILE_SIZE) {
                process.write("file size: " + file.length() + " >= " + MAX_FILE_SIZE + ", path: " + path + "\n");
                process.end();
                return;
            }
        }

        Map<String, byte[]> bytesMap = new HashMap<String, byte[]>();
        for (String path : paths) {
            RandomAccessFile f = null;
            try {
                f = new RandomAccessFile(path, "r");
                final byte[] bytes = new byte[(int) f.length()];
                f.readFully(bytes);

                final String clazzName = readClassName(bytes);

                bytesMap.put(clazzName, bytes);

            } catch (Exception e) {
                process.write("" + e + "\n");
            } finally {
                if (f != null) {
                    try {
                        f.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        if (bytesMap.size() != paths.size()) {
            process.write("paths may contains same class name!\n");
            process.end();
            return;
        }

        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (bytesMap.containsKey(clazz.getName())) {
                if (hashCode != null && !Integer.toHexString(clazz.getClassLoader().hashCode()).equals(hashCode)) {
                    continue;
                }
                definitions.add(new ClassDefinition(clazz, bytesMap.get(clazz.getName())));
                logger.info("redefine", "Try redefine class name: {}, ClassLoader: {}", clazz.getName(), clazz.getClassLoader());
            }
        }

        try {
            if (definitions.isEmpty()) {
                process.write("These classes are not found in the JVM and may not be loaded: " + bytesMap.keySet()
                                + "\n");
                process.end();
                return;
            }
            inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            process.write("redefine success, size: " + definitions.size() + "\n");
        } catch (Exception e) {
            process.write("redefine error! " + e + "\n");
        }

        process.end();
    }

    private static String readClassName(final byte[] bytes) {
        return new ClassReader(bytes).getClassName().replace("/", ".");
    }

    @Override
    public void complete(Completion completion) {
        if (!CompletionUtils.completeFilePath(completion)) {
            super.complete(completion);
        }
    }
}
