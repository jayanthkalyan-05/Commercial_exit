// MainWorkflow.java
package com.engine.workflow;

public class MainWorkflow {
    public static void main(String[] args) {
        String configFile = "workflow_config.json";
        if (args.length > 0) {
            configFile = args[0];
        }
        
        Custom_CE_workflow runner = new Custom_CE_workflow(configFile);
        boolean success = runner.runWorkflow();
        System.exit(success ? 0 : 1);
    }
}