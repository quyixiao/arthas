package com.taobao.arthas.core.command.klass100;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ClassUtils;
import com.taobao.arthas.core.util.Decompiler;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.TypeRenderUtils;
import com.taobao.arthas.core.util.affect.RowAffect;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.middleware.logger.Logger;
import com.taobao.text.Color;
import com.taobao.text.Decoration;
import com.taobao.text.lang.LangRenderUtil;
import com.taobao.text.ui.Element;
import com.taobao.text.ui.LabelElement;
import com.taobao.text.ui.TableElement;
import com.taobao.text.util.RenderUtil;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.taobao.text.ui.Element.label;

/**
 * @author diecui1202 on 15/11/24.
 * @author hengyunabc 2018-11-16
 * 获取到类的字节码
 * 2反编译为java代码
 * 比如ClassLoader.getResource("java/lang/String.class"),但是样子查找到的字码不一定，比如可能有多个
 * 冲突的jar。或者java Agetn修改了字节码
 *
 * 从JDK 1.5 有一套，ClassFileTransformer的机制，java Agent通过Instrumentation注册ClassFileTransformer，那么
 * 类加载或者retransform时就可以回调修改字节码
 *
 * 显然，在Arthas里，要增强类是已经被加载的，所以它们的字节码都是retransform时被修改
 * 通过显示的Instrumentation.tetransformClasses(Class<?> ...) 可以触发回调
 * Arthas 里增强字节码的watch/trace/stack/tt等命令都是通过ClassFileTransformer来实现
 * 看到这里，读者应该猜到jad是怎样获取到字节码的了
 * 注册一个ClassFileTransformer
 * 通过Instrumentation.retransformClasses触发回调
 * 在回调的transform函数里获取到字节码
 * 删掉注册的ClassFileTransformer
 *
 *
 * jad命令的的缺陷
 * 99%的情况下，jad命令是有缺陷的，jad命令是dump下来的字节码是准确的，除了一些极端的情况下
 * 因为JVM的注册的ClassFileTransformer可能有多个，那么jvm里运行的字节码里，可能多个ClassFileTransformer处理过的
 *
 * 触发retransformClasses之后，这些注册的ClassFileTransformer会被依次回，上一个处理的字节码传递到下一个
 * 所以不能保证这些ClassFileTransformer第二次执行会返回结果
 * 有可能一些ClassFileTransformer会被删掉，触发retransformClasses之后，之前的一些修改就会丢失掉
 * 所以，目前在Arthas里，如果开两个窗口，一个窗口执行watch/tt等命令，另一个窗口对这个类执行jad，那么可以观察watch/tt
 * 停止了输出，实际上因为字节码在触发了retransformClasses之后，watch/tt所做的修改丢失了
 *
 *
 * 反编译指定出的加载类的源码
 * jad 命令将JVM中的实际运行的class的byte code 反编译成java代码，便于你理解业务逻辑
 * 在Arthas Console上，反编译出来的源码是带有语法高亮的，阅读更加方便
 *
 * 当然，反编译出来的java代码可能存在，便于你理解业务逻辑
 *
 * 在Arthas Console上，反编译出来的源码是带语法高亮的，阅读更加方便
 *
 * 当然，反编译出来的java代码可能会存在语法的错误，但是不影响你进行阅读理解
 *
 *
 *
 * 反编译时只显示源代码
 *
 * 默认情况下，反编译结果里会带有ClassLoader信息，通过--source-only选项，可以只打印源代码，方便和mc/redefine命令结合使用
 *
 *
 * 反编译时指定ClassLoader
 * 当有多个ClassLoader都加载了这个类时，jad命令会输出对应的ClassLoader实例的hashcode,然后只需要重新执行jad命令，并使用参数-c就可吧以反编译
 * 指定ClassLoader加载的那个类了
 *
 * jad org.apache.log4j.Logger
 *
 */
@Name("jad")
@Summary("Decompile class")
@Description(Constants.EXAMPLE +
        "  jad java.lang.String\n" +
        "  jad java.lang.String toString\n" +
        "  jad --source-only java.lang.String\n" +
        "  jad -c 39eb305e org/apache/log4j/Logger\n" +
        "  jad -c 39eb305e -E org\\\\.apache\\\\.*\\\\.StringUtils\n" +
        Constants.WIKI + Constants.WIKI_HOME + "jad")
public class JadCommand extends AnnotatedCommand {
    private static final Logger logger = LogUtil.getArthasLogger();
    private static Pattern pattern = Pattern.compile("(?m)^/\\*\\s*\\*/\\s*$" + System.getProperty("line.separator"));

    private String classPattern;
    private String methodName;
    private String code = null;
    private boolean isRegEx = false;

    /**
     * jad output source code only
     */
    private boolean sourceOnly = false;

