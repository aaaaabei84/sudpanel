package com.sudpanel.api.script;

import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Invoked only by the isolation regression, under exactly the production worker profile. */
public final class ScriptOsProbe {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        Path read = Path.of(input.readLine()), write = Path.of(input.readLine()); int port = Integer.parseInt(input.readLine());
        try { Files.readString(read); System.out.println("READ_ALLOWED"); } catch (IOException e) { System.out.println("READ_DENIED"); }
        try { Files.writeString(write, "forbidden"); System.out.println("WRITE_ALLOWED"); } catch (IOException e) { System.out.println("WRITE_DENIED"); }
        try (var socket = new Socket("127.0.0.1", port)) { System.out.println("NETWORK_ALLOWED"); } catch (IOException e) { System.out.println("NETWORK_DENIED"); }
        try { new ProcessBuilder("/bin/echo", "forbidden").start().waitFor(); System.out.println("FORK_ALLOWED"); } catch (IOException e) { System.out.println("FORK_DENIED"); }
        System.out.println(System.getenv("HOME") == null ? "HOME_CLEARED" : "HOME_INHERITED");
    }
}
