package com.taobao.arthas.core.command.klass100;


import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ClassUtils;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.affect.RowAffect;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.text.util.RenderUtil;

/**
 * 展示类信息
 *
 * @author vlinux
 *
 *
 * 查看JVM已经加载的类信息
 * "Search-Class"的简写，这个命令能搜索出所有已经的加载的JVM中的Class信息，这个命令支持参数有[d],[E],[f] 和[x:]
 *
 * sc默认开启了子类匹配的功能，也就是说所有当前所有当前类的也会以被搜索出来，想要 精确的匹配，请打开optoins disable-sub-class true 开关
 *
 *
 * sc 模糊搜索
 *
 * sc demo.*
 * demo.MathGame
 * Affect(row-cnt:1) cost in 55 ms.
 *
 * 打印类的详细信息
 * sc -d demo.MathGame
 *
 * 打印出类Field信息
 *
 * sc -d -f demo.MathGame
 *
 *
 *
 *
 */
@Name("sc")
@Summary("Search all the classes loaded by JVM")
@Description(Constants.EXAMPLE +
        "  sc -d org.apache.commons.lang.StringUtils\n" +
        "  sc -d org/apache/commons/lang/StringUtils\n" +
        "  sc -d *StringUtils\n" +
        "  sc -d -f org.apache.commons.lang.StringUtils\n" +
        "  sc -E org\\\\.apache\\\\.commons\\\\.lang\\\\.StringUtils\n" +
        Constants.WIKI + Constants.WIKI_HOME + "sc")
public class SearchClassCommand extends AnnotatedCommand {
    private String classPattern;
    private boolean isDetail = false;
    private boolean isField = false;
    private boolean isRegEx = false;
    private String hashCode = null;
    private Integer expand;

    //class-pattern支持全限定名，如com.taobao.testAAA,也支持com/taobao/test/AAA这样的格式，这样，我们从异常堆栈里把类名拷贝过秋的时候，不需要在手动
    // 把/替换为.啦
    @Argument(argName = "class-pattern", index = 0)         //类名表达式匹配
    @Description("Class name pattern, use either '.' or '/' as separator")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Option(shortName = "d", longName = "details", flag = true)     //方法名表达式匹配
    @Description("Display the details of class")
    public void setDetail(boolean detail) {
        isDetail = detail;
    }

    @Option(shortName = "f", longName = "field", flag = true)       // 输出当前类的成员变量信息，（需要配合参数-d一起使用）
    @Description("Display all the member variables")
    public void setField(boolean field) {
        isField = field;
    }

    @Option(shortName = "E", longName = "regex", flag = true)       //开户正则表达式匹配，为通配符匹配
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "x", longName = "expand")               //指定输出静态变量时属性遍历深度，默认为0，即直接使用toString()输出

    @Description("Expand level of object (0 by default)")
    public void setExpand(Integer expand) {
        this.expand = expand;
    }

    @Option(shortName = "c", longName = "classloader")
    @Description("The hash code of the special class's classLoader")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Override
    public void process(CommandProcess process) {
        // TODO: null check
        RowAffect affect = new RowAffect();
        Instrumentation inst = process.session().getInstrumentation();
        List<Class<?>> matchedClasses = new ArrayList<Class<?>>(SearchUtils.searchClass(inst, classPattern, isRegEx, hashCode));
        Collections.sort(matchedClasses, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> c1, Class<?> c2) {
                return StringUtils.classname(c1).compareTo(StringUtils.classname(c2));
            }
        });

        for (Class<?> clazz : matchedClasses) {
            processClass(process, clazz);
        }

        affect.rCnt(matchedClasses.size());
        process.write(affect + "\n");
        process.end();
    }

    private void processClass(CommandProcess process, Class<?> clazz) {
        if (isDetail) {
            process.write(RenderUtil.render(ClassUtils.renderClassInfo(clazz, isField, expand), process.width()) + "\n");
        } else {
            process.write(clazz.getName() + "\n");
        }
    }

    @Override
    public void complete(Completion completion) {
        if (!CompletionUtils.completeClassName(completion)) {
            super.complete(completion);
        }
    }
}