    @Argument(argName = "class-pattern", index = 0)                     //类名表达式匹配
    @Description("Class name pattern, use either '.' or '/' as separator")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Argument(argName = "method-name", index = 1, required = false)
    @Description("method name pattern, decompile a specific method instead of the whole class")
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }


    @Option(shortName = "c", longName = "code")             //类所属的ClassLoader 和hashcode
    @Description("The hash code of the special class's classLoader")
    public void setCode(String code) {
        this.code = code;
    }

    @Option(shortName = "E", longName = "regex", flag = true)       //开启正则表达式匹配，默认的通配符匹配
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(longName = "source-only", flag = true)
    @Description("Output source code only")
    public void setSourceOnly(boolean sourceOnly) {
        this.sourceOnly = sourceOnly;
    }

    @Override
    public void process(CommandProcess process) {
        RowAffect affect = new RowAffect();
        Instrumentation inst = process.session().getInstrumentation();
        Set<Class<?>> matchedClasses = SearchUtils.searchClassOnly(inst, classPattern, isRegEx, code);

        try {
            if (matchedClasses == null || matchedClasses.isEmpty()) {
                processNoMatch(process);
            } else if (matchedClasses.size() > 1) {
                processMatches(process, matchedClasses);
            } else { // matchedClasses size is 1
                // find inner classes.
                Set<Class<?>> withInnerClasses = SearchUtils.searchClassOnly(inst,  matchedClasses.iterator().next().getName() + "$*", false, code);
                if(withInnerClasses.isEmpty()) {
                    withInnerClasses = matchedClasses;
                }
                processExactMatch(process, affect, inst, matchedClasses, withInnerClasses);
            }
        } finally {
            if (!this.sourceOnly) {
                process.write(affect + "\n");
            }
            process.end();
        }
    }


    public static void retransformClasses(Instrumentation inst, ClassFileTransformer transformer, Set<Class<?>> classes) {
        try {
            inst.addTransformer(transformer, true);
            for(Class<?> clazz : classes) {
                try{
                    inst.retransformClasses(clazz);
                }catch(Throwable e) {
                    String errorMsg = "retransformClasses class error, name: " + clazz.getName();
                    if(ClassUtils.isLambdaClass(clazz) && e instanceof VerifyError) {
                        errorMsg += ", Please ignore lambda class VerifyError: https://github.com/alibaba/arthas/issues/675";
                    }
                    logger.error("jad", errorMsg, e);
                }
            }
        } finally {
            inst.removeTransformer(transformer);
        }
    }

    private void processExactMatch(CommandProcess process, RowAffect affect, Instrumentation inst, Set<Class<?>> matchedClasses, Set<Class<?>> withInnerClasses) {
        Class<?> c = matchedClasses.iterator().next();
        Set<Class<?>> allClasses = new HashSet<Class<?>>(withInnerClasses);
        allClasses.add(c);

        try {
            ClassDumpTransformer transformer = new ClassDumpTransformer(allClasses);
            retransformClasses(inst, transformer, allClasses);

            Map<Class<?>, File> classFiles = transformer.getDumpResult();
            File classFile = classFiles.get(c);

            String source = Decompiler.decompile(classFile.getAbsolutePath(), methodName);
            if (source != null) {
                source = pattern.matcher(source).replaceAll("");
            } else {
                source = "unknown";
            }

            if (this.sourceOnly) {
                process.write(LangRenderUtil.render(source) + "\n");
                return;
            }


            process.write("\n");
            process.write(RenderUtil.render(new LabelElement("ClassLoader: ").style(Decoration.bold.fg(Color.red)), process.width()));
            process.write(RenderUtil.render(TypeRenderUtils.drawClassLoader(c), process.width()) + "\n");
            process.write(RenderUtil.render(new LabelElement("Location: ").style(Decoration.bold.fg(Color.red)), process.width()));
            process.write(RenderUtil.render(new LabelElement(ClassUtils.getCodeSource(
                    c.getProtectionDomain().getCodeSource())).style(Decoration.bold.fg(Color.blue)), process.width()) + "\n");
            process.write(LangRenderUtil.render(source) + "\n");
            process.write(com.taobao.arthas.core.util.Constants.EMPTY_STRING);
            affect.rCnt(classFiles.keySet().size());
        } catch (Throwable t) {
            logger.error(null, "jad: fail to decompile class: " + c.getName(), t);
        }
    }

    private void processMatches(CommandProcess process, Set<Class<?>> matchedClasses) {
        Element usage = new LabelElement("jad -c <hashcode> " + classPattern).style(Decoration.bold.fg(Color.blue));
        process.write("\n Found more than one class for: " + classPattern + ", Please use "
                + RenderUtil.render(usage, process.width()));

        TableElement table = new TableElement().leftCellPadding(1).rightCellPadding(1);
        table.row(new LabelElement("HASHCODE").style(Decoration.bold.bold()),
                new LabelElement("CLASSLOADER").style(Decoration.bold.bold()));

        for (Class<?> c : matchedClasses) {
            ClassLoader classLoader = c.getClassLoader();
            table.row(label(Integer.toHexString(classLoader.hashCode())).style(Decoration.bold.fg(Color.red)),
                    TypeRenderUtils.drawClassLoader(c));
        }

        process.write(RenderUtil.render(table, process.width()) + "\n");
    }

    private void processNoMatch(CommandProcess process) {
        process.write("No class found for: " + classPattern + "\n");
    }

    @Override
    public void complete(Completion completion) {
        int argumentIndex = CompletionUtils.detectArgumentIndex(completion);

        if (argumentIndex == 1) {
            if (!CompletionUtils.completeClassName(completion)) {
                super.complete(completion);
            }
            return;
        } else if (argumentIndex == 2) {
            if (!CompletionUtils.completeMethodName(completion)) {
                super.complete(completion);
            }
            return;
        }

        super.complete(completion);
    }
}
