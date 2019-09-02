package com.taobao.arthas.core.command.monitor200;

import java.util.Arrays;

import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;


/****
 * command 分析
 * arthas 里预设了很多的命令，接下来我们将根据watchCommand进行详细分析，除了字节码增强命令外的其他的命令比较简单，主要使用java.lang.management包的类
 * 来获取虚拟机信息
 *
 * WatchCommand 主要用于观测方法的入参和返回参数信息，以及方法的耗时统计，使用ASM字节码对特定的class进行增强，并重新加载class使之生效
 * 因此在研读这个命令的时候请先了解ASM和Class文件规范，public class WatchCommnad extends EnhancerCommand，WatchCommand继承于EnhancerCommand
 * WatchCommand继承于EnhancerCommand，详细查看EnhancerCommand.enhance方法中的Enhancer.enhance方法，
 * 1.筛选出需要增强的类，根据className进行条件过滤
 * 2.构建增强器。
 * 3.Instrumentation.retransformClasses重新加载类，重新加载会触发ClassFileTransformer.transform方法，对指定的类进行字节码的编辑
 *
 *
 * 这里重点要说明的是观察表达式，观察表达式的构成的主要由ongl表达式组成，所以你可以这样写"{params,returnObj}",只要是一个合法的ongl表达式
 * 都能被正常的支持
 *
 * 观察的维度也是比较多的,主要体现在参数advice的数据结构上，advice
 *
 * 方法执行数据观测
 * 让你能方便的观察到指定的方法调用情况，能观察到的范围为，返回值，抛出异常，入参，通过编写ognl表达式fjTf对应的变量查看
 *
 *
 *
 */
@Name("watch")
@Summary("Display the input/output parameter, return object, and thrown exception of specified method invocation")
@Description(Constants.EXPRESS_DESCRIPTION + "\nExamples:\n" +
        "  watch -b org.apache.commons.lang.StringUtils isBlank params\n" +
        "  watch -f org.apache.commons.lang.StringUtils isBlank returnObj\n" +
        "  watch org.apache.commons.lang.StringUtils isBlank '{params, target, returnObj}' -x 2\n" +
        "  watch -bf *StringUtils isBlank params\n" +
        "  watch *StringUtils isBlank params[0]\n" +
        "  watch *StringUtils isBlank params[0] params[0].length==1\n" +
        "  watch *StringUtils isBlank params '#cost>100'\n" +
        "  watch -E -b org\\.apache\\.commons\\.lang\\.StringUtils isBlank params[0]\n" +
        Constants.WIKI + Constants.WIKI_HOME + "watch")
public class WatchCommand extends EnhancerCommand {
    
    private String classPattern;
    private String methodPattern;
    private String express;
    private String conditionExpress;
    private boolean isBefore = false;
    private boolean isFinish = false;
    private boolean isException = false;
    private boolean isSuccess = false;
    private Integer expand = 1;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;

    @Argument(index = 0, argName = "class-pattern")                   //类名表达式匹配
    @Description("The full qualified class name you want to watch")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Argument(index = 1, argName = "method-pattern")                    //方法名表达式匹配
    @Description("The method name you want to watch")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(index = 2, argName = "express")                           //观察表达式匹配
    @Description("the content you want to watch, written by ognl.\n" + Constants.EXPRESS_EXAMPLES)
    public void setExpress(String express) {
        this.express = express;
    }

    @Argument(index = 3, argName = "condition-express", required = false)               //条件表达式匹配
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "b", longName = "before", flag = true)          //方法调用之前观察
    @Description("Watch before invocation")
    public void setBefore(boolean before) {
        isBefore = before;
    }

    @Option(shortName = "f", longName = "finish", flag = true)              //方法结束之后观察
    @Description("Watch after invocation, enable by default")
    public void setFinish(boolean finish) {
        isFinish = finish;
    }

    @Option(shortName = "e", longName = "exception", flag = true)                       //方法调用异常观察
    @Description("Watch after throw exception")
    public void setException(boolean exception) {
        isException = exception;
    }

    @Option(shortName = "s", longName = "success", flag = true)                 //方法返回之后观察
    @Description("Watch after successful invocation")
    public void setSuccess(boolean success) {
        isSuccess = success;
    }

    @Option(shortName = "M", longName = "sizeLimit")
    @Description("Upper size limit in bytes for the result (10 * 1024 * 1024 by default)")
    public void setSizeLimit(Integer sizeLimit) {
        this.sizeLimit = sizeLimit;
    }

    @Option(shortName = "x", longName = "expand")               //指定输出结果的属性遍历深度，默认为1
    @Description("Expand level of object (1 by default)")
    public void setExpand(Integer expand) {
        this.expand = expand;
    }

    @Option(shortName = "E", longName = "regex", flag = true)               //开启正则表达式匹配
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")
    @Description("Threshold of execution times")
    public void setNumberOfLimit(int numberOfLimit) {
        this.numberOfLimit = numberOfLimit;
    }

    public String getClassPattern() {
        return classPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public String getExpress() {
        return express;
    }

    public String getConditionExpress() {
        return conditionExpress;
    }

    public boolean isBefore() {
        return isBefore;
    }

    public boolean isFinish() {
        return isFinish;
    }

    public boolean isException() {
        return isException;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public Integer getExpand() {
        return expand;
    }

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public boolean isRegEx() {
        return isRegEx;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }

    @Override
    protected Matcher getClassNameMatcher() {
        if (classNameMatcher == null) {
            classNameMatcher = SearchUtils.classNameMatcher(getClassPattern(), isRegEx());
        }
        return classNameMatcher;
    }

    @Override
    protected Matcher getMethodNameMatcher() {
        if (methodNameMatcher == null) {
            methodNameMatcher = SearchUtils.classNameMatcher(getMethodPattern(), isRegEx());
        }
        return methodNameMatcher;
    }

    @Override
    protected AdviceListener getAdviceListener(CommandProcess process) {
        return new WatchAdviceListener(this, process);
    }

    @Override
    protected void completeArgument3(Completion completion) {
        CompletionUtils.complete(completion, Arrays.asList(EXPRESS_EXAMPLES));
    }
}
