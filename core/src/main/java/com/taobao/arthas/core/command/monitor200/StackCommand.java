package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 * Jstack命令<br/>
 * 负责输出当前方法执行上下文
 *
 * 输出当前方法被调用的调用路径，
 * 很多时候我们都知道一个方法被执行，但这个方法被执行的路径非常多，或者你根本就不知道这个方法是从哪里被执行了，此时你需要的是stack命令
 *
 *
 * 这里重点要说明的是观察表达式，观察表达式的构成主要由ognl表达式组成，所以你可以这样实际写"{params,returnObj}"，只要是一个合法的
 * ognl表达式，都能被正常支持
 *
 * stack demo.MathGame primeFactors
 * 据条件表达式来过虑
 * stack demo.MathGame primeFactors 'params[0]<0' -n 2
 * 据执行时间 demo.MathGame primeFactors '#cost>5'
 *
 *
 *
 *
 * @author vlinux
 * @author hengyunabc 2016-10-31
 */
@Name("stack")
@Summary("Display the stack trace for the specified class and method")
@Description(Constants.EXPRESS_DESCRIPTION + Constants.EXAMPLE +
        "  stack org.apache.commons.lang.StringUtils isBlank\n" +
        "  stack *StringUtils isBlank\n" +
        "  stack *StringUtils isBlank params[0].length==1\n" +
        "  stack *StringUtils isBlank '#cost>100'\n" +
        "  stack -E org\\.apache\\.commons\\.lang\\.StringUtils isBlank\n" +
        Constants.WIKI + Constants.WIKI_HOME + "stack")
public class StackCommand extends EnhancerCommand {
    private String classPattern;
    private String methodPattern;
    private String conditionExpress;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;

    @Argument(index = 0, argName = "class-pattern")         //输出当前方法被调用的调用路径
    @Description("Path and classname of Pattern Matching")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Argument(index = 1, argName = "method-pattern", required = false)      //方法名表达式匹配
    @Description("Method of Pattern Matching")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(index = 2, argName = "condition-express", required = false)   //条件表达式
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "E", longName = "regex", flag = true)   //开户正则表达式匹配，默认为通配符匹配
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")       // 执行次数限制
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

    public String getConditionExpress() {
        return conditionExpress;
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
        return new StackAdviceListener(this, process);
    }

}
