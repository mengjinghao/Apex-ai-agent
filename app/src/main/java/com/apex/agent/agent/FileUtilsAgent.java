package com.apex.agent.agent;

import java.lang.instrument.Instrumentation;

public class FileUtilsAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("FileUtilsAgent: premain called");
        inst.addTransformer(new FileUtilsTransformer(), true);
    }
}
