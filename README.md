# AWS Cloud Formation Kubernetes Operator
Java based Kubernetes Operator to process AWS Cloud Formation template as custom resource definition (CRD).  

### Build command to generate docker image
```shell script
  mvn -P no-integration-tests package dockerfile:build
```
### Publish docker image
```shell script
  mvn dockerfile:push
```
- Published docker image
```shell script
  docker pull mdstech/cf-k8s-java-operator:latest
```

## Deployment Steps into Kubernetes environment
- Create namespace
```shell script
  kubectl apply -f k8s/namespace.yaml
```
- Deploy Custom Resource Definition
```shell script
  kubectl apply -f k8s/crd.yaml
```

- Create RBAC role and role binding and Service Account Objects

Note:- Use IAM Role ARN annotation for Service Account, if "assume role arn" is not defined.

```shell script
  kubectl apply -f k8s/rbac.yaml
```

- Deploy Operator
Change docker registry and operator depend on requirements
```shell script
  kubectl apply -f k8s/deployment.yaml
```

- Deploy custom cloud formation stack
```shell script

```


