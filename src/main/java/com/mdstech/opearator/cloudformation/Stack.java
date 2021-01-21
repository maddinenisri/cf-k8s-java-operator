package com.mdstech.opearator.cloudformation;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("cloudformartion.mdstechinc.com")
@Version("v1alpha1")
public class Stack extends CustomResource<StackSpec, StackStatus> implements Namespaced {}