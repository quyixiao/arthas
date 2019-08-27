package com.taobao.arthas.core.testtool;

import com.taobao.arthas.core.util.ClassUtils;
import com.taobao.arthas.core.util.Decompiler;

public class JadTest {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.taobao.arthas.core.testtool.TestUtils");
       String a =  ClassUtils.getCodeSource(
                c.getProtectionDomain().getCodeSource());
        System.out.println(a );
        String source = Decompiler.decompile("/Users/quyixiao/project/arthas/core/target/test-classes" +
                "/com/taobao/arthas/core/testtool/TestUtils.class", "newArrayList");
        System.out.println(source);
    }
}
