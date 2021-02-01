package com.mdstech.opearator.cloudformation;

import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class TemplateProcessor {

    private final AmazonCloudFormation amazonCloudFormation;
    private final Collection<String> defaultCapabilities;
    private final List<Tag> defaultTags;
    private static final Logger log = LoggerFactory.getLogger(TemplateProcessor.class);

    public TemplateProcessor(AmazonCloudFormation amazonCloudFormation, List<Tag> defaultTags, Collection<String> defaultCapabilities) {
        this.amazonCloudFormation = amazonCloudFormation;
        this.defaultCapabilities = defaultCapabilities;
        this.defaultTags = defaultTags;
    }

    public CreateStackResult createStack(String name, String cutomRoleARN, String templateURL, Map<String, String> parameters, Map<String, String> tags) {
        CreateStackRequest createStackRequest =  new CreateStackRequest()
                .withCapabilities(defaultCapabilities)
                .withStackName(name)
                .withRoleARN(cutomRoleARN)
                .withTemplateURL(templateURL)
                .withParameters(convertToParameters(parameters))
                .withTags(convertToTags(tags));
        log.info("Create Stack {}", createStackRequest);
        try {
            return amazonCloudFormation.createStack(createStackRequest);
        }
        catch(AmazonCloudFormationException ex) {
            log.error("Got exception while creating", ex);
            return null;
        }
    }

    public UpdateStackResult updateStack(String name, String customRoleARN, String templateURL, Map<String, String> parameters, Map<String, String> tags) {
        UpdateStackRequest updateStackRequest = new UpdateStackRequest()
                .withCapabilities(defaultCapabilities)
                .withStackName(name)
                .withRoleARN(customRoleARN)
                .withTemplateURL(templateURL)
                .withParameters(convertToParameters(parameters))
                .withTags(convertToTags(tags));
        log.info("Update Stack {}", updateStackRequest);
        try {
            return amazonCloudFormation.updateStack(updateStackRequest);
        }
        catch(AmazonCloudFormationException ex) {
            log.error("Got exception while updating ", ex);
            return null;
        }
    }

    public DeleteStackResult deleteStack(String name) {
        DeleteStackRequest deleteStackRequest = new DeleteStackRequest().withStackName(name);
        try {
            return amazonCloudFormation.deleteStack(deleteStackRequest);
        }
        catch (AmazonCloudFormationException ex) {
            log.error("Got exception while deleting ", ex);
            return null;
        }
    }

    public boolean isCreatedOrUpdatedStackExist(String stackName) {
        List<String> stackStatuses = Arrays.asList("CREATE_COMPLETE", "UPDATE_COMPLETE");
        log.info("Before verifying stack {} and status {} exists...", stackName, stackStatuses);
        ListStacksResult listStacksResult = amazonCloudFormation.listStacks(
                new ListStacksRequest().withStackStatusFilters(stackStatuses));
        List<String> stackSummaries =
                listStacksResult
                        .getStackSummaries()
                        .stream()
                        .filter(stackSummary -> stackSummary.getStackName().equals(stackName) && stackStatuses.contains(stackSummary.getStackStatus()))
                        .map(StackSummary::getStackName)
                        .collect(Collectors.toList());
        log.info("Stack {} exists : {}", stackName, stackSummaries.contains(stackName));
        return stackSummaries.contains(stackName);
    }

    public boolean isStackDeletable(String stackName) {
        ListStacksResult listStacksResult = amazonCloudFormation.listStacks();
        List<String> stackStatuses =
                listStacksResult
                        .getStackSummaries()
                        .stream()
                        .filter(stackSummary -> stackSummary.getStackName().equals(stackName))
                        .map(StackSummary::getStackStatus)
                        .collect(Collectors.toList());
        log.info("Stack {} Statuses {} ", stackName, stackStatuses);
        return !( stackStatuses == null || stackStatuses.isEmpty() || stackStatuses.contains("DELETE_COMPLETE"));
    }

    private Collection<Tag> convertToTags(Map<String, String> tags) {
        List<Tag> tagList = new ArrayList<>();
        if(!(defaultTags == null || defaultTags.isEmpty())) {
            tagList.addAll(defaultTags);
        }
        if(tags != null) {
            tagList.addAll(tags.entrySet().stream().map(entry -> new Tag().withKey(entry.getKey()).withValue(entry.getValue())).collect(Collectors.toList()));
        }
        return tagList;
    }

    private Collection<Parameter> convertToParameters(Map<String, String> parameters) {
        if(parameters == null || parameters.isEmpty()) {
            return null;
        }
        return parameters.entrySet().stream()
                .map(entry -> new Parameter().withParameterKey(entry.getKey()).withParameterValue(entry.getValue())).collect(Collectors.toList());
    }

    private Collection<String> convertToCapabilities(String capabilities) {
        if(capabilities == null) {
            return null;
        }
        return Arrays.stream(capabilities.split(",")).collect(Collectors.toList());
    }
}
