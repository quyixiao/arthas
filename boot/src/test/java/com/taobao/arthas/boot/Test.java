package com.taobao.arthas.boot;

import static com.taobao.arthas.boot.DownloadUtils.getRepoUrl;
import static com.taobao.arthas.boot.DownloadUtils.readMavenMetaData;

public class Test {

    public static void main(String[] args) {
        System.out.println("11111111111");

        System.out.println(getRepoUrl("https/", true));

        System.out.println(readMavenMetaData("center", true));
    }
}
