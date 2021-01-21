package com.mdstech.opearator.cloudformation;

import java.util.Map;

public class StackStatus {
    private String stackID;
    private String status;
    private Map<String, String> outputs;

    public String getStackID() {
        return stackID;
    }

    public void setStackID(String stackID) {
        this.stackID = stackID;
    }

    public Map<String, String> getOutputs() {
        return outputs;
    }

    public void setOutputs(Map<String, String> outputs) {
        this.outputs = outputs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
