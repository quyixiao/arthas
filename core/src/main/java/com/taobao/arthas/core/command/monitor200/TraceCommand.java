package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.matcher.GroupMatcher;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.arthas.core.util.matcher.RegexMatcher;
import com.taobao.arthas.core.util.matcher.TrueMatcher;
import com.taobao.arthas.core.util.matcher.WildcardMatcher;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用跟踪命令<br/>
 * 负责输出一个类中的所有方法调用路径
 * 方法内部的内部的路径，并输出方法路径上的每个节点上的耗时
 * trace 命令主动搜索class-pathern/method-pattern 对应的方法调用的路径，渲染和统计整个调用的链路上的所有的性能开销和追踪调用链路
 *
 * 这重点要说明的是观察表达式，观察表达式的构成主要由ognl表达式组成，所以你可以这样写"{params,returnObj}"，只要是一个合法的ognl表达式，都能被
 * 正常的支持
 *
 * 观察的维度也是比较多的，主要体现在advice的数据结构上，Advice参数最主要的封装是通过通知节点的所有的信息
 * 很多的时候，我们只想看到某个方法的的rt大于某个时间之后的trace结果，现在Arthas可以按照方法的执行耗时来进行过虑了，例如trace * StringUtils
 * isBlank '#cost>100'表示当前的执行时间超过100ms的时候，都会输出trace结果
 *
 *  watch/stack/trace这个三个命令都支持#cost
 *
 *  注意事项
 *  trace能方便的帮助你定位和发现因RT高而导致性能问题缺陷，但是每次只能跟踪一级方案的调用链路
 *
 *  trace函数
 *  trace demo.mathGame run
 *  过虑掉jdk的函数
 *  trace -j demo.MathGame run
 *  -j:jdkMethodSkip,skip jdk methon trace
 *  据调用耗时过滤
 *  trace demo.MathGame run '#cost>10'
 *  只会展示耗时大于100ms的调用路径，有助于在排查问题的时候，只关注异常情况
 *  当前节点在当前节点在当前步骤的耗时，单位为毫秒
 *  [],对该方法中相同的方法调用进行了合并，表示方法调用耗时，min,max,total,count;throws Exception 表明该方法调用中存在异常返回
 *  这里可能存在一个统计不准有的问题，就是所有方法耗时加起来可能会小于该监测方法总耗时，这个是由于arthas配制的逻辑会有一定的耗时
 *  可以用正则表匹配路径上的多个类和函数，一定程度上达到了多层的trace的效果
 *  trace -E com.test.ClassA | org.test.ClassB method | method2 | method3
 *
 *
 *
 *
 *
 *
 *
 *
 *
 * @author vlinux on 15/5/27.
 */
@Name("trace")
@Summary("Trace the execution time of specified method invocation.")
@Description(value = Constants.EXPRESS_DESCRIPTION + Constants.EXAMPLE +
        "  trace org.apache.commons.lang.StringUtils isBlank\n" +
        "  trace *StringUtils isBlank\n" +
        "  trace *StringUtils isBlank params[0].length==1\n" +
        "  trace *StringUtils isBlank '#cost>100'\n" +
        "  trace -E org\\\\.apache\\\\.commons\\\\.lang\\\\.StringUtils isBlank\n" +
        "  trace -E com.test.ClassA|org.test.ClassB method1|method2|method3\n" +
        Constants.WIKI + Constants.WIKI_HOME + "trace")
public class TraceCommand extends EnhancerCommand {

    private String classPattern;
    private String methodPattern;
    private String conditionExpress;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private List<String> pathPatterns;
    private boolean skipJDKTrace;

    @Argument(argName = "class-pattern", index = 0)             //类名表达式匹配
    @Description("Class name pattern, use either '.' or '/' as separator")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Argument(argName = "method-pattern", index = 1)            //方法名表达式匹配
    @Description("Method name pattern")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(argName = "condition-express", index = 2, required = false)       //条件表达式
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "E", longName = "regex", flag = true)           //开启正则表达式，默认为通配符匹配
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")           //命令执行次数
    @Description("Threshold of execution times")
    public void setNumberOfLimit(int numberOfLimit) {
        this.numberOfLimit = numberOfLimit;
    }

    @Option(shortName = "p", longName = "path", acceptMultipleValues = true)
    @Description("path tracing pattern")
    public void setPathPatterns(List<String> pathPatterns) {
        this.pathPatterns = pathPatterns;
    }

    @Option(shortName = "j", longName = "jdkMethodSkip")
    @Description("skip jdk method trace")
    public void setSkipJDKTrace(boolean skipJDKTrace) {
        this.skipJDKTrace = skipJDKTrace;
    }

    public String getClassPattern() {
        return classPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public String getConditionExpress() {
        return conditionExpress;
    }

    public boolean isSkipJDKTrace() {
        return skipJDKTrace;
    }

    public boolean isRegEx() {
        return isRegEx;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }

    public List<String> getPathPatterns() {
        return pathPatterns;
    }

    @Override
    protected Matcher getClassNameMatcher() {
        if (classNameMatcher == null) {
            if (pathPatterns == null || pathPatterns.isEmpty()) {
                classNameMatcher = SearchUtils.classNameMatcher(getClassPattern(), isRegEx());
            } else {
                classNameMatcher = getPathTracingClassMatcher();
            }
        }
        return classNameMatcher;
    }

    @Override
    protected Matcher getMethodNameMatcher() {
        if (methodNameMatcher == null) {
            if (pathPatterns == null || pathPatterns.isEmpty()) {
                methodNameMatcher = SearchUtils.classNameMatcher(getMethodPattern(), isRegEx());
            } else {
                methodNameMatcher = getPathTracingMethodMatcher();
            }
        }
        return methodNameMatcher;
    }

    @Override
    protected AdviceListener getAdviceListener(CommandProcess process) {
        if (pathPatterns == null || pathPatterns.isEmpty()) {
            return new TraceAdviceListener(this, process);
        } else {
            return new PathTraceAdviceListener(this, process);
        }
    }

    /**
     * 构造追踪路径匹配
     */
    private Matcher<String> getPathTracingClassMatcher() {

        List<Matcher<String>> matcherList = new ArrayList<Matcher<String>>();
        matcherList.add(SearchUtils.classNameMatcher(getClassPattern(), isRegEx()));

        if (null != getPathPatterns()) {
            for (String pathPattern : getPathPatterns()) {
                if (isRegEx()) {
                    matcherList.add(new RegexMatcher(pathPattern));
                } else {
                    matcherList.add(new WildcardMatcher(pathPattern));
                }
            }
        }

        return new GroupMatcher.Or<String>(matcherList);
    }

    private Matcher<String> getPathTracingMethodMatcher() {
        return new TrueMatcher<String>();
    }
}
