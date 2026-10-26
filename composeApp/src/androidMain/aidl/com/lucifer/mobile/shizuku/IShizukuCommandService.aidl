package com.lucifer.mobile.shizuku;

/** Narrow AIDL surface for commands executed as the identity granted by Shizuku. */
interface IShizukuCommandService {
    String execute(String command, int timeoutSeconds);
    void destroy() = 16777114;
}
