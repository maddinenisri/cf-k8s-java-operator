# K8s operator for Cloudformation Stack

## Build and Publish
```shell script
  mvn -P no-integration-tests package dockerfile:build dockerfile:push
```

## Deploy into K8s
- Create namespace
```shell script
  kubectl apply -f k8s/namespace.yaml
```
- Deploy CRD
```shell script
  kubectl apply -f k8s/crd.yaml
```
- Setup RBAC
```shell script
  kubectl apply -f k8s/rbac.yaml
```

- Deploy Pod
Replace "DOCKER_REGISTRY" and "OPERATOR_VERSION" values dependens on build and execute template
```shell script
  kubectl apply k8s/deployment.yaml
```

- Deploy custom cloudformation stack
```shell script

```


