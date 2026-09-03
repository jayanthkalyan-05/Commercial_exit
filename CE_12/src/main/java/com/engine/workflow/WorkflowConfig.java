// WorkflowConfig.java - Helper class for config parsing
package com.engine.workflow;

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class WorkflowConfig {
    
    private JsonObject config;
    
    public WorkflowConfig(String configFile) throws IOException {
        try (Reader reader = new FileReader(configFile)) {
            Gson gson = new Gson();
            config = gson.fromJson(reader, JsonObject.class);
        }
    }
    
    public String getWorkflowName() {
        return config.has("workflow_name") ? config.get("workflow_name").getAsString() : "Unnamed Workflow";
    }
    
    public String getCommonFolder() {
        return config.has("common_folder") ? config.get("common_folder").getAsString() : ".";
    }
    
    public boolean isCopyOutputToCommon() {
        return config.has("copy_output_to_common") && config.get("copy_output_to_common").getAsBoolean();
    }
    
    public boolean isCleanupEnabled() {
        return config.has("cleanup_intermediate_files") && config.get("cleanup_intermediate_files").getAsBoolean();
    }
    
    public JsonArray getScripts() {
        return config.has("scripts") ? config.getAsJsonArray("scripts") : new JsonArray();
    }
    
    public JsonObject getFilePatterns() {
        return config.has("file_patterns") ? config.getAsJsonObject("file_patterns") : new JsonObject();
    }
    
    public JsonObject getDataFiles() {
        return config.has("data_files") ? config.getAsJsonObject("data_files") : new JsonObject();
    }
}