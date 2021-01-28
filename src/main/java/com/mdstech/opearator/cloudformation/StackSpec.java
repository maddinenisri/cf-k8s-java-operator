package com.mdstech.opearator.cloudformation;

import org.apache.commons.lang3.builder.ToStringBuilder;

import java.util.Map;

public class StackSpec {
    private Map<String, String> tags;
    private Map<String, String> parameters;
    private String template;
    private String templateURL;
    private String customRoleARN;

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getCustomRoleARN() {
        return customRoleARN;
    }

    public void setCustomRoleARN(String customRoleARN) {
        this.customRoleARN = customRoleARN;
    }

    public String getTemplateURL() {
        return templateURL;
    }

    public void setTemplateURL(String templateURL) {
        this.templateURL = templateURL;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("tags", tags)
                .append("parameters", parameters)
                .append("template", template)
                .append("templateURL", templateURL)
                .append("customRoleARN", customRoleARN)
                .toString();
    }
}
